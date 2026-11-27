package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #276 on a JDK recording without {@code jdk.CPULoad}: every {@code jdk.ExecutionSample} carries
 * {@code STATE_RUNNABLE}, and {@code jfr_use}'s thread-state fallback, which compared with {@code
 * RUNNABLE}, reported 0% of samples runnable.
 */
class JdkThreadStatesUseTest {

  @TempDir static Path dir;

  private static Path recording;

  @BeforeAll
  static void record() throws Exception {
    recording = dir.resolve("busy.jfr");
    AtomicBoolean stop = new AtomicBoolean();
    List<Thread> spinners = new ArrayList<>();
    try (Recording r = new Recording()) {
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      r.disable("jdk.CPULoad");
      r.start();
      for (int i = 0; i < 2; i++) {
        Thread t =
            new Thread(
                () -> {
                  long x = 0;
                  while (!stop.get()) {
                    x += System.nanoTime() % 7;
                  }
                  if (x == 42) {
                    System.out.println(x);
                  }
                },
                "spinner-" + i);
        t.start();
        spinners.add(t);
      }
      Thread.sleep(1500);
      stop.set(true);
      for (Thread t : spinners) {
        t.join();
      }
      r.stop();
      r.dump(recording);
    }
  }

  @Test
  void theCpuFallbackSaysTheStatesGiveNoUtilization() throws Exception {
    long samples = 0;
    long stateRunnable = 0;
    try (RecordingFile file = new RecordingFile(recording)) {
      while (file.hasMoreEvents()) {
        RecordedEvent e = file.readEvent();
        if ("jdk.ExecutionSample".equals(e.getEventType().getName())) {
          samples++;
          if ("STATE_RUNNABLE".equals(e.getString("state"))) {
            stateRunnable++;
          }
        }
      }
    }
    assertTrue(samples >= 5, "the recording has execution samples: " + samples);
    assertEquals(samples, stateRunnable, "the JDK names every sample's state STATE_RUNNABLE");

    JsonNode cpu =
        call("handleJfrUse", Map.of("resources", List.of("cpu"))).path("resources").path("cpu");

    // Every sample is STATE_RUNNABLE, whatever the threads did: the states give no utilization
    assertTrue(
        cpu.path("warning").asString().contains("jdk.CPULoad events not found"), cpu.toString());
    assertEquals("N/A", cpu.path("utilization").path("value").asString(), cpu.toString());
    assertTrue(
        cpu.path("utilization")
            .path("detail")
            .asString()
            .contains("All " + samples + " jdk.ExecutionSample samples are RUNNABLE"),
        cpu.toString());
    assertEquals("N/A", cpu.path("saturation").path("value").asString(), cpu.toString());
  }

  @Test
  void threadStateAnalysisSaysTheStatesAreUninformative() throws Exception {
    JsonNode tsa = call("handleJfrTsa", Map.of());

    assertTrue(tsa.path("stateDistribution").has("RUNNABLE"), tsa.toString());
    assertTrue(tsa.path("stateNote").asString().contains("are RUNNABLE"), tsa.toString());
    for (JsonNode profile : tsa.path("threadProfiles")) {
      assertEquals("NOT_APPLICABLE", profile.path("assessment").asString(), profile.toString());
    }
    String patterns = tsa.path("insights").path("patterns").toString();
    assertFalse(patterns.contains("CPU utilization"), patterns);
    assertFalse(
        tsa.path("findings").toString().contains("high-cpu"), tsa.path("findings").toString());
  }

  @Test
  void samplesWhoseStatesVaryStillGetVerdicts() throws Exception {
    // A sampler that records states (async-profiler's wall mode, Datadog's): 60 runnable, 40
    // blocked on a monitor, and no jdk.CPULoad, so jfr_use falls back to the states
    Path mixed = dir.resolve("mixed.jfr");
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (org.openjdk.jmc.flightrecorder.writer.api.Recording r =
        org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(bytes)) {
      org.openjdk.jmc.flightrecorder.writer.api.Type sample =
          r.registerEventType(
              "jdk.ExecutionSample",
              t ->
                  t.addField(
                      "state", org.openjdk.jmc.flightrecorder.writer.api.Types.Builtin.STRING));
      long start = System.nanoTime();
      for (int i = 0; i < 100; i++) {
        String state = i < 60 ? "STATE_RUNNABLE" : "STATE_BLOCKED_ON_MONITOR_ENTER";
        long at = start + i * 1000L;
        r.writeEvent(
            sample.asValue(
                v -> {
                  v.putField("startTime", at);
                  v.putField("state", state);
                }));
      }
    }
    java.nio.file.Files.write(mixed, bytes.toByteArray());

    JsonNode cpu =
        call(mixed, "handleJfrUse", Map.of("resources", List.of("cpu")))
            .path("resources")
            .path("cpu");
    assertTrue(cpu.path("utilization").path("value").isNumber(), cpu.toString());
    assertEquals(60.0, cpu.path("utilization").path("value").asDouble(), 1e-9, cpu.toString());
    assertEquals(40.0, cpu.path("saturation").path("value").asDouble(), 1e-9, cpu.toString());

    JsonNode tsa = call(mixed, "handleJfrTsa", Map.of("minSamples", 1));
    assertFalse(tsa.has("stateNote"), tsa.toString());
    assertTrue(tsa.path("stateDistribution").has("BLOCKED"), tsa.toString());
    assertTrue(
        tsa.path("insights").path("patterns").toString().contains("lock contention"),
        tsa.toString());
  }

  private static JsonNode call(String handler, Map<String, Object> arguments) throws Exception {
    return call(recording, handler, arguments);
  }

  private static JsonNode call(Path file, String handler, Map<String, Object> arguments)
      throws Exception {
    JafarMcpServer server = new JafarMcpServer();
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    assertFalse(((CallToolResult) open.invoke(server, Map.of("path", file.toString()))).isError());
    Method tool =
        JafarMcpServer.class.getDeclaredMethod(
            handler, McpSyncServerExchange.class, Map.class, Object.class);
    tool.setAccessible(true);
    CallToolResult result =
        (CallToolResult) tool.invoke(server, null, new HashMap<>(arguments), null);
    assertFalse(result.isError(), result.content().toString());
    return new ObjectMapper().readTree(((TextContent) result.content().get(0)).text());
  }
}
