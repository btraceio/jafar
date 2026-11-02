package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Responses sent concurrently from different threads must all reach the client.
 *
 * <p>The MCP SDK completes tool calls on {@code boundedElastic} workers, so two calls in flight
 * call {@code sendMessage} from two threads at once. A Reactor sink requires serialized producers:
 * an unguarded concurrent {@code tryEmitNext} returns {@code FAIL_NON_SERIALIZED}, which the
 * transport used to report as "Failed to enqueue message" and the response was lost (PR #144, CI
 * run 41356077155, {@code McpOtlpTransportTest > otlpFlamegraphReturnsRows()}).
 */
class ConcurrentResponseTransportTest extends McpTransportTestBase {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Two real {@code tools/call} requests written back to back, without waiting for the first
   * response, must both be answered. Repeated because a single pair races only sometimes.
   */
  @Test
  void twoToolCallsSentBackToBackBothGetResponses() throws Exception {
    int id = 100;
    for (int round = 0; round < 200; round++) {
      int a = id++;
      int b = id++;
      harness.write(toolCall(a, "jfr_help"));
      harness.write(toolCall(b, "jfr_help"));
      // waitForId keeps non-matching messages, so the order of arrival does not matter.
      assertSuccess(harness.collector().waitForId(a, McpTransportHarness.RESPONSE_TIMEOUT_MS), a);
      assertSuccess(harness.collector().waitForId(b, McpTransportHarness.RESPONSE_TIMEOUT_MS), b);
    }
  }

  /**
   * Many real {@code tools/call} requests in flight at once must all be answered.
   *
   * <p>Uses a deadline shorter than the harness default: on a failure the lost ids never arrive,
   * and waiting the full default for each of them would only slow the report down.
   */
  @Test
  void manyConcurrentToolCallsAllGetResponses() throws Exception {
    int first = 1_000;
    int count = 500;
    StringBuilder batch = new StringBuilder();
    for (int i = 0; i < count; i++) {
      batch.append(toolCall(first + i, "jfr_help")).append('\n');
    }
    harness.write(batch.toString().stripTrailing());

    Set<Integer> missing = new TreeSet<>();
    long deadline = System.currentTimeMillis() + McpTransportHarness.RESPONSE_TIMEOUT_MS;
    for (int i = 0; i < count; i++) {
      long remaining = Math.max(1, deadline - System.currentTimeMillis());
      JsonNode resp = harness.collector().waitForId(first + i, remaining);
      if (resp == null) {
        missing.add(first + i);
      } else {
        assertSuccess(resp, first + i);
      }
    }
    assertTrue(missing.isEmpty(), () -> missing.size() + " of " + count + " lost: " + missing);
  }

  /**
   * Pairs of responses released at the same instant from two threads by a barrier, against the bare
   * transport with a session that answers every request. Isolates the transport from tool timing so
   * the contention happens on every pair rather than by chance.
   */
  @Test
  void responsesEmittedSimultaneouslyFromTwoThreadsAllArrive() throws Exception {
    int pairs = 2_000;
    int total = pairs * 2;

    PipedInputStream serverIn = new PipedInputStream(1 << 20);
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream(1 << 20);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    FixedStdioServerTransportProvider transport =
        new FixedStdioServerTransportProvider(McpJsonDefaults.getMapper(), serverIn, serverOut);

    Set<Integer> received = ConcurrentHashMap.newKeySet();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader br =
                  new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                  if (line.isBlank()) continue;
                  JsonNode node = MAPPER.readTree(line);
                  if (node.has("id") && !node.get("id").isNull()) {
                    received.add(node.get("id").asInt());
                  }
                }
              } catch (Exception ignored) {
              }
            },
            "test-reader");
    reader.setDaemon(true);
    reader.start();

    // One barrier per pair: requests 2k and 2k+1 meet there, then both call sendMessage at once.
    CyclicBarrier[] barriers = new CyclicBarrier[pairs];
    for (int i = 0; i < pairs; i++) {
      barriers[i] = new CyclicBarrier(2);
    }

    try {
      transport.setSessionFactory(
          (McpServerTransport sessionTransport) ->
              new McpServerSession(
                  "test-session",
                  Duration.ofSeconds(5),
                  sessionTransport,
                  initReq -> Mono.empty(),
                  Collections.emptyMap(),
                  Collections.emptyMap()) {
                @Override
                public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
                  if (!(message instanceof McpSchema.JSONRPCRequest req)) {
                    return Mono.empty();
                  }
                  int id = ((Number) req.id()).intValue();
                  McpSchema.JSONRPCResponse ok =
                      new McpSchema.JSONRPCResponse(
                          McpSchema.JSONRPC_VERSION, req.id(), "ok", null);
                  return Mono.fromRunnable(() -> await(barriers[id / 2]))
                      .subscribeOn(Schedulers.boundedElastic())
                      .then(Mono.defer(() -> sessionTransport.sendMessage(ok)));
                }
              });

      StringBuilder batch = new StringBuilder();
      for (int id = 0; id < total; id++) {
        batch.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id).append(",\"method\":\"x\"}\n");
      }
      clientOut.write(batch.toString().getBytes(StandardCharsets.UTF_8));
      clientOut.flush();

      long deadline = System.currentTimeMillis() + 30_000;
      while (received.size() < total && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      Set<Integer> missing = new TreeSet<>();
      for (int id = 0; id < total; id++) {
        if (!received.contains(id)) missing.add(id);
      }
      assertEquals(
          Set.of(),
          missing,
          () -> missing.size() + " of " + total + " responses lost (first: " + head(missing) + ")");
    } finally {
      try {
        clientOut.close();
      } catch (IOException ignored) {
      }
      try {
        transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
      } catch (Exception ignored) {
      }
      try {
        clientIn.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (BrokenBarrierException | TimeoutException ignored) {
      // Send anyway; a lost response is what the assertion reports.
    }
  }

  private static String head(Set<Integer> ids) {
    return ids.stream().limit(10).toList().toString();
  }

  private static String toolCall(int id, String tool) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool
        + "\",\"arguments\":{}}}";
  }
}
