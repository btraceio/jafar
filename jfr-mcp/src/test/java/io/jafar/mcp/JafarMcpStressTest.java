package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end stress tests that exercise the full in-process stdio MCP pipeline via {@link
 * McpTransportHarness}.
 *
 * <p>These tests are tagged {@code stress} and are excluded from the default {@code test} task; run
 * them via {@code ./gradlew :jfr-mcp:stressTest}.
 *
 * <p>They are designed to surface regressions in the reliability fixes that landed for the "crashes
 * and disconnects" reports — handler error backstops, malformed inbound resilience, and sustained
 * throughput without resource growth.
 */
@Tag("stress")
class JafarMcpStressTest {

  private McpTransportHarness harness;

  @BeforeEach
  void setUp() throws Exception {
    harness = new McpTransportHarness();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (harness != null) {
      harness.close();
    }
  }

  /**
   * Drives 500 sequential {@code jfr_help} calls through the stdio pipeline. Verifies sustained
   * throughput without thread leaks (transport stdio-inbound/outbound + small slack).
   */
  @Test
  @Tag("stress")
  void survivesManySequentialToolCalls() throws Exception {
    int initialThreadCount = Thread.getAllStackTraces().keySet().size();

    int iterations = 500;
    for (int i = 0; i < iterations; i++) {
      int id = i + 1;
      JsonNode resp = harness.callTool(id, "jfr_help", "{}");
      assertNotNull(resp, "iteration " + i + ": response must arrive within timeout");
      assertTrue(
          resp.has("id") && resp.get("id").asInt() == id,
          "iteration " + i + ": response id must match request id, got " + resp);
      assertTrue(
          !resp.has("error"),
          "iteration " + i + ": response must not be a JSON-RPC error, got " + resp);
    }

    int finalThreadCount = Thread.getAllStackTraces().keySet().size();
    int delta = finalThreadCount - initialThreadCount;
    assertTrue(
        delta <= 10,
        "thread count grew by "
            + delta
            + " threads (initial="
            + initialThreadCount
            + ", final="
            + finalThreadCount
            + "); expected <= 10");
  }

  /**
   * 100 iterations of: trigger a handler error path, then issue a valid call. Without the handler
   * error backstop (Task 1.3) the pipeline would wedge on the first failing call. Both responses
   * must arrive with matching ids.
   */
  @Test
  @Tag("stress")
  void recoversFromHandlerErrors() throws Exception {
    int iterations = 100;
    for (int i = 0; i < iterations; i++) {
      int errorId = (i * 2) + 1;
      int helpId = (i * 2) + 2;

      JsonNode errResp =
          harness.callTool(
              errorId, "jfr_query", "{\"sessionId\":\"does-not-exist\",\"query\":\"events\"}");
      assertNotNull(errResp, "iteration " + i + ": error response must arrive within timeout");
      assertTrue(
          errResp.has("id") && errResp.get("id").asInt() == errorId,
          "iteration " + i + ": error response id must match, got " + errResp);
      // Either a JSON-RPC error member OR a result with isError=true is acceptable.
      boolean hasJsonRpcError = errResp.has("error");
      boolean hasResultError = errResp.at("/result/isError").asBoolean(false);
      assertTrue(
          hasJsonRpcError || hasResultError,
          "iteration " + i + ": expected error response, got " + errResp);

      JsonNode helpResp = harness.callTool(helpId, "jfr_help", "{}");
      assertNotNull(helpResp, "iteration " + i + ": help response must arrive within timeout");
      assertTrue(
          helpResp.has("id") && helpResp.get("id").asInt() == helpId,
          "iteration " + i + ": help response id must match, got " + helpResp);
      assertTrue(
          !helpResp.has("error"),
          "iteration " + i + ": help response must be successful, got " + helpResp);
      assertTrue(
          !helpResp.at("/result/isError").asBoolean(false),
          "iteration " + i + ": help result.isError must be false, got " + helpResp);
    }
  }

  /**
   * 50 iterations of: one malformed inbound line, then one valid {@code jfr_help} call. Validates
   * that malformed inbound traffic does not terminate the inbound flux.
   */
  @Test
  @Tag("stress")
  void handlesInterleavedMalformedAndValidRequests() throws Exception {
    int iterations = 50;
    for (int i = 0; i < iterations; i++) {
      String malformed =
          (i % 2 == 0)
              ? "not-json"
              : "{\"jsonrpc\":\"2.0\",\"method\":\"unknown/method\",\"id\":-" + (i + 1) + "}";
      harness.write(malformed);

      int id = i + 1;
      JsonNode resp;
      try {
        resp = harness.callTool(id, "jfr_help", "{}");
      } catch (Exception e) {
        fail(
            "iteration "
                + i
                + ": valid call after malformed line threw "
                + e.getClass().getName()
                + ": "
                + e.getMessage());
        return;
      }
      assertNotNull(
          resp,
          "iteration "
              + i
              + ": valid call after malformed line must produce response within"
              + " timeout");
      assertTrue(
          resp.has("id") && resp.get("id").asInt() == id,
          "iteration " + i + ": response id must match request id, got " + resp);
      assertTrue(!resp.has("error"), "iteration " + i + ": valid call must succeed, got " + resp);
    }
  }
}
