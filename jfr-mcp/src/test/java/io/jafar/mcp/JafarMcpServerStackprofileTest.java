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
 * Tests for jfr_stackprofile MCP tool.
 *
 * <p>Uses the smaller test-dd.jfr (~2MB) to avoid OOM when building the full profile tree.
 */
class JafarMcpServerStackprofileTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path jfrFile;

  private JafarMcpServer server;

  @BeforeAll
  static void findJfrFile() {
    jfrFile = Path.of("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!jfrFile.toFile().exists()) {
      jfrFile = Path.of("demo/src/test/resources/test-dd.jfr").normalize();
    }
    if (!jfrFile.toFile().exists()) {
      throw new IllegalStateException("JFR file not found at: " + jfrFile.toAbsolutePath());
    }
  }

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
  void stackprofileReturnsFrames() throws Exception {
    Method handleJfrStackprofile = getMethod("handleJfrStackprofile", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrStackprofile.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("eventType"));
    assertTrue(node.has("direction"));
    assertEquals("top-down", node.get("direction").asText());
    assertTrue(node.has("frames"));
    assertTrue(node.get("frames").isArray());
    assertTrue(node.has("totalSamples"));
    assertTrue(node.get("totalSamples").asLong() > 0);
    assertTrue(node.has("frameCount"));
  }

  @Test
  void stackprofileFramesHaveProfile() throws Exception {
    Method handleJfrStackprofile = getMethod("handleJfrStackprofile", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrStackprofile.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode frames = node.get("frames");
    assertTrue(frames.size() > 0, "should have at least one frame");
    JsonNode firstFrame = frames.get(0);

    assertTrue(firstFrame.has("method"));
    assertTrue(firstFrame.has("depth"));
    assertTrue(firstFrame.has("profile"));

    JsonNode profile = firstFrame.get("profile");
    assertTrue(profile.has("self"));
    assertTrue(profile.has("total"));
    assertTrue(profile.has("totalPct"));
    assertTrue(profile.get("totalPct").isNumber());
    assertTrue(profile.has("selfPct"));
    assertTrue(profile.get("selfPct").isNumber());
    assertTrue(profile.has("pattern"));
    assertTrue(profile.has("category"));
    assertTrue(profile.has("timeBuckets"));
    assertTrue(profile.get("timeBuckets").isArray());
    assertTrue(profile.has("threadCounts"));
    assertTrue(profile.get("threadCounts").isObject());
  }

  @Test
  void stackprofileRespectsLimit() throws Exception {
    Method handleJfrStackprofile = getMethod("handleJfrStackprofile", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("limit", 5);

    CallToolResult result = (CallToolResult) handleJfrStackprofile.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode frames = node.get("frames");
    assertTrue(frames.size() <= 5);
  }

  @Test
  void stackprofileRespectsDirection() throws Exception {
    Method handleJfrStackprofile = getMethod("handleJfrStackprofile", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("direction", "bottom-up");

    CallToolResult result = (CallToolResult) handleJfrStackprofile.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertEquals("bottom-up", node.get("direction").asText());
    assertTrue(node.get("frames").isArray());
  }

  @Test
  void stackprofileAutoDetectsEventType() throws Exception {
    Method handleJfrStackprofile = getMethod("handleJfrStackprofile", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrStackprofile.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    String eventType = node.get("eventType").asText();
    assertTrue(
        eventType.equals("jdk.ExecutionSample") || eventType.equals("datadog.ExecutionSample"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private void openSession() throws Exception {
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("path", jfrFile.toString());

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
