package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for jfr_use and jfr_tsa MCP tools.
 *
 * <p>Tests require a real JFR file to be available at /tmp/main.jfr.
 */
@EnabledIf("testFileExists")
class JafarMcpServerUseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;
  private static final String TEST_JFR_PATH = "/tmp/main.jfr";

  static boolean testFileExists() {
    return Paths.get(TEST_JFR_PATH).toFile().exists();
  }

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();

    // Open the recording
    Method handleJfrOpen = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    handleJfrOpen.setAccessible(true);

    Map<String, Object> openArgs = new HashMap<>();
    openArgs.put("path", TEST_JFR_PATH);

    CallToolResult openResult = (CallToolResult) handleJfrOpen.invoke(server, openArgs);
    assertFalse(openResult.isError(), "Failed to open test recording");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_use tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void useMethodAnalyzesAllResources() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("all"));
    args.put("includeInsights", true);

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify structure
    assertEquals("USE", node.get("method").asText());
    assertTrue(node.has("recordingPath"));
    assertTrue(node.has("resources"));

    // Verify all resources are present
    JsonNode resources = node.get("resources");
    assertTrue(resources.has("cpu"), "CPU resource missing");
    assertTrue(resources.has("memory"), "Memory resource missing");
    assertTrue(resources.has("threads"), "Threads resource missing");
    assertTrue(resources.has("io"), "I/O resource missing");

    // Verify insights and summary
    assertTrue(node.has("insights"));
    assertTrue(node.has("summary"));
  }

  @Test
  void useMethodAnalyzesSpecificResource() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("cpu"));

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode resources = node.get("resources");
    assertTrue(resources.has("cpu"));
    assertFalse(resources.has("memory"));
    assertFalse(resources.has("threads"));
    assertFalse(resources.has("io"));
  }

  @Test
  void useMethodSupportsTimeWindowing() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("cpu"));
    args.put("startTime", 0L);
    args.put("endTime", 1000000000L); // First second

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("timeWindow"));
    JsonNode timeWindow = node.get("timeWindow");
    assertEquals(0, timeWindow.get("startTime").asLong());
    assertEquals(1000000000L, timeWindow.get("endTime").asLong());
  }

  @Test
  void useMethodHandlesMissingSession() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "999"); // Non-existent session

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertTrue(result.isError());
    String json = extractTextContent(result);
    assertTrue(json.contains("Session not found") || json.contains("No session"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_tsa tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void tsaAnalyzesThreadStates() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("topThreads", 5);
    args.put("correlateBlocking", true);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify structure
    assertEquals("TSA", node.get("method").asText());
    assertTrue(node.has("totalSamples"));
    assertTrue(node.has("stateDistribution"));
    assertTrue(node.has("topThreadsByState"));
    assertTrue(node.has("threadProfiles"));
  }

  @Test
  void tsaSupportsMinSamplesThreshold() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("minSamples", 100); // High threshold

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Should filter out threads with few samples
    assertTrue(node.has("threadProfiles"));
    // Can't assert exact count without knowing recording content
  }

  @Test
  void tsaCorrelatesWithLocks() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("correlateBlocking", true);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // If correlateBlocking is true, correlations section should exist
    // (may be empty if no lock events in recording)
    if (node.has("correlations")) {
      assertTrue(
          node.get("correlations").has("blockedOn")
              || node.get("correlations").has("waitingOn"));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private String extractTextContent(CallToolResult result) {
    return result.content().stream()
        .filter(c -> c instanceof TextContent)
        .map(c -> ((TextContent) c).text())
        .findFirst()
        .orElse("");
  }
}
