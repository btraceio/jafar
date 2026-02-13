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

/** Tests for jfr_summary MCP tool. */
class JafarMcpServerSummaryTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;
  private String sessionId;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    sessionId = openSession();
  }

  @AfterEach
  void tearDown() throws Exception {
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);
    handleJfrClose.invoke(server, args);
  }

  @Test
  void summaryReturnsRecordingMetadata() throws Exception {
    Method handleJfrSummary = getMethod("handleJfrSummary", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrSummary.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("recordingPath"));
    assertTrue(node.has("sessionId"));
    assertTrue(node.has("totalEvents"));
    assertTrue(node.has("totalEventTypes"));
  }

  @Test
  void summaryIncludesTopEventTypes() throws Exception {
    Method handleJfrSummary = getMethod("handleJfrSummary", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrSummary.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("topEventTypes"));
    JsonNode topTypes = node.get("topEventTypes");
    assertTrue(topTypes.isArray());

    if (topTypes.size() > 0) {
      JsonNode firstType = topTypes.get(0);
      assertTrue(firstType.has("type"));
      assertTrue(firstType.has("count"));
      assertTrue(firstType.has("pct"));
    }
  }

  @Test
  void summaryIncludesHighlights() throws Exception {
    Method handleJfrSummary = getMethod("handleJfrSummary", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrSummary.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("highlights"));
    JsonNode highlights = node.get("highlights");

    // At least one of these should be present
    assertTrue(highlights.has("gc") || highlights.has("exceptions") || highlights.has("cpu"));
  }

  @Test
  void summaryWorksWithSpecificSession() throws Exception {
    Method handleJfrSummary = getMethod("handleJfrSummary", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", sessionId);

    CallToolResult result = (CallToolResult) handleJfrSummary.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void summaryFailsForNonexistentSession() throws Exception {
    Method handleJfrSummary = getMethod("handleJfrSummary", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "nonexistent");

    CallToolResult result = (CallToolResult) handleJfrSummary.invoke(server, args);

    assertTrue(result.isError());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private String openSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", getComprehensiveJfr());

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);
    assertFalse(result.isError());

    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);
    return node.get("id").asText();
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
