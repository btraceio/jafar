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
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for JafarMcpServer handler logic using realistic JFR test files.
 *
 * <p>Tests the MCP server's business logic with real JFR file processing to achieve high code
 * coverage. Test files are small (< 50KB) and created once per test class for fast execution.
 *
 * <p>Note: Tests that require deep JFR processing (flamegraph details, exception analysis) are
 * marked as integration tests since synthetic JFR files lack the complete metadata needed by the
 * parser. These tests should use real JFR files from test resources.
 */
class HandlerLogicTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir static Path tempDir;

  private static Path executionSampleFile;
  private static Path exceptionFile;
  private static Path comprehensiveFile;

  private JafarMcpServer server;
  private String sessionId;

  @BeforeAll
  static void createTestFiles() throws Exception {
    // Create test JFR files once per test class (shared across tests)
    executionSampleFile = JfrTestFileBuilder.createExecutionSampleFile(20);
    exceptionFile = JfrTestFileBuilder.createExceptionFile(10);
    comprehensiveFile = JfrTestFileBuilder.createComprehensiveFile();
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

    assertEquals("closed", node.get("status").asText());

    // Subsequent operations on closed session should fail
    CallToolResult queryResult = invokeTool("jfr_query", Map.of("query", "events/jdk.ExecutionSample"));
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

  @Test
  void flamegraphBottomUpFoldedFormat() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("direction", "bottom-up");
    args.put("format", "folded");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("folded", node.get("format").asText());
    assertEquals("bottom-up", node.get("direction").asText());
    assertTrue(node.get("data").asText().length() > 0);
    assertTrue(node.get("processedEvents").asInt() > 0);

    // Folded format should contain semicolon-separated stack traces
    String data = node.get("data").asText();
    assertTrue(data.contains(";"), "Folded format should use semicolons");
  }

  @Test
  void flamegraphTopDownDirection() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("direction", "top-down");
    args.put("format", "folded");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("top-down", node.get("direction").asText());
    assertTrue(node.get("processedEvents").asInt() > 0);
  }

  @Test
  void flamegraphTreeFormat() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "tree");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("tree", node.get("format").asText());
    assertTrue(node.has("tree"));
    assertTrue(node.get("tree").isObject());
    assertTrue(node.get("tree").has("name"));
    assertTrue(node.get("tree").has("value"));
  }

  @Test
  void flamegraphMaxDepth() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "tree");
    args.put("maxDepth", 2);

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    // Verify depth is limited
    assertTrue(node.has("tree"));
    int maxDepth = calculateMaxDepth(node.get("tree"));
    assertTrue(maxDepth <= 3, "Max depth should be limited (root + 2)"); // root + maxDepth
  }

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

  @Test
  void callgraphDotFormat() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "dot");

    CallToolResult result = invokeTool("jfr_callgraph", args);
    JsonNode node = parseResult(result);

    assertTrue(node.has("data"));
    String dotData = node.get("data").asText();

    // Verify DOT format structure
    assertTrue(dotData.startsWith("digraph"), "DOT output should start with 'digraph'");
    assertTrue(dotData.contains("->"), "DOT output should contain edges");
    assertTrue(dotData.contains("["), "DOT output should contain attributes");
  }

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

  @Test
  void listTypesWithFilter() throws Exception {
    openSession(comprehensiveFile);

    Map<String, Object> args = new HashMap<>();
    args.put("filter", "Exception");

    CallToolResult result = invokeTool("jfr_list_types", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("eventTypes").size() > 0);

    // Verify all types match filter
    for (JsonNode type : node.get("eventTypes")) {
      String typeName = type.asText();
      assertTrue(
          typeName.toLowerCase().contains("exception"),
          "Type should match filter: " + typeName);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_exceptions tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void exceptionsGroupsByType() throws Exception {
    openSession(exceptionFile);

    CallToolResult result = invokeTool("jfr_exceptions", Map.of());
    JsonNode node = parseResult(result);

    assertTrue(node.has("totalExceptions"));
    assertTrue(node.get("totalExceptions").asInt() > 0);
    assertTrue(node.has("exceptionsByType"));
    assertTrue(node.get("exceptionsByType").isArray());
    assertTrue(node.get("exceptionsByType").size() > 0);

    // Verify exception type structure
    JsonNode firstException = node.get("exceptionsByType").get(0);
    assertTrue(firstException.has("type"));
    assertTrue(firstException.has("count"));
  }

  @Test
  void exceptionsRespectLimit() throws Exception {
    openSession(exceptionFile);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 2);

    CallToolResult result = invokeTool("jfr_exceptions", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("exceptionsByType").size() <= 2);
  }

  @Test
  void exceptionsMinCount() throws Exception {
    openSession(exceptionFile);

    Map<String, Object> args = new HashMap<>();
    args.put("minCount", 2);

    CallToolResult result = invokeTool("jfr_exceptions", args);
    JsonNode node = parseResult(result);

    // Verify all exception types meet minimum count
    for (JsonNode exType : node.get("exceptionsByType")) {
      int count = exType.get("count").asInt();
      assertTrue(count >= 2, "All exception types should meet minimum count");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // jfr_hotmethods tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void hotmethodsIdentifiesTopMethods() throws Exception {
    openSession(executionSampleFile);

    CallToolResult result = invokeTool("jfr_hotmethods", Map.of("limit", 10));
    JsonNode node = parseResult(result);

    assertTrue(node.has("hotMethods"));
    assertTrue(node.get("hotMethods").isArray());
    assertTrue(node.get("hotMethods").size() > 0);

    // Verify method structure
    JsonNode firstMethod = node.get("hotMethods").get(0);
    assertTrue(firstMethod.has("method"));
    assertTrue(firstMethod.has("samples"));

    // Verify methods are sorted by sample count (descending)
    if (node.get("hotMethods").size() > 1) {
      int firstSamples = node.get("hotMethods").get(0).get("samples").asInt();
      int secondSamples = node.get("hotMethods").get(1).get("samples").asInt();
      assertTrue(
          firstSamples >= secondSamples, "Methods should be sorted by sample count descending");
    }
  }

  @Test
  void hotmethodsRespectsLimit() throws Exception {
    openSession(executionSampleFile);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 5);

    CallToolResult result = invokeTool("jfr_hotmethods", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("hotMethods").size() <= 5);
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

  @Test
  void helpWithTopic() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("topic", "filters");

    CallToolResult result = invokeTool("jfr_help", args);

    // Help returns plain text, not JSON
    assertFalse(result.isError());
    String helpText = extractTextContent(result);
    assertTrue(helpText.contains("filter"));
  }
}
