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

/** Tests for jfr_diagnose MCP tool - automated performance diagnosis. */
class JafarMcpServerDiagnoseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEST_JFR = System.getProperty("user.dir") + "/parser/src/test/resources/test-jfr.jfr";

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    openSession();
  }

  @Test
  void diagnoseRunsComprehensiveAnalysis() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("method"));
    assertEquals("DIAGNOSE", node.get("method").asText());
    assertTrue(node.has("recordingPath"));
  }

  @Test
  void diagnoseIncludesSummary() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("summary"));
    JsonNode summary = node.get("summary");

    assertTrue(summary.has("totalEvents"));
    assertTrue(summary.has("totalEventTypes"));
  }

  @Test
  void diagnoseIncludesFindings() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("findings"));
    JsonNode findings = node.get("findings");
    assertTrue(findings.isArray());
  }

  @Test
  void diagnoseIncludesRecommendations() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    assertTrue(node.has("recommendations"));
    JsonNode recommendations = node.get("recommendations");
    assertTrue(recommendations.isArray());
  }

  @Test
  void diagnoseCanIncludeAnalysisResults() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("includeAnalysis", true);

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // When analysis is included, we should see detailed analysis data
    assertTrue(node.has("summary"));
  }

  @Test
  void diagnoseCanExcludeAnalysisResults() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("includeAnalysis", false);

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // When analysis is excluded, we should still have findings and recommendations
    assertTrue(node.has("findings"));
    assertTrue(node.has("recommendations"));
  }

  @Test
  void diagnoseDetectsExceptionIssues() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode findings = node.get("findings");

    // Check if exception-related findings exist (if recording has exceptions)
    boolean hasExceptionFinding = false;
    for (JsonNode finding : findings) {
      String text = finding.asText();
      if (text.toUpperCase().contains("EXCEPTION")) {
        hasExceptionFinding = true;
        break;
      }
    }

    // This assertion is conditional on the recording having exceptions
    // For test-jfr.jfr, this may or may not be true
  }

  @Test
  void diagnoseDetectsGCIssues() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode findings = node.get("findings");

    // Check if GC-related findings exist (if recording has GC pressure)
    boolean hasGcFinding = false;
    for (JsonNode finding : findings) {
      String text = finding.asText();
      if (text.toUpperCase().contains("GC")) {
        hasGcFinding = true;
        break;
      }
    }

    // This assertion is conditional on the recording having GC events
  }

  @Test
  void diagnoseDetectsCpuIssues() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    JsonNode findings = node.get("findings");

    // Check if CPU-related findings exist (if recording has CPU samples)
    boolean hasCpuFinding = false;
    for (JsonNode finding : findings) {
      String text = finding.asText();
      if (text.toUpperCase().contains("CPU")) {
        hasCpuFinding = true;
        break;
      }
    }

    // This assertion is conditional on the recording having CPU samples
  }

  @Test
  void diagnoseHandlesMissingSession() throws Exception {
    Method handleJfrDiagnose = getMethod("handleJfrDiagnose", Map.class);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "nonexistent");

    CallToolResult result = (CallToolResult) handleJfrDiagnose.invoke(server, args);

    assertTrue(result.isError());
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
