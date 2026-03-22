package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for hdump_* MCP handler validation and error handling. */
class HdumpHandlerTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpOpenRejectsNullPath() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("path", null);
    var result = invoke("handleHdumpOpen", args);
    assertError(result, "path is required");
  }

  @Test
  void hdumpOpenRejectsBlankPath() throws Exception {
    var result = invoke("handleHdumpOpen", Map.of("path", "   "));
    assertError(result, "path is required");
  }

  @Test
  void hdumpOpenRejectsNonexistentFile() throws Exception {
    var result =
        invoke(
            "handleHdumpOpen", Map.of("path", "/tmp/nonexistent-" + System.nanoTime() + ".hprof"));
    assertError(result, "File not found");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpQueryRejectsNullQuery() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    var result = invoke("handleHdumpQuery", args);
    assertError(result, "query is required");
  }

  @Test
  void hdumpQueryRejectsBlankQuery() throws Exception {
    var result = invoke("handleHdumpQuery", Map.of("query", "  "));
    assertError(result, "query is required");
  }

  @Test
  void hdumpQueryRejectsZeroLimit() throws Exception {
    var result = invoke("handleHdumpQuery", Map.of("query", "objects | count", "limit", 0));
    assertError(result, "limit must be positive");
  }

  @Test
  void hdumpQueryRejectsNegativeLimit() throws Exception {
    var result = invoke("handleHdumpQuery", Map.of("query", "objects | count", "limit", -5));
    assertError(result, "limit must be positive");
  }

  @Test
  void hdumpQueryFailsWithNoOpenSession() throws Exception {
    var result = invoke("handleHdumpQuery", Map.of("query", "objects | count"));
    assertError(result, "hdump_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpSummaryFailsWithNoOpenSession() throws Exception {
    var result = invoke("handleHdumpSummary", Map.of());
    assertError(result, "hdump_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_report
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpReportFailsWithNoOpenSession() throws Exception {
    var result = invoke("handleHdumpReport", Map.of());
    assertError(result, "hdump_open");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpCloseFailsWithNoOpenSession() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", false);
    var result = invoke("handleHdumpClose", args);
    assertError(result, "hdump_open");
  }

  @Test
  void hdumpCloseAllSucceedsWhenEmpty() throws Exception {
    var result = invoke("handleHdumpClose", Map.of("closeAll", true));
    assertFalse(result.isError());
    String content = result.content().get(0).toString();
    assertTrue(content.contains("0"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpHelpReturnsOverviewByDefault() throws Exception {
    Map<String, Object> args = new HashMap<>();
    var result = invoke("handleHdumpHelp", args);
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("HdumpPath"));
  }

  @Test
  void hdumpHelpReturnsRootsTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "roots"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("objects"));
  }

  @Test
  void hdumpHelpReturnsFiltersTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "filters"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("predicate"));
  }

  @Test
  void hdumpHelpReturnsOperatorsTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "operators"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("top("));
  }

  @Test
  void hdumpHelpReturnsExamplesTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "examples"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("retained"));
  }

  @Test
  void hdumpHelpReturnsPatternsTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "patterns"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("Workflow"));
  }

  @Test
  void hdumpHelpReturnsToolsTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "tools"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("hdump_open"));
  }

  @Test
  void hdumpHelpHandlesUnknownTopic() throws Exception {
    var result = invoke("handleHdumpHelp", Map.of("topic", "bogus"));
    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("Unknown topic"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private CallToolResult invoke(String methodName, Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(methodName, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, args);
  }

  private void assertError(CallToolResult result, String expectedFragment) {
    assertTrue(result.isError(), "Expected error result");
    String content = result.content().get(0).toString();
    assertTrue(
        content.contains(expectedFragment), "Expected '" + expectedFragment + "' in: " + content);
  }
}
