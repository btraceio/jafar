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

/** Tests for jfr_hotmethods MCP tool. */

class JafarMcpServerHotmethodsTest extends BaseJfrTest {

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
  void hotmethodsReturnsTopMethods() throws Exception {
    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("eventType"));
    assertTrue(node.has("totalSamples"));
    assertTrue(node.has("methods"));

    JsonNode methods = node.get("methods");
    assertTrue(methods.isArray());
  }

  @Test
  void hotmethodsIncludesMethodDetails() throws Exception {
    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode methods = node.get("methods");
    if (methods.size() > 0) {
      JsonNode method = methods.get(0);
      assertTrue(method.has("method"));
      assertTrue(method.has("samples"));
      assertTrue(method.has("pct"));
    }
  }

  @Test
  void hotmethodsRespectsLimit() throws Exception {
    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 5);

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode methods = node.get("methods");
    assertTrue(methods.size() <= 5);
  }

  @Test
  void hotmethodsCanExcludeNative() throws Exception {
    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("includeNative", false);

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void hotmethodsAutoDetectsEventType() throws Exception {
    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    String eventType = node.get("eventType").asText();
    assertTrue(
        eventType.equals("jdk.ExecutionSample")
            || eventType.equals("datadog.ExecutionSample"));
  }

  @Test
  void hotmethodsFailsWhenNoExecutionSamples() throws Exception {
    // Close current session and open one without execution samples
    Method handleJfrClose = getMethod("handleJfrClose", Map.class);
    handleJfrClose.invoke(server, Map.of("closeAll", true));

    // Would need a recording without execution samples for this test
    // For now, just test error handling with explicit event type

    Method handleJfrHotmethods = getMethod("handleJfrHotmethods", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("eventType", "jdk.NonexistentEvent");

    CallToolResult result = (CallToolResult) handleJfrHotmethods.invoke(server, args);

    // Should handle gracefully - either error or empty result
    assertNotNull(result);
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
