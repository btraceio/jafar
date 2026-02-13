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
 * Integration tests for JafarMcpServer using real JFR files.
 *
 * <p>These tests exercise deep JFR processing logic (flamegraph, callgraph, exception analysis)
 * with real production-size JFR recordings. They are slower than synthetic file tests.
 */
class HandlerLogicIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path realJfrFile;

  private JafarMcpServer server;
  private String sessionId;

  @BeforeAll
  static void findRealJfrFile() {
    // Use the smallest available real JFR file
    // test-ap.jfr is ~171MB (symlink to demo/src/test/resources/test-ap.jfr)
    realJfrFile = Path.of("../demo/src/test/resources/test-ap.jfr").normalize();
    if (!realJfrFile.toFile().exists()) {
      // Try alternative location
      realJfrFile = Path.of("demo/src/test/resources/test-ap.jfr").normalize();
    }
    assertTrue(
        realJfrFile.toFile().exists(),
        "Real JFR file not found at: " + realJfrFile.toAbsolutePath());
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
    openSession();

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
    openSession();

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
    openSession();

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

  // ═══════════════════════════════════════════════════════════════════════════════════════
  // Callgraph Integration Tests (require real stack traces)
  // ═══════════════════════════════════════════════════════════════════════════════════════

  @Test
  void callgraphDotFormat() throws Exception {
    openSession();

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
    JsonNode node = parseResult(result);

    assertTrue(node.has("totalExceptions"));
    // Real JFR file may or may not have exceptions, so just verify structure
    assertTrue(node.has("byType"));
    assertTrue(node.get("byType").isArray());

    // If exceptions exist, verify structure
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
      String typeName = type.asText();
      assertTrue(
          typeName.toLowerCase().contains("execution"), "Type should match filter: " + typeName);
    }
  }
}
