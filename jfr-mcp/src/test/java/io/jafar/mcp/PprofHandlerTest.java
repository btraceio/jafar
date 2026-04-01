package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for pprof_* MCP handler validation and error handling. */
@UnitTest
class PprofHandlerTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofOpenRejectsNullPath() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("path", null);
    assertError(invoke("handlePprofOpen", args), "path is required");
  }

  @Test
  void pprofOpenRejectsBlankPath() throws Exception {
    assertError(invoke("handlePprofOpen", Map.of("path", "   ")), "path is required");
  }

  @Test
  void pprofOpenRejectsNonexistentFile() throws Exception {
    assertError(
        invoke(
            "handlePprofOpen", Map.of("path", "/tmp/nonexistent-" + System.nanoTime() + ".pb.gz")),
        "File not found");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofCloseFailsWithNoOpenSession() throws Exception {
    Map<String, Object> args = new HashMap<>();
    assertError(invoke("handlePprofClose", args), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofQueryRejectsNullQuery() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    assertError(invoke("handlePprofQuery", args), "query is required");
  }

  @Test
  void pprofQueryRejectsBlankQuery() throws Exception {
    assertError(invoke("handlePprofQuery", Map.of("query", "  ")), "query is required");
  }

  @Test
  void pprofQueryRejectsZeroLimit() throws Exception {
    assertError(
        invoke("handlePprofQuery", Map.of("query", "samples | count", "limit", 0)),
        "limit must be positive");
  }

  @Test
  void pprofQueryRejectsNegativeLimit() throws Exception {
    assertError(
        invoke("handlePprofQuery", Map.of("query", "samples | count", "limit", -1)),
        "limit must be positive");
  }

  @Test
  void pprofQueryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handlePprofQuery", Map.of("query", "samples | count")), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofSummaryFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handlePprofSummary", Map.of()), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofFlamegraphFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handlePprofFlamegraph", Map.of()), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofUseFailsWithNoOpenSession() throws Exception {
    assertError(invokeWithExchange("handlePprofUse", Map.of()), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofHotmethodsFailsWithNoOpenSession() throws Exception {
    assertError(invoke("handlePprofHotmethods", Map.of()), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_tsa
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofTsaFailsWithNoOpenSession() throws Exception {
    assertError(invokeWithExchange("handlePprofTsa", Map.of()), "pprof_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofHelpReturnsSyntaxContent() throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod("getPprofHelpText");
    method.setAccessible(true);
    String content = (String) method.invoke(server);
    assertTrue(content.contains("samples"));
    assertTrue(content.contains("pprof_open"));
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
