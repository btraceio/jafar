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

/** Tests for jfr_tsa (Thread State Analysis) MCP tool. */
class JafarMcpServerTsaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEST_JFR = System.getProperty("user.dir") + "/parser/src/test/resources/test-jfr.jfr";

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    openSession();
  }

  @Test
  void tsaAnalyzesThreadStates() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("TSA", node.get("method").asText());
    assertTrue(node.has("recordingPath"));
    assertTrue(node.has("totalSamples"));
  }

  @Test
  void tsaIncludesGlobalStateDistribution() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("globalStateDistribution"));
    JsonNode stateDist = node.get("globalStateDistribution");

    assertTrue(stateDist.isArray());
  }

  @Test
  void tsaIncludesThreadBreakdown() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("threadBreakdown"));
    JsonNode threads = node.get("threadBreakdown");

    assertTrue(threads.isObject());
  }

  @Test
  void tsaRespectsTopThreadsParameter() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("topThreads", 3);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void tsaRespectsMinSamplesParameter() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("minSamples", 10);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void tsaCanDisableBlockingCorrelation() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("correlateBlocking", false);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
  }

  @Test
  void tsaCanDisableInsights() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("includeInsights", false);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertFalse(node.has("insights"));
    assertFalse(node.has("summary"));
  }

  @Test
  void tsaIncludesInsightsByDefault() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("insights"));
    assertTrue(node.has("summary"));
  }

  @Test
  void tsaHandlesTimeWindow() throws Exception {
    Method handleJfrTsa = getMethod("handleJfrTsa", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("startTime", 0L);
    args.put("endTime", 1000000000L);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private void openSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", TEST_JFR);

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
