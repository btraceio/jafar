package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.io.BufferedReader;
import java.io.Closeable;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bare {@link FixedStdioServerTransportProvider} with a stub session, so the contention on the
 * outbound sink is forced on every pair rather than left to tool timing. See {@link
 * ConcurrentResponseTransportTest} for the same property through the real tool stack.
 */
class ConcurrentEmitTransportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Pairs of responses released at the same instant from two threads by a barrier, against a
   * session that answers every request. All of them must reach the client.
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
    CountDownLatch allReceived = new CountDownLatch(total);
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader br =
                  new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                  if (line.isBlank()) continue;
                  JsonNode node = MAPPER.readTree(line);
                  if (node.has("id")
                      && !node.get("id").isNull()
                      && received.add(node.get("id").asInt())) {
                    allReceived.countDown();
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

      allReceived.await(30, TimeUnit.SECONDS);
      Set<Integer> missing = new TreeSet<>();
      for (int id = 0; id < total; id++) {
        if (!received.contains(id)) missing.add(id);
      }
      assertEquals(
          Set.of(),
          missing,
          () -> missing.size() + " of " + total + " responses lost (first: " + head(missing) + ")");
    } finally {
      closeQuietly(clientOut);
      try {
        transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
      } catch (Exception ignored) {
      }
      closeQuietly(serverIn);
      closeQuietly(serverOut);
      closeQuietly(clientIn);
      reader.join(TimeUnit.SECONDS.toMillis(5));
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

  private static void closeQuietly(Closeable c) {
    try {
      c.close();
    } catch (IOException ignored) {
    }
  }
}
