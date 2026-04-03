package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for otlp_* MCP handler validation and error handling. */
@UnitTest
class OtlpHandlerTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpOpenRejectsNullPath() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("path", null);
    assertError(invoke("handleOtlpOpen", args), "path is required");
  }

  @Test
  void otlpOpenRejectsBlankPath() throws Exception {
    assertError(invoke("handleOtlpOpen", Map.of("path", "   ")), "path is required");
  }

  @Test
  void otlpOpenRejectsNonexistentFile() throws Exception {
    assertError(
        invoke("handleOtlpOpen", Map.of("path", "/tmp/nonexistent-" + System.nanoTime() + ".otlp")),
        "File not found");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpCloseFailsWithNoOpenSession() throws Exception {
    Map<String, Object> args = new HashMap<>();
    assertError(invoke("handleOtlpClose", args), "otlp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpQueryRejectsNullQuery() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    assertError(invoke("handleOtlpQuery", args), "query is required");
  }

  @Test
  void otlpQueryRejectsBlankQuery() throws Exception {
    assertError(invoke("handleOtlpQuery", Map.of("query", "  ")), "query is required");
  }

  @Test
  void otlpQueryRejectsZeroLimit() throws Exception {
    assertError(
        invoke("handleOtlpQuery", Map.of("query", "samples | count", "limit", 0)),
        "limit must be positive");
  }

  @Test
  void otlpQueryRejectsNegativeLimit() throws Exception {
    assertError(
        invoke("handleOtlpQuery", Map.of("query", "samples | count", "limit", -1)),
        "limit must be positive");
  }

  @Test
  void otlpQueryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtlpQuery", Map.of("query", "samples | count")), "otlp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpSummaryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtlpSummary", Map.of()), "otlp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpFlamegraphFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handleOtlpFlamegraph", Map.of()), "otlp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpUseFailsWithNoOpenSession() throws Exception {
    assertError(invokeWithExchange("handleOtlpUse", Map.of()), "otlp_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpHelpReturnsSyntaxContent() throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod("getOtlpHelpText");
    method.setAccessible(true);
    String content = (String) method.invoke(server);
    assertTrue(content.contains("samples[predicate] | operator(args)"), "syntax line missing");
    assertTrue(content.contains("count()"), "count operator missing");
    assertTrue(content.contains("groupBy(field, sum(f))"), "groupBy sum variant missing");
    assertTrue(content.contains("stackprofile([field])"), "stackprofile operator missing");
    assertTrue(content.contains("otlp_open"), "workflow step missing");
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
