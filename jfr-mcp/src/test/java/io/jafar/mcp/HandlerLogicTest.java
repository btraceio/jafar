package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fast unit tests for JafarMcpServer using minimal synthetic JFR files.
 *
 * <p>Tests MCP server infrastructure (session management, query processing, API validation) with
 * minimal synthetic JFR files (< 50KB) for fast execution (< 1 second).
 *
 * <p>Tests requiring deep JFR processing (flamegraph, callgraph, hotmethods, exception analysis)
 * are in {@link HandlerLogicIntegrationTest}.
 */
class HandlerLogicTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path executionSampleFile;
  private static Path exceptionFile;
  private static Path comprehensiveFile;

  private JafarMcpServer server;
  private String sessionId;

  @BeforeAll
  static void createTestFiles() throws Exception {
    // Use SimpleJfrFileBuilder - minimal valid JFR files for fast unit testing
    executionSampleFile = SimpleJfrFileBuilder.createExecutionSampleFile(20);
    exceptionFile = SimpleJfrFileBuilder.createExceptionFile(10);
    comprehensiveFile = SimpleJfrFileBuilder.createComprehensiveFile();
  }

  @BeforeEach
  void setUp() {
    // Use real dependencies (no mocking!)
    server = new JafarMcpServer();
  }

  @AfterEach
  void tearDown() throws Exception {
    // Close all sessions after each test
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);
    handleJfrClose.invoke(server, args);
  }

  private void openSession(Path jfrFile) throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);
    Map<String, Object> args = Map.of("path", jfrFile.toString());
    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);
    assertFalse(result.isError(), "Failed to open session");

    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);
    sessionId = node.get("id").asText();
  }

  private CallToolResult invokeTool(String toolName, Map<String, Object> args) throws Exception {
    Method method = getMethod(camelCase("handle_" + toolName), Map.class);
    return (CallToolResult) method.invoke(server, args);
  }

  private String camelCase(String snakeCase) {
    StringBuilder result = new StringBuilder();
    boolean capitalizeNext = false;
    for (char c : snakeCase.toCharArray()) {
      if (c == '_') {
        capitalizeNext = true;
      } else {
        result.append(capitalizeNext ? Character.toUpperCase(c) : c);
        capitalizeNext = false;
      }
    }
    return result.toString();
  }

  private Method getMethod(String name, Class<?>... parameterTypes) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method;
  }

  private JsonNode parseResult(CallToolResult result) throws Exception {
    assertFalse(result.isError(), "Tool call failed: " + result);
    String json = extractTextContent(result);
    return MAPPER.readTree(json);
  }

  private String extractTextContent(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_open / jfr_close tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void openCreatesNewSession() throws Exception {
    CallToolResult result = invokeTool("jfr_open", Map.of("path", executionSampleFile.toString()));
    JsonNode node = parseResult(result);

    assertTrue(node.has("id"));
    assertNotNull(node.get("id").asText());
    assertTrue(node.has("path"));
  }

  @Test
  void closeRemovesSession() throws Exception {
    openSession(executionSampleFile);

    CallToolResult result = invokeTool("jfr_close", Map.of("sessionId", sessionId));
    JsonNode node = parseResult(result);

    assertTrue(node.get("success").asBoolean()); // Actual field is "success" not "status"

    // Subsequent operations on closed session should fail
    CallToolResult queryResult =
        invokeTool("jfr_query", Map.of("query", "events/jdk.ExecutionSample"));
    assertTrue(queryResult.isError());
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_query tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void queryExecutesSuccessfully() throws Exception {
    openSession(executionSampleFile);
    String queryString = "events/jdk.ExecutionSample | count()";

    CallToolResult result = invokeTool("jfr_query", Map.of("query", queryString));
    JsonNode node = parseResult(result);

    assertEquals(queryString, node.get("query").asText());
    assertTrue(node.get("resultCount").asInt() > 0);
    assertTrue(node.get("results").isArray());
  }

  @Test
  void queryRespectsLimit() throws Exception {
    openSession(executionSampleFile);
    String queryString = "events/jdk.ExecutionSample";

    Map<String, Object> args = new HashMap<>();
    args.put("query", queryString);
    args.put("limit", 5);

    CallToolResult result = invokeTool("jfr_query", args);
    JsonNode node = parseResult(result);

    assertEquals(5, node.get("resultCount").asInt());
    assertEquals(5, node.get("results").size());
  }

  @Test
  void queryHandlesInvalidSyntax() throws Exception {
    openSession(executionSampleFile);
    String queryString = "invalid ||| syntax";

    CallToolResult result = invokeTool("jfr_query", Map.of("query", queryString));
    assertTrue(result.isError());
  }

  @Test
  void queryRequiresOpenSession() throws Exception {
    // Don't open session
    CallToolResult result = invokeTool("jfr_query", Map.of("query", "events/jdk.ExecutionSample"));
    assertTrue(result.isError());
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_flamegraph tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  // Flamegraph tests requiring real stack traces moved to HandlerLogicIntegrationTest

  @Test
  void flamegraphMinSamples() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "folded");
    args.put("minSamples", 5);

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    // Verify only high-sample stacks are included
    String data = node.get("data").asText();
    if (!data.isEmpty()) {
      String[] lines = data.split("\n");
      for (String line : lines) {
        if (!line.trim().isEmpty()) {
          String[] parts = line.split(" ");
          int samples = Integer.parseInt(parts[parts.length - 1]);
          assertTrue(samples >= 5, "All samples should meet minimum threshold");
        }
      }
    }
  }

  private int calculateMaxDepth(JsonNode tree) {
    if (!tree.has("children") || tree.get("children").isEmpty()) {
      return 1;
    }
    int maxChildDepth = 0;
    for (JsonNode child : tree.get("children")) {
      maxChildDepth = Math.max(maxChildDepth, calculateMaxDepth(child));
    }
    return 1 + maxChildDepth;
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_callgraph tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void callgraphJsonFormat() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "json");

    CallToolResult result = invokeTool("jfr_callgraph", args);
    JsonNode node = parseResult(result);

    assertTrue(node.has("nodes"));
    assertTrue(node.has("edges"));
    assertTrue(node.get("nodes").isArray());
    assertTrue(node.get("edges").isArray());

    // Verify node structure
    if (node.get("nodes").size() > 0) {
      JsonNode firstNode = node.get("nodes").get(0);
      assertTrue(firstNode.has("id"));
      assertTrue(firstNode.has("label"));
      assertTrue(firstNode.has("samples"));
    }

    // Verify edge structure
    if (node.get("edges").size() > 0) {
      JsonNode firstEdge = node.get("edges").get(0);
      assertTrue(firstEdge.has("from"));
      assertTrue(firstEdge.has("to"));
      assertTrue(firstEdge.has("weight"));
    }
  }

  // Callgraph tests requiring real stack traces moved to HandlerLogicIntegrationTest

  @Test
  void callgraphMinWeight() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "json");
    args.put("minWeight", 3);

    CallToolResult result = invokeTool("jfr_callgraph", args);
    JsonNode node = parseResult(result);

    // Verify all edges meet minimum weight
    for (JsonNode edge : node.get("edges")) {
      int weight = edge.get("weight").asInt();
      assertTrue(weight >= 3, "All edges should meet minimum weight threshold");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_list_types tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void listTypesReturnsAvailableTypes() throws Exception {
    openSession(comprehensiveFile);

    CallToolResult result = invokeTool("jfr_list_types", Map.of());
    JsonNode node = parseResult(result);

    assertTrue(node.has("eventTypes"));
    assertTrue(node.get("eventTypes").isArray());
    assertTrue(node.get("eventTypes").size() > 0);

    // Verify expected types are present
    String eventTypesJson = node.get("eventTypes").toString();
    assertTrue(eventTypesJson.contains("jdk.ExecutionSample"));
    assertTrue(eventTypesJson.contains("jdk.JavaExceptionThrow"));
  }

  // Type filtering tests moved to HandlerLogicIntegrationTest

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_exceptions tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  // Exception analysis tests requiring real exception data moved to HandlerLogicIntegrationTest

  @Test
  void exceptionsRespectLimit() throws Exception {
    openSession(exceptionFile);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 2);

    CallToolResult result = invokeTool("jfr_exceptions", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("byType").size() <= 2); // Actual field name is "byType"
  }

  @Test
  void exceptionsMinCount() throws Exception {
    openSession(exceptionFile);

    Map<String, Object> args = new HashMap<>();
    args.put("minCount", 2);

    CallToolResult result = invokeTool("jfr_exceptions", args);
    JsonNode node = parseResult(result);

    // Verify all exception types meet minimum count
    for (JsonNode exType : node.get("byType")) { // Actual field name is "byType"
      int count = exType.get("count").asInt();
      assertTrue(count >= 2, "All exception types should meet minimum count");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_hotmethods tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  // Hotmethods tests requiring real stack traces moved to HandlerLogicIntegrationTest

  @Test
  void hotmethodsRespectsLimit() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 5);

    CallToolResult result = invokeTool("jfr_hotmethods", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("methods").size() <= 5); // Actual field name is "methods"
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_summary tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void summaryProvidesRecordingOverview() throws Exception {
    openSession(comprehensiveFile);

    CallToolResult result = invokeTool("jfr_summary", Map.of());
    JsonNode node = parseResult(result);

    // Verify summary structure matches actual implementation
    assertTrue(node.has("recordingPath"));
    assertTrue(node.has("sessionId"));
    assertTrue(node.has("totalEvents"));
    assertTrue(node.has("totalEventTypes"));
    assertTrue(node.get("totalEventTypes").asInt() > 0);
    assertTrue(node.has("topEventTypes"));
    assertTrue(node.has("highlights"));
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_help tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void helpReturnsDocumentation() throws Exception {
    CallToolResult result = invokeTool("jfr_help", Map.of());

    // Help returns plain text, not JSON
    assertFalse(result.isError());
    String helpText = extractTextContent(result);
    assertTrue(helpText.length() > 100);
    assertTrue(helpText.contains("JfrPath"));
  }

  // helpWithTopic test removed - help content validation doesn't need deep JFR processing
}
