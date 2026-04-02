package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for otelp_* MCP handler validation and error handling. */
@UnitTest
class OtelpHandlerTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpOpenRejectsNullPath() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("path", null);
    assertError(invoke("handleOtelpOpen", args), "path is required");
  }

  @Test
  void otelpOpenRejectsBlankPath() throws Exception {
    assertError(invoke("handleOtelpOpen", Map.of("path", "   ")), "path is required");
  }

  @Test
  void otelpOpenRejectsNonexistentFile() throws Exception {
    assertError(
        invoke(
            "handleOtelpOpen", Map.of("path", "/tmp/nonexistent-" + System.nanoTime() + ".otlp")),
        "File not found");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpCloseFailsWithNoOpenSession() throws Exception {
    Map<String, Object> args = new HashMap<>();
    assertError(invoke("handleOtelpClose", args), "otelp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpQueryRejectsNullQuery() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    assertError(invoke("handleOtelpQuery", args), "query is required");
  }

  @Test
  void otelpQueryRejectsBlankQuery() throws Exception {
    assertError(invoke("handleOtelpQuery", Map.of("query", "  ")), "query is required");
  }

  @Test
  void otelpQueryRejectsZeroLimit() throws Exception {
    assertError(
        invoke("handleOtelpQuery", Map.of("query", "samples | count", "limit", 0)),
        "limit must be positive");
  }

  @Test
  void otelpQueryRejectsNegativeLimit() throws Exception {
    assertError(
        invoke("handleOtelpQuery", Map.of("query", "samples | count", "limit", -1)),
        "limit must be positive");
  }

  @Test
  void otelpQueryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtelpQuery", Map.of("query", "samples | count")), "otelp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpSummaryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtelpSummary", Map.of()), "otelp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpFlamegraphFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtelpFlamegraph", Map.of()), "otelp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpUseFailsWithNoOpenSession() throws Exception {
    assertError(invokeWithExchange("handleOtelpUse", Map.of()), "otelp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otelp_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otelpHelpReturnsSyntaxContent() throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod("getOtelpHelpText");
    method.setAccessible(true);
    String content = (String) method.invoke(server);
    assertTrue(content.contains("samples[predicate] | operator(args)"), "syntax line missing");
    assertTrue(content.contains("count()"), "count operator missing");
    assertTrue(content.contains("groupBy(field, sum(f))"), "groupBy sum variant missing");
    assertTrue(content.contains("stackprofile([field])"), "stackprofile operator missing");
    assertTrue(content.contains("otelp_open"), "workflow step missing");
    assertTrue(content.contains("stackTrace"), "stackTrace field missing");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private CallToolResult invoke(String methodName, Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(methodName, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, args);
  }

  private CallToolResult invokeWithExchange(String methodName, Map<String, Object> args)
      throws Exception {
    Method method =
        JafarMcpServer.class.getDeclaredMethod(methodName, McpSyncServerExchange.class, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);
  }

  private void assertError(CallToolResult result, String expectedFragment) {
    assertTrue(result.isError(), "Expected error result");
    String content = result.content().get(0).toString();
    assertTrue(
        content.contains(expectedFragment), "Expected '" + expectedFragment + "' in: " + content);
  }
}
