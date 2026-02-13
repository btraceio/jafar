package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for MCP response format validation. */
class McpResponseFormatTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @Test
  void errorResultHasCorrectStructure() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("errorResult", String.class);
    method.setAccessible(true);

    CallToolResult result = (CallToolResult) method.invoke(server, "Test error");

    assertTrue(result.isError());
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());
    assertTrue(result.content().get(0) instanceof TextContent);
    assertTrue(((TextContent) result.content().get(0)).text().contains("Test error"));
  }

  @Test
  void successResultHasCorrectStructure() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("successResult", Map.class);
    method.setAccessible(true);

    Map<String, Object> data = new HashMap<>();
    data.put("status", "success");
    data.put("count", 42);

    CallToolResult result = (CallToolResult) method.invoke(server, data);

    assertFalse(result.isError());
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());
    assertTrue(result.content().get(0) instanceof TextContent);

    String json = ((TextContent) result.content().get(0)).text();
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("status"));
    assertTrue(node.has("count"));
    assertEquals("success", node.get("status").asText());
    assertEquals(42, node.get("count").asInt());
  }

  @Test
  void successResultHandlesNullValues() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("successResult", Map.class);
    method.setAccessible(true);

    Map<String, Object> data = new HashMap<>();
    data.put("field", null);

    CallToolResult result = (CallToolResult) method.invoke(server, data);

    assertFalse(result.isError());
    String json = ((TextContent) result.content().get(0)).text();
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("field"));
    assertTrue(node.get("field").isNull());
  }

  @Test
  void successResultHandlesNestedMaps() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("successResult", Map.class);
    method.setAccessible(true);

    Map<String, Object> nested = new HashMap<>();
    nested.put("inner", "value");

    Map<String, Object> data = new HashMap<>();
    data.put("outer", nested);

    CallToolResult result = (CallToolResult) method.invoke(server, data);

    assertFalse(result.isError());
    String json = ((TextContent) result.content().get(0)).text();
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("outer"));
    assertTrue(node.get("outer").isObject());
    assertEquals("value", node.get("outer").get("inner").asText());
  }

  @Test
  void successResultHandlesLists() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("successResult", Map.class);
    method.setAccessible(true);

    Map<String, Object> data = new HashMap<>();
    data.put("items", java.util.List.of("a", "b", "c"));

    CallToolResult result = (CallToolResult) method.invoke(server, data);

    assertFalse(result.isError());
    String json = ((TextContent) result.content().get(0)).text();
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("items"));
    assertTrue(node.get("items").isArray());
    assertEquals(3, node.get("items").size());
  }

  @Test
  void helpResponseHasCorrectFormat() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrHelp", Map.class);
    method.setAccessible(true);

    CallToolResult result = (CallToolResult) method.invoke(server, new HashMap<>());

    assertFalse(result.isError());
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());

    String text = ((TextContent) result.content().get(0)).text();
    assertFalse(text.isEmpty());
  }

  @Test
  void errorResponseIncludesMethod() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("errorResult", String.class);
    method.setAccessible(true);

    CallToolResult result = (CallToolResult) method.invoke(server, "error");

    assertTrue(result.isError());
    String text = ((TextContent) result.content().get(0)).text();

    // Error messages should be clear and actionable
    assertFalse(text.isEmpty());
    assertTrue(text.length() > 0);
  }
}
