package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for jfr_flamegraph and jfr_callgraph MCP tools.
 *
 * <p>Tests the aggregation logic using synthetic stack trace data to avoid memory issues with large
 * JFR files.
 */
class JafarMcpServerFlamegraphTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // FlameNode aggregation tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void flameNodeAggregatesPathsCorrectly() throws Exception {
    // Test the FlameNode class directly via reflection
    Class<?> flameNodeClass = getInnerClass("FlameNode");
    Object root = createFlameNode("root");

    Method addPath = flameNodeClass.getDeclaredMethod("addPath", List.class);
    addPath.setAccessible(true);

    // Add paths
    addPath.invoke(root, List.of("A", "B", "C"));
    addPath.invoke(root, List.of("A", "B", "D"));
    addPath.invoke(root, List.of("A", "E"));
    addPath.invoke(root, List.of("A", "B", "C")); // duplicate path

    // Verify root value
    Field valueField = flameNodeClass.getDeclaredField("value");
    valueField.setAccessible(true);
    assertEquals(4L, valueField.get(root));

    // Verify children
    Field childrenField = flameNodeClass.getDeclaredField("children");
    childrenField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> children = (Map<String, Object>) childrenField.get(root);
    assertEquals(1, children.size()); // Only "A" at root level
    assertTrue(children.containsKey("A"));
  }

  @Test
  void flameNodeHandlesEmptyPath() throws Exception {
    Class<?> flameNodeClass = getInnerClass("FlameNode");
    Object root = createFlameNode("root");

    Method addPath = flameNodeClass.getDeclaredMethod("addPath", List.class);
    addPath.setAccessible(true);

    // Add empty path - should just increment value
    addPath.invoke(root, List.of());

    Field valueField = flameNodeClass.getDeclaredField("value");
    valueField.setAccessible(true);
    assertEquals(1L, valueField.get(root));

    Field childrenField = flameNodeClass.getDeclaredField("children");
    childrenField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> children = (Map<String, Object>) childrenField.get(root);
    assertTrue(children.isEmpty());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // CallGraph aggregation tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void callGraphAggregatesEdgesCorrectly() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Object graph = createCallGraph();

    Method addStack = callGraphClass.getDeclaredMethod("addStack", List.class);
    addStack.setAccessible(true);

    // Add stacks: A->B->C and A->B->D
    addStack.invoke(graph, List.of("A", "B", "C"));
    addStack.invoke(graph, List.of("A", "B", "D"));

    // Verify edges
    Field edgesField = callGraphClass.getDeclaredField("edges");
    edgesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Long> edges = (Map<String, Long>) edgesField.get(graph);

    assertEquals(2L, edges.get("A->B")); // A calls B twice
    assertEquals(1L, edges.get("B->C")); // B calls C once
    assertEquals(1L, edges.get("B->D")); // B calls D once
  }

  @Test
  void callGraphTracksConvergencePoints() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Object graph = createCallGraph();

    Method addStack = callGraphClass.getDeclaredMethod("addStack", List.class);
    addStack.setAccessible(true);

    // Multiple callers to same callee: A->C and B->C
    addStack.invoke(graph, List.of("A", "C"));
    addStack.invoke(graph, List.of("B", "C"));

    // Compute inDegree (done after all stacks are added)
    Method computeInDegree = callGraphClass.getDeclaredMethod("computeInDegree");
    computeInDegree.setAccessible(true);
    computeInDegree.invoke(graph);

    // Verify inDegree tracks convergence
    Field inDegreeField = callGraphClass.getDeclaredField("inDegree");
    inDegreeField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Integer> inDegree = (Map<String, Integer>) inDegreeField.get(graph);

    // C has inDegree=2 (called from both A and B)
    assertTrue(inDegree.containsKey("C"));
    assertEquals(2, inDegree.get("C"));
  }

  @Test
  void callGraphHandlesEmptyStack() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Object graph = createCallGraph();

    Method addStack = callGraphClass.getDeclaredMethod("addStack", List.class);
    addStack.setAccessible(true);

    // Add empty stack - should not crash
    addStack.invoke(graph, List.of());

    Field totalSamplesField = callGraphClass.getDeclaredField("totalSamples");
    totalSamplesField.setAccessible(true);
    assertEquals(1L, totalSamplesField.get(graph));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // extractMethodName tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void extractMethodNameFromNestedMap() throws Exception {
    Method extractMethodName =
        JafarMcpServer.class.getDeclaredMethod("extractMethodName", Object.class);
    extractMethodName.setAccessible(true);

    // Create a frame map similar to JFR data structure
    Map<String, Object> nameMap = new LinkedHashMap<>();
    nameMap.put("string", "doSomething");

    Map<String, Object> typeNameMap = new LinkedHashMap<>();
    typeNameMap.put("string", "com.example.MyClass");

    Map<String, Object> typeMap = new LinkedHashMap<>();
    typeMap.put("name", typeNameMap);

    Map<String, Object> methodMap = new LinkedHashMap<>();
    methodMap.put("name", nameMap);
    methodMap.put("type", typeMap);

    Map<String, Object> frameMap = new LinkedHashMap<>();
    frameMap.put("method", methodMap);

    String result = (String) extractMethodName.invoke(server, frameMap);
    assertEquals("com.example.MyClass.doSomething", result);
  }

  @Test
  void extractMethodNameHandlesNull() throws Exception {
    Method extractMethodName =
        JafarMcpServer.class.getDeclaredMethod("extractMethodName", Object.class);
    extractMethodName.setAccessible(true);

    assertNull(extractMethodName.invoke(server, (Object) null));
  }

  @Test
  void extractMethodNameHandlesMissingFields() throws Exception {
    Method extractMethodName =
        JafarMcpServer.class.getDeclaredMethod("extractMethodName", Object.class);
    extractMethodName.setAccessible(true);

    // Frame with no method field
    Map<String, Object> frameMap = new LinkedHashMap<>();
    assertNull(extractMethodName.invoke(server, frameMap));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Format output tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void formatFlamegraphFoldedOutput() throws Exception {
    // Create a FlameNode tree and format it
    Class<?> flameNodeClass = getInnerClass("FlameNode");
    Object root = createFlameNode("root");

    Method addPath = flameNodeClass.getDeclaredMethod("addPath", List.class);
    addPath.setAccessible(true);
    addPath.invoke(root, List.of("A", "B"));
    addPath.invoke(root, List.of("A", "C"));

    Method formatFolded =
        JafarMcpServer.class.getDeclaredMethod("formatFlamegraphFolded", flameNodeClass, int.class);
    formatFolded.setAccessible(true);

    CallToolResult result = (CallToolResult) formatFolded.invoke(server, root, 1);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("folded", node.get("format").asText());
    assertTrue(node.has("totalSamples"));
    assertTrue(node.has("data"));

    String data = node.get("data").asText();
    assertTrue(data.contains("A;B 1"));
    assertTrue(data.contains("A;C 1"));
  }

  @Test
  void formatCallgraphDotOutput() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Object graph = createCallGraph();

    Method addStack = callGraphClass.getDeclaredMethod("addStack", List.class);
    addStack.setAccessible(true);
    addStack.invoke(graph, List.of("A", "B", "C"));

    Method formatDot =
        JafarMcpServer.class.getDeclaredMethod("formatCallgraphDot", callGraphClass, int.class);
    formatDot.setAccessible(true);

    CallToolResult result = (CallToolResult) formatDot.invoke(server, graph, 1);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("dot", node.get("format").asText());
    String data = node.get("data").asText();
    assertTrue(data.startsWith("digraph callgraph {"));
    assertTrue(data.contains("->"));
    assertTrue(data.contains("[label="));
  }

  @Test
  void formatCallgraphJsonOutput() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Object graph = createCallGraph();

    Method addStack = callGraphClass.getDeclaredMethod("addStack", List.class);
    addStack.setAccessible(true);
    addStack.invoke(graph, List.of("A", "B"));

    Method formatJson =
        JafarMcpServer.class.getDeclaredMethod(
            "formatCallgraphJson", callGraphClass, int.class, int.class);
    formatJson.setAccessible(true);

    CallToolResult result = (CallToolResult) formatJson.invoke(server, graph, 1, 1);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("json", node.get("format").asText());
    assertTrue(node.has("nodes"));
    assertTrue(node.has("edges"));

    JsonNode edges = node.get("edges");
    assertTrue(edges.isArray());
    if (edges.size() > 0) {
      JsonNode firstEdge = edges.get(0);
      assertTrue(firstEdge.has("from"));
      assertTrue(firstEdge.has("to"));
      assertTrue(firstEdge.has("weight"));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Parameter validation tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void flamegraphRequiresEventType() throws Exception {
    // Mock the session registry to avoid needing a real JFR file
    Map<String, Object> args = new HashMap<>();
    // No eventType provided

    Method handleJfrFlamegraph =
        JafarMcpServer.class.getDeclaredMethod("handleJfrFlamegraph", Map.class);
    handleJfrFlamegraph.setAccessible(true);

    CallToolResult result = (CallToolResult) handleJfrFlamegraph.invoke(server, args);

    assertTrue(result.isError());
    String json = extractTextContent(result);
    assertTrue(json.contains("eventType is required"));
  }

  @Test
  void callgraphRequiresEventType() throws Exception {
    Map<String, Object> args = new HashMap<>();
    // No eventType provided

    Method handleJfrCallgraph =
        JafarMcpServer.class.getDeclaredMethod("handleJfrCallgraph", Map.class);
    handleJfrCallgraph.setAccessible(true);

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertTrue(result.isError());
    String json = extractTextContent(result);
    assertTrue(json.contains("eventType is required"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private Class<?> getInnerClass(String name) throws ClassNotFoundException {
    for (Class<?> inner : JafarMcpServer.class.getDeclaredClasses()) {
      if (inner.getSimpleName().equals(name)) {
        return inner;
      }
    }
    throw new ClassNotFoundException("Inner class not found: " + name);
  }

  private Object createFlameNode(String name) throws Exception {
    Class<?> flameNodeClass = getInnerClass("FlameNode");
    Constructor<?> ctor = flameNodeClass.getDeclaredConstructor(String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(name);
  }

  private Object createCallGraph() throws Exception {
    Class<?> callGraphClass = getInnerClass("CallGraph");
    Constructor<?> ctor = callGraphClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  private String extractTextContent(CallToolResult result) {
    return result.content().stream()
        .filter(c -> c instanceof TextContent)
        .map(c -> ((TextContent) c).text())
        .findFirst()
        .orElse("");
  }
}
