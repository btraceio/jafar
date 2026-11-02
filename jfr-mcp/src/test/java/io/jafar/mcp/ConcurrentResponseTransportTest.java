package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Responses sent concurrently from different threads must all reach the client.
 *
 * <p>The MCP SDK completes tool calls on {@code boundedElastic} workers, so two calls in flight
 * call {@code sendMessage} from two threads at once. A Reactor sink requires serialized producers:
 * an unguarded concurrent {@code tryEmitNext} returns {@code FAIL_NON_SERIALIZED}, which the
 * transport used to report as "Failed to enqueue message" and the response was lost
 * (btraceio/jafar#144, CI run 41356077155, {@code McpOtlpTransportTest >
 * otlpFlamegraphReturnsRows()}).
 *
 * <p>These tests go through the real tool stack. {@link ConcurrentEmitTransportTest} drives the
 * bare transport with forced contention.
 */
class ConcurrentResponseTransportTest extends McpTransportTestBase {

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
   * <p>All ids share one deadline of {@link McpTransportHarness#RESPONSE_TIMEOUT_MS}: on a failure
   * the lost ids never arrive, and waiting the full timeout for each of them would only slow the
   * report down.
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

  private static String toolCall(int id, String tool) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool
        + "\",\"arguments\":{}}}";
  }
}
