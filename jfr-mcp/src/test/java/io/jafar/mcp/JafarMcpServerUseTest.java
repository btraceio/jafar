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
  // jfr_use queue integration tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void useMethodHandlesQueueSaturation() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("threads"));
    args.put("includeInsights", true);

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify threads resource exists
    JsonNode resources = node.get("resources");
    assertTrue(resources.has("threads"));
    JsonNode threads = resources.get("threads");
    assertTrue(threads.has("saturation"));

    JsonNode saturation = threads.get("saturation");

    // If QueueTime events exist, verify structure
    if (saturation.has("queueSaturation")) {
      JsonNode queueSat = saturation.get("queueSaturation");

      // Verify required fields
      assertTrue(queueSat.has("totalQueueTimeMs"), "Missing totalQueueTimeMs");
      assertTrue(queueSat.has("totalQueuedItems"), "Missing totalQueuedItems");
      assertTrue(queueSat.has("avgQueueTimeMs"), "Missing avgQueueTimeMs");
      assertTrue(queueSat.has("maxQueueTimeMs"), "Missing maxQueueTimeMs");
      assertTrue(queueSat.has("byScheduler"), "Missing byScheduler breakdown");
      assertTrue(queueSat.has("assessment"), "Missing assessment");

      // Verify assessment is valid
      String assessment = queueSat.get("assessment").asText();
      assertTrue(
          assessment.equals("HIGH_QUEUE_SATURATION")
              || assessment.equals("MODERATE_QUEUE_SATURATION")
              || assessment.equals("LOW_QUEUE_SATURATION"),
          "Invalid assessment: " + assessment);

      // Verify byScheduler structure
      JsonNode byScheduler = queueSat.get("byScheduler");
      assertTrue(byScheduler.isObject(), "byScheduler should be an object");

      // If there are schedulers, verify their structure
      if (byScheduler.size() > 0) {
        JsonNode firstScheduler = byScheduler.elements().next();
        assertTrue(firstScheduler.has("queueType"), "Scheduler missing queueType");
        assertTrue(firstScheduler.has("count"), "Scheduler missing count");
        assertTrue(firstScheduler.has("totalTimeMs"), "Scheduler missing totalTimeMs");
        assertTrue(firstScheduler.has("avgTimeMs"), "Scheduler missing avgTimeMs");
        assertTrue(firstScheduler.has("maxTimeMs"), "Scheduler missing maxTimeMs");
        assertTrue(firstScheduler.has("p95Ms"), "Scheduler missing p95Ms");
        assertTrue(firstScheduler.has("p99Ms"), "Scheduler missing p99Ms");
      }

      // Verify lockContention is still present (restructured)
      assertTrue(saturation.has("lockContention"), "Lock contention should be preserved");
    } else {
      // No QueueTime events - verify graceful handling
      // May have lock contention data, or just a message if no contention either
      assertTrue(
          saturation.has("contentionEvents")
              || saturation.has("lockContention")
              || saturation.has("message")
              || saturation.has("assessment"),
          "Saturation should have some content");
    }
  }

  @Test
  void useMethodHandlesMissingQueueEvents() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("threads"));

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    // Should not error even if no QueueTime events
    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify basic structure is intact
    JsonNode resources = node.get("resources");
    assertTrue(resources.has("threads"));
  }

  @Test
  void useMethodIncludesQueueInsights() throws Exception {
    Method handleJfrUse = JafarMcpServer.class.getDeclaredMethod("handleJfrUse", Map.class);
    handleJfrUse.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("resources", List.of("threads"));
    args.put("includeInsights", true);

    CallToolResult result = (CallToolResult) handleJfrUse.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify insights exist
    assertTrue(node.has("insights"));
    JsonNode insights = node.get("insights");
    assertTrue(insights.has("recommendations"));
    assertTrue(insights.has("bottlenecks"));

    // If high queue saturation exists, should be in bottlenecks
    JsonNode threads = node.get("resources").get("threads");
    if (threads.has("saturation")) {
      JsonNode saturation = threads.get("saturation");
      if (saturation.has("queueSaturation")) {
        JsonNode queueSat = saturation.get("queueSaturation");
        String assessment = queueSat.get("assessment").asText();
        if ("HIGH_QUEUE_SATURATION".equals(assessment)) {
          JsonNode bottlenecks = insights.get("bottlenecks");
          boolean hasQueueBottleneck = false;
          for (JsonNode bottleneck : bottlenecks) {
            if (bottleneck.asText().contains("queue")) {
              hasQueueBottleneck = true;
              break;
            }
          }
          assertTrue(hasQueueBottleneck, "High queue saturation should be in bottlenecks");
        }
      }
    }
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
  // jfr_tsa queue integration tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void tsaCorrelatesWithQueues() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("correlateBlocking", true);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify basic structure
    assertEquals("TSA", node.get("method").asText());
    assertTrue(node.has("threadProfiles"));

    // If QueueTime events exist, verify queue correlation structure
    if (node.has("correlations")) {
      JsonNode correlations = node.get("correlations");

      if (correlations.has("queuedOn")) {
        JsonNode queuedOn = correlations.get("queuedOn");
        assertTrue(queuedOn.isObject(), "queuedOn should be an object");

        // If there are queue correlations, verify structure
        if (queuedOn.size() > 0) {
          JsonNode firstQueue = queuedOn.elements().next();
          assertTrue(firstQueue.has("queueType"), "Queue missing queueType");
          assertTrue(firstQueue.has("samples"), "Queue missing samples");
          assertTrue(firstQueue.has("threads"), "Queue missing threads count");

          // May have timing info if durations available
          if (firstQueue.has("avgQueueTimeMs")) {
            assertTrue(firstQueue.has("maxQueueTimeMs"), "Should have max if avg present");
          }
        }
      }
    }
  }

  @Test
  void tsaThreadProfilesIncludeQueueInfo() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("correlateBlocking", true);
    args.put("topThreads", 10);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify thread profiles exist
    assertTrue(node.has("threadProfiles"));
    JsonNode threadProfiles = node.get("threadProfiles");
    assertTrue(threadProfiles.isArray());

    // Check if any thread profiles have queuedOn field
    boolean foundQueuedOn = false;
    for (JsonNode profile : threadProfiles) {
      // Verify standard fields
      assertTrue(profile.has("threadId"), "Profile missing threadId");
      assertTrue(profile.has("threadName"), "Profile missing threadName");
      assertTrue(profile.has("totalSamples"), "Profile missing totalSamples");
      assertTrue(profile.has("stateBreakdown"), "Profile missing stateBreakdown");
      assertTrue(profile.has("assessment"), "Profile missing assessment");

      // If queuedOn exists, verify it's an array of executor names
      if (profile.has("queuedOn")) {
        foundQueuedOn = true;
        JsonNode queuedOn = profile.get("queuedOn");
        assertTrue(queuedOn.isArray(), "queuedOn should be an array");
        assertTrue(queuedOn.size() > 0, "queuedOn array should not be empty");

        // Verify entries are strings (executor names)
        for (JsonNode executor : queuedOn) {
          assertTrue(executor.isTextual(), "Executor names should be strings");
          assertFalse(executor.asText().isEmpty(), "Executor name should not be empty");
        }
      }
    }

    // Note: foundQueuedOn may be false if recording has no QueueTime events
    // This is acceptable - the test verifies structure when present
  }

  @Test
  void tsaIncludesQueueInsights() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("correlateBlocking", true);
    args.put("includeInsights", true);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    assertFalse(result.isError());
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify insights exist
    assertTrue(node.has("insights"));
    JsonNode insights = node.get("insights");
    assertTrue(insights.has("patterns"));
    assertTrue(insights.has("recommendations"));

    // If high queue times detected, should be in patterns/recommendations
    if (node.has("correlations")) {
      JsonNode correlations = node.get("correlations");
      if (correlations.has("queuedOn")) {
        JsonNode queuedOn = correlations.get("queuedOn");

        // Check if any queue has high avg time (>50ms threshold)
        boolean hasHighQueueTime = false;
        for (JsonNode queue : queuedOn) {
          if (queue.has("avgQueueTimeMs")) {
            double avgMs = queue.get("avgQueueTimeMs").asDouble();
            if (avgMs > 50) {
              hasHighQueueTime = true;
              break;
            }
          }
        }

        if (hasHighQueueTime) {
          // Should be mentioned in patterns or recommendations
          JsonNode patterns = insights.get("patterns");
          JsonNode recommendations = insights.get("recommendations");

          boolean mentionedInInsights = false;
          for (JsonNode pattern : patterns) {
            if (pattern.asText().toLowerCase().contains("queue")) {
              mentionedInInsights = true;
              break;
            }
          }
          if (!mentionedInInsights) {
            for (JsonNode rec : recommendations) {
              if (rec.asText().toLowerCase().contains("queue")
                  || rec.asText().toLowerCase().contains("pool")) {
                mentionedInInsights = true;
                break;
              }
            }
          }

          assertTrue(
              mentionedInInsights, "High queue times should be mentioned in insights");
        }
      }
    }
  }

  @Test
  void tsaHandlesMissingQueueEvents() throws Exception {
    Method handleJfrTsa = JafarMcpServer.class.getDeclaredMethod("handleJfrTsa", Map.class);
    handleJfrTsa.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "1");
    args.put("correlateBlocking", true);

    CallToolResult result = (CallToolResult) handleJfrTsa.invoke(server, args);

    // Should not error even if no QueueTime events
    assertFalse(result.isError(), () -> extractTextContent(result));
    String json = extractTextContent(result);
    JsonNode node = MAPPER.readTree(json);

    // Verify basic structure is intact
    assertEquals("TSA", node.get("method").asText());
    assertTrue(node.has("totalSamples"));
    assertTrue(node.has("threadProfiles"));
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
