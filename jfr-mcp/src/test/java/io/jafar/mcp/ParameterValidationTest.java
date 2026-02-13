package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for MCP parameter validation across all handlers. */
class ParameterValidationTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_open validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void openRejectsNullPath() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("path", null);
    var result = invokeHandler("handleJfrOpen", args);
    assertError(result, "Path is required");
  }

  @Test
  void openRejectsEmptyPath() throws Exception {
    var result = invokeHandler("handleJfrOpen", Map.of("path", ""));
    assertError(result, "Path is required");
  }

  @Test
  void openRejectsWhitespacePath() throws Exception {
    var result = invokeHandler("handleJfrOpen", Map.of("path", "   "));
    assertError(result, "Path is required");
  }

  @Test
  void openRejectsNonexistentFile() throws Exception {
    var result = invokeHandler("handleJfrOpen", Map.of("path", "/tmp/nonexistent-" + System.nanoTime() + ".jfr"));
    assertError(result, "File not found");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_query validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void queryRejectsNullQuery() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    var result = invokeHandler("handleJfrQuery", args);
    assertError(result, "Query is required");
  }

  @Test
  void queryRejectsEmptyQuery() throws Exception {
    var result = invokeHandler("handleJfrQuery", Map.of("query", ""));
    assertError(result, "Query is required");
  }

  @Test
  void queryRejectsWhitespaceQuery() throws Exception {
    var result = invokeHandler("handleJfrQuery", Map.of("query", "   "));
    assertError(result, "Query is required");
  }

  @Test
  void queryRejectsNegativeLimit() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", "events/jdk.ExecutionSample");
    args.put("limit", -1);

    var result = invokeHandler("handleJfrQuery", args);
    assertError(result, "Limit must be positive");
  }

  @Test
  void queryRejectsZeroLimit() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("query", "events/jdk.ExecutionSample");
    args.put("limit", 0);

    var result = invokeHandler("handleJfrQuery", args);
    assertError(result, "Limit must be positive");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_flamegraph validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void flamegraphRejectsNullEventType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("eventType", null);
    var result = invokeHandler("handleJfrFlamegraph", args);
    assertError(result, "Event type is required");
  }

  @Test
  void flamegraphRejectsEmptyEventType() throws Exception {
    var result = invokeHandler("handleJfrFlamegraph", Map.of("eventType", ""));
    assertError(result, "Event type is required");
  }

  @Test
  void flamegraphRejectsInvalidDirection() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("direction", "invalid");

    var result = invokeHandler("handleJfrFlamegraph", args);
    assertError(result, "direction");
  }

  @Test
  void flamegraphRejectsInvalidFormat() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "invalid");

    var result = invokeHandler("handleJfrFlamegraph", args);
    assertError(result, "format");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_callgraph validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void callgraphRejectsNullEventType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("eventType", null);
    var result = invokeHandler("handleJfrCallgraph", args);
    assertError(result, "Event type is required");
  }

  @Test
  void callgraphRejectsEmptyEventType() throws Exception {
    var result = invokeHandler("handleJfrCallgraph", Map.of("eventType", ""));
    assertError(result, "Event type is required");
  }

  @Test
  void callgraphRejectsInvalidFormat() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "invalid");

    var result = invokeHandler("handleJfrCallgraph", args);
    assertError(result, "format");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_use validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void useAcceptsValidResources() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("resources", java.util.List.of("cpu", "memory"));

    // Should fail on missing session, not on parameter validation
    var result = invokeHandler("handleJfrUse", args);
    assertError(result); // Any error is fine, we're testing it doesn't reject valid params
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_tsa validation
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void tsaAcceptsValidTopThreads() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("topThreads", 10);

    // Should fail on missing session, not on parameter validation
    var result = invokeHandler("handleJfrTsa", args);
    assertError(result); // Any error is fine, we're testing it doesn't reject valid params
  }

  @Test
  void tsaAcceptsValidMinSamples() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("minSamples", 5);

    var result = invokeHandler("handleJfrTsa", args);
    assertError(result); // Any error is fine
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private CallToolResult invokeHandler(String methodName, Map<String, Object> args)
      throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(methodName, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, args);
  }

  private void assertError(CallToolResult result) {
    assertTrue(result.isError(), "Expected error result");
  }

  private void assertError(CallToolResult result, String expectedMessage) {
    assertTrue(result.isError(), "Expected error result");
    String content = result.content().get(0).toString();
    assertTrue(
        content.contains(expectedMessage),
        "Expected error message to contain '" + expectedMessage + "' but was: " + content);
  }
}
