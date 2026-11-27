package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.jfr.ProfileCompareTools;
import io.jafar.mcp.query.DefaultQueryEvaluator;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.pprof.MinimalPprofBuilder;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code profile_compare}: each side a {format, sessionId} pair, with the sampler policy. */
class ProfileCompareToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Checked in: 14,202 {@code datadog.ExecutionSample} events. */
  private static final Path TCK =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path dir;

  /** async-profiler 4.6's real files: one run as JFR and as jfrconv's OTLP, and agent OTLP. */
  private static final Path REAL_OTLP =
      Path.of("..", "otlp-shell", "src", "test", "resources", "real").toAbsolutePath().normalize();

  private SessionRegistry jfr;
  private PprofSessionRegistry pprof;
  private OtlpSessionRegistry otlp;
  private ProfileCompareTools tools;

  @BeforeEach
  void setUp() {
    jfr = new SessionRegistry();
    pprof = new PprofSessionRegistry();
    otlp = new OtlpSessionRegistry();
    tools =
        new ProfileCompareTools(
            jfr, pprof, otlp, new DefaultQueryEvaluator(), new McpResultFactory(MAPPER));
  }

  @AfterEach
  void tearDown() {
    jfr.shutdown();
    pprof.shutdown();
    otlp.shutdown();
  }

  @Test
  void comparesARecordingWithItsOtlpConversion() throws Exception {
    jfr.open(REAL_OTLP.resolve("ap-cpu.jfr"), "rec");
    otlp.open(REAL_OTLP.resolve("conv-cpu.otlp"), "conv");
    JsonNode result =
        success(
            tools.handleProfileCompare(
                Map.of(
                    "baseline",
                    Map.of("format", "jfr", "sessionId", "rec"),
                    "candidate",
                    Map.of("format", "otlp", "sessionId", "conv"),
                    "inclusiveFrames",
                    true,
                    "minDeltaPct",
                    0)));
    assertEquals("otlp", result.at("/candidate/format").asString());
    assertEquals("conv", result.at("/candidate/alias").asString());
    assertEquals("cpu/count", result.at("/candidate/eventType").asString());
    assertEquals(216, result.at("/candidate/samples").asInt());
    assertEquals(216, result.at("/baseline/samples").asInt());
    assertTrue(result.at("/candidate/sampleCountKnown").asBoolean());
    // One run in two formats: every row unchanged.
    assertFalse(result.at("/frames").isEmpty());
    for (JsonNode row : result.at("/frames")) {
      assertEquals(0.0, row.at("/deltaPct").asDouble(), row.toString());
      assertFalse(row.at("/significant").asBoolean(), row.toString());
    }
  }

  @Test
  void otlpSamplersOfDifferentKindsAreRefused() throws Exception {
    otlp.open(REAL_OTLP.resolve("ap-cpu.otlp"), "cpu");
    otlp.open(REAL_OTLP.resolve("ap-wall.otlp"), "wall");
    String message =
        error(
            tools.handleProfileCompare(
                Map.of(
                    "baseline", Map.of("format", "otlp", "sessionId", "cpu"),
                    "candidate", Map.of("format", "otlp", "sessionId", "wall"))));
    assertTrue(message.contains("Different samplers (baseline otlp perf_events/count"), message);
    JsonNode allowed =
        success(
            tools.handleProfileCompare(
                Map.of(
                    "baseline", Map.of("format", "otlp", "sessionId", "cpu"),
                    "candidate", Map.of("format", "otlp", "sessionId", "wall"),
                    "allowMixedSamplers", true)));
    assertTrue(allowed.at("/comparability").toString().contains("Mixed samplers"));
  }

  @Test
  void comparesARecordingWithAPprofProfile() throws Exception {
    jfr.open(TCK, "rec");
    pprof.open(profile("cpu", 0), "prof");
    JsonNode result =
        success(
            tools.handleProfileCompare(
                Map.of(
                    "baseline", Map.of("format", "jfr", "sessionId", "rec"),
                    "candidate", Map.of("format", "pprof", "sessionId", "prof"))));

    JsonNode baseline = result.get("baseline");
    assertEquals("jfr", baseline.get("format").asString());
    assertEquals("rec", baseline.get("alias").asString());
    assertEquals("datadog.ExecutionSample", baseline.get("eventType").asString());
    JsonNode candidate = result.get("candidate");
    assertEquals("pprof", candidate.get("format").asString());
    assertEquals("samples/count", candidate.get("eventType").asString());
    assertEquals("CPU", candidate.get("samplerKind").asString());
    assertTrue(candidate.get("sampleCountKnown").asBoolean());
    assertEquals(1000, candidate.get("samples").asLong());
    assertEquals(0, result.get("metrics").size(), "event rates need two JFR recordings");
    assertTrue(result.get("comparability").toString().contains("Different formats"));
    assertFalse(result.has("inclusiveFrames"), "inclusive frames are opt-in");
  }

  @Test
  void refusesSamplersOfDifferentKindsUnlessAllowed() throws Exception {
    pprof.open(profile("cpu", 0), "cpu");
    pprof.open(profile("wall", 400), "wall");
    Map<String, Object> args = new HashMap<>();
    args.put("baseline", Map.of("format", "pprof", "sessionId", "cpu"));
    args.put("candidate", Map.of("format", "pprof", "sessionId", "wall"));
    String error = error(tools.handleProfileCompare(args));
    assertTrue(error.contains("Different samplers"), error);
    assertTrue(error.contains("allowMixedSamplers"), error);

    args.put("allowMixedSamplers", true);
    args.put("inclusiveFrames", true);
    JsonNode result = success(tools.handleProfileCompare(args));
    assertTrue(result.get("comparability").toString().contains("Mixed samplers"));
    List<JsonNode> rows = new ArrayList<>();
    result.get("frames").forEach(rows::add);
    result.get("inclusiveFrames").forEach(rows::add);
    assertFalse(rows.isEmpty(), "the 40-point move is reported");
    for (JsonNode row : rows) {
      assertFalse(row.get("significant").asBoolean(), row.toString());
    }
  }

  @Test
  void aPerSideSampleTypeSelectsAColumn() throws Exception {
    pprof.open(profile("cpu", 0), "a");
    pprof.open(profile("cpu", 400), "b");
    JsonNode result =
        success(
            tools.handleProfileCompare(
                Map.of(
                    "baseline", Map.of("format", "pprof", "sessionId", "a"),
                    "candidate", Map.of("format", "pprof", "sessionId", "b", "sampleType", "cpu"),
                    "sampleType", "samples/count")));
    assertEquals("samples/count", result.get("baseline").get("eventType").asString());
    assertEquals("cpu/nanoseconds", result.get("candidate").get("eventType").asString());
    assertEquals(
        "requested; counts: the samples/count column",
        result.get("candidate").get("sampleTypeChosen").asString());
    // cpu/nanoseconds takes its counts from the samples/count column: the move is judged.
    assertTrue(result.get("frames").toString().contains("\"significant\":true"), result.toString());
  }

  @Test
  void twoRecordingsOfDifferentSamplerKindsGetANoteAsInJfrCompare() throws Exception {
    // CPU (datadog.ExecutionSample) against wall (jdk.NativeMethodSample): jfr_compare and
    // jfr-shell compare compare such a pair with a note, so profile_compare does too.
    jfr.open(TCK, "cpu");
    jfr.open(nativeMethodSamples(dir.resolve("native.jfr"), 100), "native");
    JsonNode result =
        success(
            tools.handleProfileCompare(
                Map.of(
                    "baseline", Map.of("format", "jfr", "sessionId", "cpu"),
                    "candidate", Map.of("format", "jfr", "sessionId", "native"))));
    assertEquals("jdk.NativeMethodSample", result.get("candidate").get("eventType").asString());
    assertTrue(
        result.get("comparability").toString().contains("Different execution sample event types"),
        result.get("comparability").toString());
  }

  @Test
  void rejectsBadSides() throws Exception {
    assertTrue(error(tools.handleProfileCompare(Map.of())).contains("baseline is required"));
    assertTrue(
        error(
                tools.handleProfileCompare(
                    Map.of(
                        "baseline", Map.of("format", "heap"),
                        "candidate", Map.of("format", "pprof"))))
            .contains("baseline.format must be jfr, pprof or otlp, not heap"));
    pprof.open(profile("cpu", 0), "one");
    assertTrue(
        error(
                tools.handleProfileCompare(
                    Map.of(
                        "baseline", Map.of("format", "pprof", "sessionId", "one"),
                        "candidate", Map.of("format", "pprof"))))
            .contains("same pprof session"));
    assertTrue(
        error(
                tools.handleProfileCompare(
                    Map.of(
                        "baseline", Map.of("format", "pprof"),
                        "candidate", Map.of("format", "jfr"))))
            .contains("jfr_open"));
  }

  @Test
  void isRegisteredNextToJfrCompare() throws Exception {
    Method create = JafarMcpServer.class.getDeclaredMethod("createToolSpecifications");
    create.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> specs =
        (List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification>)
            create.invoke(new JafarMcpServer());
    List<String> names = specs.stream().map(s -> s.tool().name()).toList();
    assertEquals(
        names.indexOf("jfr_compare") + 1, names.indexOf("profile_compare"), names.toString());
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Sample types {@code samples/count} and {@code <type>/nanoseconds}: 1000 samples, {@code moved}
   * of them with {@code m.New.work} as their leaf instead of {@code m.Busy.work}.
   */
  private Path profile(String type, int moved) throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSampleType(b.addString(type), b.addString("nanoseconds"));
    b.setDurationNanos(10_000_000_000L);
    int file = b.addString("Main.java");
    long busy = b.addLocation(b.addFunction(b.addString("m.Busy.work"), file), 1);
    long fresh = b.addLocation(b.addFunction(b.addString("m.New.work"), file), 1);
    long main = b.addLocation(b.addFunction(b.addString("m.Main.main"), file), 1);
    b.addSample(
        List.of(busy, main), List.of(1000L - moved, (1000L - moved) * 10_000_000L), List.of());
    if (moved > 0) {
      b.addSample(List.of(fresh, main), List.of((long) moved, moved * 10_000_000L), List.of());
    }
    return b.write(dir);
  }

  /** A recording with {@code count} {@code jdk.NativeMethodSample} events, one second apart. */
  private static Path nativeMethodSamples(Path file, int count) throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording =
        org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(bytes)) {
      org.openjdk.jmc.flightrecorder.writer.api.Types types = recording.getTypes();
      org.openjdk.jmc.flightrecorder.writer.api.Type stackTrace =
          types.getType(org.openjdk.jmc.flightrecorder.writer.api.Types.JDK.STACK_TRACE);
      org.openjdk.jmc.flightrecorder.writer.api.Type thread =
          types.getType(org.openjdk.jmc.flightrecorder.writer.api.Types.JDK.THREAD);
      org.openjdk.jmc.flightrecorder.writer.api.Type event =
          recording.registerEventType("jdk.NativeMethodSample", t -> {});
      long start = System.nanoTime();
      for (int i = 0; i < count; i++) {
        long at = start + i * 1_000_000_000L;
        recording.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", stackTrace.nullValue());
                  v.putField("eventThread", thread.nullValue());
                  v.putField("startTime", at);
                }));
      }
    }
    return java.nio.file.Files.write(file, bytes.toByteArray());
  }

  private static JsonNode success(CallToolResult result) throws Exception {
    assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
    return MAPPER.readTree(text(result));
  }

  private static String error(CallToolResult result) {
    assertTrue(Boolean.TRUE.equals(result.isError()), () -> "expected an error: " + text(result));
    return text(result);
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
