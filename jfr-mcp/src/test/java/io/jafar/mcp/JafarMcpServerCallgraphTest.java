package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for jfr_callgraph MCP tool. */

class JafarMcpServerCallgraphTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    openSession();
  }

  @AfterEach
  void tearDown() throws Exception {
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);
    handleJfrClose.invoke(server, args);
  }

  @Test
  void callgraphGeneratesDotFormat() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "dot");

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("dot", node.get("format").asText());
    assertTrue(node.has("data"));

    String dotData = node.get("data").asText();
    assertTrue(dotData.contains("digraph callgraph"));
  }

  @Test
  void callgraphGeneratesJsonFormat() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "json");

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("json", node.get("format").asText());
    assertTrue(node.has("nodes"));
    assertTrue(node.has("edges"));

    JsonNode nodes = node.get("nodes");
    JsonNode edges = node.get("edges");

    assertTrue(nodes.isArray());
    assertTrue(edges.isArray());
  }

  @Test
  void callgraphIncludesConvergencePoints() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "json");

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode nodes = node.get("nodes");

    // Check if any nodes have inDegree > 1 (convergence points)
    boolean hasConvergence = false;
    for (JsonNode n : nodes) {
      if (n.has("inDegree") && n.get("inDegree").asInt() > 1) {
        hasConvergence = true;
        break;
      }
    }

    // For a real JFR with execution samples, we should have some convergence
    // This assertion might fail for empty recordings
    if (nodes.size() > 10) {
      assertTrue(hasConvergence, "Expected convergence points in call graph");
    }
  }

  @Test
  void callgraphRespectsMinWeight() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("format", "json");
    args.put("minWeight", 100);

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode edges = node.get("edges");

    // All edges should have weight >= minWeight
    for (JsonNode edge : edges) {
      int weight = edge.get("weight").asInt();
      assertTrue(weight >= 100);
    }
  }

  @Test
  void callgraphFailsWithInvalidMinWeight() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.ExecutionSample");
    args.put("minWeight", 0);

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(extractTextContent(result).contains("must be >= 1"));
  }

  @Test
  void callgraphFailsWithoutEventType() throws Exception {
    Method handleJfrCallgraph = getMethod("handleJfrCallgraph", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrCallgraph.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(extractTextContent(result).contains("eventType is required"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private void openSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", getComprehensiveJfr());

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);
    assertFalse(result.isError());
  }

  private Method getMethod(String name, Class<?>... parameterTypes) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method;
  }

  private String extractTextContent(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
