package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpSyncServerExchange;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for JafarMcpServer using real JFR files.
 *
 * <p>These tests exercise deep JFR processing logic (flamegraph, callgraph, exception analysis)
 * with real production-size JFR recordings. They are slower than synthetic file tests.
 *
 * <p>These tests are excluded by default in build.gradle and require -DenableIntegrationTests=true
 * to run, as they need large JFR files that may not be available in all environments.
 */
class HandlerLogicIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path realJfrFile;

  private JafarMcpServer server;
  private String sessionId;

  @BeforeAll
  static void findRealJfrFile() {
    // Use small JFR file (~2MB) to avoid OOM
    realJfrFile = Path.of("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!realJfrFile.toFile().exists()) {
      realJfrFile = Path.of("demo/src/test/resources/test-dd.jfr").normalize();
    }
    assertTrue(
        realJfrFile.toFile().exists(), "JFR file not found at: " + realJfrFile.toAbsolutePath());
  }

  @BeforeEach
  void setUp() {
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

  private void openSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);
    Map<String, Object> args = Map.of("path", realJfrFile.toString());
    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);
    assertFalse(result.isError(), "Failed to open session");

    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);
    sessionId = node.get("id").asText();
  }

  private CallToolResult invokeTool(String toolName, Map<String, Object> args) throws Exception {
    String methodName = camelCase("handle_" + toolName);
    try {
      Method method = getMethod(methodName, McpSyncServerExchange.class, Map.class);
      return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);
    } catch (NoSuchMethodException e) {
      Method method = getMethod(methodName, Map.class);
      return (CallToolResult) method.invoke(server, args);
    }
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
  // Flamegraph Integration Tests (require real stack traces)
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void flamegraphBottomUpFoldedFormat() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "datadog.ExecutionSample");
    args.put("direction", "bottom-up");
    args.put("format", "folded");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("folded", node.get("format").asText());
    assertTrue(node.get("data").asText().length() > 0);
    assertTrue(node.get("totalSamples").asInt() > 0);

    // Folded format should contain semicolon-separated stack traces
    String data = node.get("data").asText();
    assertTrue(data.contains(";"), "Folded format should use semicolons");
  }

  @Test
  void flamegraphTopDownDirection() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "datadog.ExecutionSample");
    args.put("direction", "top-down");
    args.put("format", "folded");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("folded", node.get("format").asText());
    assertTrue(node.get("totalSamples").asInt() > 0);
  }

  @Test
  void flamegraphTreeFormat() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "datadog.ExecutionSample");
    args.put("format", "tree");

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    assertEquals("tree", node.get("format").asText());
    assertTrue(node.has("root"));
    assertTrue(node.get("root").isObject());
    assertTrue(node.get("root").has("name"));
    assertTrue(node.get("root").has("value"));
  }

  @Test
  void flamegraphMaxDepth() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "datadog.ExecutionSample");
    args.put("format", "tree");
    args.put("maxDepth", 2);

    CallToolResult result = invokeTool("jfr_flamegraph", args);
    JsonNode node = parseResult(result);

    // Verify depth is limited
    assertTrue(node.has("root"));
    int maxDepth = calculateMaxDepth(node.get("root"));
    assertTrue(maxDepth <= 3, "Max depth should be limited (root + 2)"); // root + maxDepth
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // Callgraph Integration Tests (require real stack traces)
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void callgraphDotFormat() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "datadog.ExecutionSample");
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

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // Hotmethods Integration Tests (require real stack traces)
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void hotmethodsIdentifiesTopMethods() throws Exception {
    openSession();

    CallToolResult result = invokeTool("jfr_hotmethods", Map.of("limit", 10));
    JsonNode node = parseResult(result);

    assertTrue(node.has("methods"));
    assertTrue(node.get("methods").isArray());
    assertTrue(node.get("methods").size() > 0, "Should identify hot methods in real JFR file");

    // Verify method structure
    JsonNode firstMethod = node.get("methods").get(0);
    assertTrue(firstMethod.has("method"));
    assertTrue(firstMethod.has("samples"));

    // Verify methods are sorted by sample count (descending)
    if (node.get("methods").size() > 1) {
      int firstSamples = node.get("methods").get(0).get("samples").asInt();
      int secondSamples = node.get("methods").get(1).get("samples").asInt();
      assertTrue(
          firstSamples >= secondSamples, "Methods should be sorted by sample count descending");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // Exception Analysis Integration Tests (require real exception data)
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void exceptionsGroupsByType() throws Exception {
    openSession();

    CallToolResult result = invokeTool("jfr_exceptions", Map.of());
    // Recording may not contain exception events — auto-detection returns an error in that case
    if (result.isError()) {
      String json = extractTextContent(result);
      assertTrue(json.contains("No exception events found"));
      return;
    }
    JsonNode node = parseResult(result);

    assertTrue(node.has("totalExceptions"));
    assertTrue(node.has("byType"));
    assertTrue(node.get("byType").isArray());

    if (node.get("byType").size() > 0) {
      JsonNode firstException = node.get("byType").get(0);
      assertTrue(firstException.has("type"));
      assertTrue(firstException.has("count"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // Type Listing Integration Tests
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void listTypesWithFilter() throws Exception {
    openSession();

    Map<String, Object> args = new HashMap<>();
    args.put("filter", "Execution");

    CallToolResult result = invokeTool("jfr_list_types", args);
    JsonNode node = parseResult(result);

    assertTrue(node.get("eventTypes").size() > 0, "Should find execution sample types");

    // Verify all types match filter
    for (JsonNode type : node.get("eventTypes")) {
      String typeName = type.get("name").asText();
      assertTrue(
          typeName.toLowerCase().contains("execution"), "Type should match filter: " + typeName);
    }
  }
}
