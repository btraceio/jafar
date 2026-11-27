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
    // The barrier waits get a scheduler of their own (#287). On the shared, capped boundedElastic,
    // busy workers can queue one half of a pair behind its partner's 5 s wait, and such stalls can
    // add up past the 30 s wait without the transport losing anything. The transport runs at most
    // 256 requests at once, so 512 threads leave room for every one in flight.
    reactor.core.scheduler.Scheduler barrierScheduler =
        Schedulers.newBoundedElastic(512, Integer.MAX_VALUE, "emit-test-barrier");
    // Responses the transport accepted, those it refused, and pairs whose barrier timed out
    Set<Integer> sent = ConcurrentHashMap.newKeySet();
    Set<Integer> rejected = ConcurrentHashMap.newKeySet();
    java.util.concurrent.atomic.AtomicInteger barrierTimeouts =
        new java.util.concurrent.atomic.AtomicInteger();
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
                  return Mono.fromRunnable(
                          () -> {
                            if (await(barriers[id / 2])) {
                              barrierTimeouts.incrementAndGet();
                            }
                          })
                      .subscribeOn(barrierScheduler)
                      .then(Mono.defer(() -> sessionTransport.sendMessage(ok)))
                      .doOnSuccess(done -> sent.add(id))
                      .doOnError(error -> rejected.add(id));
                }
              });

      StringBuilder batch = new StringBuilder();
      for (int id = 0; id < total; id++) {
        batch.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id).append(",\"method\":\"x\"}\n");
      }
      clientOut.write(batch.toString().getBytes(StandardCharsets.UTF_8));
      clientOut.flush();

      boolean allArrived = allReceived.await(30, TimeUnit.SECONDS);
      // Give the responses accepted so far time to reach the reader before blaming the transport
      Set<Integer> accepted = new TreeSet<>(sent);
      for (int i = 0; i < 50 && !allArrived && !received.containsAll(accepted); i++) {
        Thread.sleep(100);
      }
      Set<Integer> refused = new TreeSet<>(rejected);
      Set<Integer> lost = new TreeSet<>(accepted);
      lost.removeAll(received);
      Set<Integer> neverSent = new TreeSet<>();
      for (int id = 0; id < total; id++) {
        if (!received.contains(id) && !accepted.contains(id) && !refused.contains(id)) {
          neverSent.add(id);
        }
      }
      // Refused, or accepted and not delivered, is the transport's fault (the #154 race refused
      // concurrent emits); never sent is this test's own stall. All three fail the test.
      assertEquals(
          "",
          refused.isEmpty() && lost.isEmpty() && neverSent.isEmpty()
              ? ""
              : refused.size()
                  + " refused by sendMessage "
                  + head(refused)
                  + ", "
                  + lost.size()
                  + " lost after sendMessage "
                  + head(lost)
                  + ", "
                  + neverSent.size()
                  + " never sent within 30 s "
                  + head(neverSent)
                  + " of "
                  + total
                  + " responses; "
                  + barrierTimeouts.get()
                  + " pair barriers timed out");
    } finally {
      barrierScheduler.dispose();
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

  /**
   * Waits for the pair partner; the response is sent either way.
   *
   * @return whether this wait timed out (its partner then sees a broken barrier, not counted)
   */
  private static boolean await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (TimeoutException e) {
      return true;
    } catch (BrokenBarrierException e) {
      return false;
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
