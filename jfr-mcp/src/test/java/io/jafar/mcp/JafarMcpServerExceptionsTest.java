package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for jfr_exceptions MCP tool. */
class JafarMcpServerExceptionsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEST_DD_JFR = System.getProperty("user.dir") + "/../parser/src/test/resources/test-dd.jfr";

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    openSession(TEST_DD_JFR);
  }

  @Test
  void exceptionsAnalyzesExceptionPatterns() throws Exception {
    Method handleJfrExceptions = getMethod("handleJfrExceptions", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrExceptions.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("eventType"));
    assertTrue(node.has("totalExceptions"));
  }

  @Test
  void exceptionsIncludesExceptionTypes() throws Exception {
    Method handleJfrExceptions = getMethod("handleJfrExceptions", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrExceptions.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    if (node.get("totalExceptions").asInt() > 0) {
      assertTrue(node.has("exceptionTypes"));
      JsonNode types = node.get("exceptionTypes");
      assertTrue(types.isArray());
    }
  }

  @Test
  void exceptionsRespectsLimit() throws Exception {
    Method handleJfrExceptions = getMethod("handleJfrExceptions", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 5);

    CallToolResult result = (CallToolResult) handleJfrExceptions.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    if (node.has("exceptionTypes")) {
      JsonNode types = node.get("exceptionTypes");
      assertTrue(types.size() <= 5);
    }
  }

  @Test
  void exceptionsRespectsMinCount() throws Exception {
    Method handleJfrExceptions = getMethod("handleJfrExceptions", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("minCount", 10);

    CallToolResult result = (CallToolResult) handleJfrExceptions.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    if (node.has("exceptionTypes")) {
      JsonNode types = node.get("exceptionTypes");
      for (JsonNode type : types) {
        int count = type.get("count").asInt();
        assertTrue(count >= 10);
      }
    }
  }

  @Test
  void exceptionsAutoDetectsEventType() throws Exception {
    Method handleJfrExceptions = getMethod("handleJfrExceptions", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrExceptions.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    String eventType = node.get("eventType").asText();
    assertTrue(
        eventType.equals("datadog.ExceptionSample")
            || eventType.equals("jdk.JavaExceptionThrow"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private void openSession(String path) throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", path);

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
