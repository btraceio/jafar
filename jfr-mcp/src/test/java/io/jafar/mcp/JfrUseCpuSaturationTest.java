package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #263: on a recording with {@code jdk.ContainerCPUThrottling} events, {@code jfr_use} set
 * {@code cpu.saturation.value} to the sum of the throttled-period counters, and the insights read
 * it as a percentage: "Investigate thread blocking: 16356.0% of CPU time spent waiting/blocked" on
 * test-dd.jfr. Those counters are the cgroup's running totals; the share for the recording is
 * computed here from the first and last event with the JDK's own reader.
 */
class JfrUseCpuSaturationTest {

  private static final Path RECORDING =
      Path.of("../demo/src/test/resources/test-dd.jfr").toAbsolutePath().normalize();

  @Test
  void containerThrottlingIsTheShareOfPeriodsBetweenTheFirstAndLastReading() throws Exception {
    Assumptions.assumeTrue(Files.isRegularFile(RECORDING), "missing " + RECORDING);
    List<RecordedEvent> readings = new ArrayList<>();
    try (RecordingFile file = new RecordingFile(RECORDING)) {
      while (file.hasMoreEvents()) {
        RecordedEvent event = file.readEvent();
        if ("jdk.ContainerCPUThrottling".equals(event.getEventType().getName())) {
          readings.add(event);
        }
      }
    }
    Assumptions.assumeTrue(readings.size() >= 2, "needs two throttling readings");
    readings.sort(Comparator.comparing(RecordedEvent::getStartTime));
    RecordedEvent first = readings.get(0);
    RecordedEvent last = readings.get(readings.size() - 1);
    long throttled = last.getLong("cpuThrottledSlices") - first.getLong("cpuThrottledSlices");
    long elapsed = last.getLong("cpuElapsedSlices") - first.getLong("cpuElapsedSlices");
    long throttledNs =
        last.getDuration("cpuThrottledTime").toNanos()
            - first.getDuration("cpuThrottledTime").toNanos();
    assertTrue(elapsed > 0, "periods elapsed between the readings");

    JsonNode root = use(Map.of());
    JsonNode saturation = root.path("resources").path("cpu").path("saturation");

    assertEquals("containerThrottling", text(saturation, "source"), saturation.toString());
    assertEquals("%", text(saturation, "unit"), saturation.toString());
    assertEquals(throttled, saturation.path("throttledSlices").asLong(), saturation.toString());
    assertEquals(elapsed, saturation.path("elapsedSlices").asLong(), saturation.toString());
    assertEquals(throttledNs, saturation.path("throttledTimeNs").asLong(), saturation.toString());
    assertEquals(
        Math.round(throttled * 1000.0 / elapsed) / 10.0,
        saturation.path("value").asDouble(),
        1e-9,
        saturation.toString());

    // A window that leaves out the first reading is measured between the readings around it
    // (#263: 30-120 s was N/A while the readings were queried with the window's filter)
    JsonNode windowed =
        use(Map.of("startTime", 30_000_000_000L, "endTime", 120_000_000_000L))
            .path("resources")
            .path("cpu")
            .path("saturation");
    assertEquals(saturation.path("value"), windowed.path("value"), windowed.toString());

    String recommendations = root.path("insights").path("recommendations").toString();
    assertFalse(recommendations.contains("of CPU time spent waiting"), recommendations);
    if (saturation.path("value").asDouble() > 30) {
      assertTrue(recommendations.contains("CPU periods"), recommendations);
      assertFalse(recommendations.contains("blocking states"), recommendations);
    }
  }

  private static JsonNode use(Map<String, Object> window) throws Exception {
    JafarMcpServer server = new JafarMcpServer();
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    assertFalse(
        ((CallToolResult) open.invoke(server, Map.of("path", RECORDING.toString()))).isError());
    Method use =
        JafarMcpServer.class.getDeclaredMethod(
            "handleJfrUse", McpSyncServerExchange.class, Map.class, Object.class);
    use.setAccessible(true);
    CallToolResult result = (CallToolResult) use.invoke(server, null, useArgs(window), null);
    assertFalse(result.isError(), result.content().toString());
    return new ObjectMapper().readTree(((TextContent) result.content().get(0)).text());
  }

  private static String text(JsonNode node, String field) {
    return node.has(field) ? node.get(field).asString() : null;
  }

  private static Map<String, Object> useArgs(Map<String, Object> window) {
    Map<String, Object> args = new java.util.HashMap<>(window);
    args.put("resources", List.of("cpu"));
    args.put("includeInsights", true);
    return args;
  }
}
