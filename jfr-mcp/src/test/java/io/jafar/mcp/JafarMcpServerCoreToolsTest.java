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

/** Tests for core MCP tools: jfr_open, jfr_query, jfr_list_types, jfr_close, jfr_help. */
class JafarMcpServerCoreToolsTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @AfterEach
  void tearDown() throws Exception {
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);
    handleJfrClose.invoke(server, args);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_open tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void openSucceedsWithValidPath() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", getComprehensiveJfr());

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("id"));
    assertTrue(node.has("path"));
    assertTrue(node.has("message"));
  }

  @Test
  void openWithAliasCreatesNamedSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", getComprehensiveJfr());
    args.put("alias", "test-session");

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void openFailsWithNullPath() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(extractTextContent(result).contains("Path is required"));
  }

  @Test
  void openFailsWithBlankPath() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", "   ");

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);

    assertTrue(result.isError());
  }

  @Test
  void openFailsWithNonexistentFile() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", "/nonexistent/file.jfr");

    CallToolResult result = (CallToolResult) handleJfrOpen.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(extractTextContent(result).contains("not found"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_query tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void queryExecutesSimpleQuery() throws Exception {
    openSession();

    Method handleJfrQuery = getMethod("handleJfrQuery", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("query", "events/jdk.ExecutionSample | count()");

    CallToolResult result = (CallToolResult) handleJfrQuery.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.isArray());
  }

  @Test
  void queryFailsWithNullQuery() throws Exception {
    openSession();

    Method handleJfrQuery = getMethod("handleJfrQuery", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrQuery.invoke(server, args);

    assertTrue(result.isError());
  }

  @Test
  void queryFailsWithInvalidQuery() throws Exception {
    openSession();

    Method handleJfrQuery = getMethod("handleJfrQuery", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("query", "invalid syntax here");

    CallToolResult result = (CallToolResult) handleJfrQuery.invoke(server, args);

    assertTrue(result.isError());
  }

  @Test
  void queryRespectsLimit() throws Exception {
    openSession();

    Method handleJfrQuery = getMethod("handleJfrQuery", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("query", "events/jdk.ExecutionSample");
    args.put("limit", 5);

    CallToolResult result = (CallToolResult) handleJfrQuery.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.isArray());
    assertTrue(node.size() <= 5);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_list_types tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void listTypesReturnsAvailableTypes() throws Exception {
    openSession();

    Method handleJfrListTypes = getMethod("handleJfrListTypes", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrListTypes.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("eventTypes"));
    assertTrue(node.get("eventTypes").isArray());
    assertTrue(node.get("eventTypes").size() > 0);
  }

  @Test
  void listTypesFiltersByPattern() throws Exception {
    openSession();

    Method handleJfrListTypes = getMethod("handleJfrListTypes", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("filter", "jdk.Execution");

    CallToolResult result = (CallToolResult) handleJfrListTypes.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode types = node.get("eventTypes");
    for (JsonNode type : types) {
      String name = type.get("name").asText();
      assertTrue(name.contains("Execution"));
    }
  }

  @Test
  void listTypesScanGetsCounts() throws Exception {
    openSession();

    Method handleJfrListTypes = getMethod("handleJfrListTypes", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("scan", true);

    CallToolResult result = (CallToolResult) handleJfrListTypes.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode types = node.get("eventTypes");
    if (types.size() > 0) {
      JsonNode firstType = types.get(0);
      assertTrue(firstType.has("count"));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_close tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void closeRemovesSession() throws Exception {
    String sessionId = openSession();

    Method handleJfrClose = getMethod("handleJfrClose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", sessionId);

    CallToolResult result = (CallToolResult) handleJfrClose.invoke(server, args);

    assertFalse(result.isError());

    // Verify session is gone
    Method handleJfrQuery = getMethod("handleJfrQuery", Map.class);
    Map<String, Object> queryArgs = new HashMap<>();
    queryArgs.put("query", "events/jdk.ExecutionSample | count()");
    queryArgs.put("sessionId", sessionId);

    CallToolResult queryResult = (CallToolResult) handleJfrQuery.invoke(server, queryArgs);
    assertTrue(queryResult.isError());
  }

  @Test
  void closeAllRemovesAllSessions() throws Exception {
    openSession();
    openSession();

    Method handleJfrClose = getMethod("handleJfrClose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);

    CallToolResult result = (CallToolResult) handleJfrClose.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void closeFailsForNonexistentSession() throws Exception {
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "nonexistent");

    CallToolResult result = (CallToolResult) handleJfrClose.invoke(server, args);

    assertTrue(result.isError());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_help tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void helpReturnsDocumentation() throws Exception {
    Method handleJfrHelp = getMethod("handleJfrHelp", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrHelp.invoke(server, args);

    assertFalse(result.isError());
    String content = extractTextContent(result);

    assertTrue(content.contains("JfrPath"));
    assertTrue(content.contains("events/"));
  }

  @Test
  void helpReturnsTopicDocumentation() throws Exception {
    Method handleJfrHelp = getMethod("handleJfrHelp", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("topic", "filters");

    CallToolResult result = (CallToolResult) handleJfrHelp.invoke(server, args);

    assertFalse(result.isError());
    String content = extractTextContent(result);

    assertTrue(content.contains("filter"));
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
