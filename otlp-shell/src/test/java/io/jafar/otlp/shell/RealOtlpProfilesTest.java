package io.jafar.otlp.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import io.jafar.otlp.shell.otlppath.OtlpPathEvaluator;
import io.jafar.otlp.shell.otlppath.OtlpPathParser;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SampleTypeInfo;
import io.jafar.shell.core.compare.SamplerKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The OTLP parser, OtlpPath and the compare reader against files a real producer wrote:
 * async-profiler 4.6's agent and its {@code jfrconv -o otlp}, and async-profiler 4.1's agent. How
 * each file was made, and its sha256, is in {@code src/test/resources/real/README.md}. The expected
 * values were decoded independently, field by field against opentelemetry-proto's {@code
 * profiles.proto}.
 */
class RealOtlpProfilesTest {

  static final Path REAL = Path.of("src", "test", "resources", "real");

  // --- the dictionary -------------------------------------------------------------------------

  @Test
  void everyTableStartsWithItsZeroValue() throws Exception {
    OtlpProfile.Dictionary dict = OtlpParser.parse(REAL.resolve("ap-cpu.otlp")).dictionary();
    assertEquals("", dict.stringTable().get(0));
    assertEquals(new OtlpProfile.Function("", "", "", 0), dict.functionTable().get(0));
    assertEquals(List.of(), dict.locationTable().get(0).lines());
    assertEquals(List.of(), dict.stackTable().get(0).locationIndices());
    assertEquals(1, dict.mappingTable().size());
    // The first real entries sit at index 1.
    assertEquals("java/lang/Integer.getChars", dict.functionTable().get(1).name());
    assertEquals(1, dict.locationTable().get(1).lines().get(0).functionIndex());
  }

  @Test
  void stacksResolveByAbsoluteIndex() throws Exception {
    List<Map<String, Object>> rows = run("ap-cpu.otlp", "samples");
    assertEquals(61, rows.size());
    // The first sample refers to stack 1, the second entry of the stack table.
    assertEquals(
        List.of(
            "java/lang/Integer.getChars",
            "java/lang/StringConcatHelper.prepend",
            "java/lang/StringConcatHelper.prepend",
            "java/lang/invoke/DirectMethodHandle$Holder.invokeStatic",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c002c00.invoke",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c003000.invoke",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c004c00.invoke",
            "java/lang/invoke/Invokers$Holder.linkToTargetMethod",
            "RealProf.allocate",
            "RealProf.main"),
        names(rows.get(0)));
    for (Map<String, Object> row : rows) {
      assertTrue(!names(row).isEmpty(), "every sample has a stack: " + row);
    }
  }

  @Test
  void attributesResolveByAbsoluteIndex() throws Exception {
    // jfrconv writes thread.name per sample; the agent writes no attributes.
    assertEquals(
        java.util.Set.of(
            "[DestroyJavaVM tid=24735]",
            "[tid=24754]",
            "[C2 CompilerThre tid=24748]",
            "[C1 CompilerThre tid=24749]",
            "[sleeper tid=24753]"),
        run("conv-cpu.otlp", "samples").stream()
            .map(r -> r.get("thread.name"))
            .collect(java.util.stream.Collectors.toSet()));
  }

  // --- values ---------------------------------------------------------------------------------

  @Test
  void agentValuesAreSampleCounts() throws Exception {
    assertEquals(217, sum("ap-cpu.otlp", "perf_events"));
    assertEquals(903, sum("ap-wall.otlp", "wall"));
  }

  @Test
  void aSampleWithoutValuesCountsItsTimestamps() throws Exception {
    // jfrconv writes no values for a single-event recording: one timestamp per sample, 216 of
    // them, which is also the JFR's jdk.ExecutionSample count.
    assertEquals(216, sum("conv-cpu.otlp", "cpu"));
  }

  @Test
  void aSampleWithSeveralValuesCountsThemAll() throws Exception {
    // One value per timestamp: 513 timestamps over 85 records, 1,161 samples.
    assertEquals(1161, sum("conv-multi-cpu.otlp", "cpu"));
  }

  // --- the layout -----------------------------------------------------------------------------

  @Test
  void theOlderLayoutIsRefused() {
    IOException e =
        assertThrows(IOException.class, () -> OtlpParser.parse(REAL.resolve("ap41-cpu.otlp")));
    assertTrue(e.getMessage().contains("unsupported OTLP profiles layout"), e.getMessage());
    assertTrue(e.getMessage().contains("v1.7.0"), e.getMessage());
  }

  // --- the compare reader ---------------------------------------------------------------------

  @Test
  void theAgentsCpuProfileCountsItsSamples() throws Exception {
    Path file = REAL.resolve("ap-cpu.otlp");
    assertEquals(
        List.of(new SampleTypeInfo("perf_events", "count", SamplerKind.CPU)),
        new OtlpProfileReader().sampleTypes(file));
    RecordingProfile profile = read("ap-cpu.otlp", null);
    assertEquals("perf_events/count", profile.eventType());
    assertSame(SamplerKind.CPU, profile.samplerKind());
    assertTrue(profile.countsKnown());
    assertEquals(
        "default: the first CPU sample type; counts: the sample type itself",
        profile.sampleTypeReason());
    assertEquals(217, profile.sampleCount());
    assertEquals(217, profile.totalWeight());
    assertEquals(2.018187, profile.durationSeconds(), 1e-9);
    // Dotted (the profile has $$Lambda frames), hidden-class addresses dropped.
    assertEquals(133L, profile.leafFrameCounts().get("RealProf$Hasher.mix"));
    assertTrue(profile.inclusiveFrameCounts().containsKey("java.lang.invoke.LambdaForm$MH.invoke"));
    assertTrue(profile.inclusiveFrameCounts().containsKey("RealProf.main"));
  }

  @Test
  void theAgentsWallAndAllocationProfilesHaveTheirKinds() throws Exception {
    RecordingProfile wall = read("ap-wall.otlp", null);
    assertSame(SamplerKind.WALL, wall.samplerKind());
    assertTrue(wall.countsKnown());
    assertEquals(903, wall.sampleCount());

    RecordingProfile alloc = read("ap-alloc.otlp", null);
    assertSame(SamplerKind.ALLOC_COUNT, alloc.samplerKind());
    assertFalse(alloc.countsKnown(), alloc.sampleTypeReason());
    assertEquals(1405, alloc.totalWeight());
    // The agent marks the allocated class byte[]_[i]; the key drops the mark.
    assertEquals(
        java.util.Set.of("byte[]", "java.lang.String", "java.lang.Object[]"),
        alloc.leafFrameWeights().keySet());
  }

  @Test
  void jfrconvsTimestampOnlyProfileCountsEveryTimestamp() throws Exception {
    RecordingProfile profile = read("conv-cpu.otlp", null);
    assertEquals("cpu/count", profile.eventType());
    assertTrue(profile.countsKnown());
    assertEquals(216, profile.sampleCount());
    assertEquals(133L, profile.leafFrameCounts().get("RealProf$Hasher.mix"));
  }

  @Test
  void jfrconvNamesAWallProfileCpu() throws Exception {
    // As in its pprof output: the reader cannot tell this wall profile from a CPU one.
    RecordingProfile wall = read("conv-multi-wall.otlp", null);
    assertEquals("cpu/count", wall.eventType());
    assertSame(SamplerKind.CPU, wall.samplerKind());
    assertEquals(945, wall.sampleCount());
  }

  @Test
  void aProfileAgainstItselfMovesNothing() throws Exception {
    RecordingProfile cpu = read("ap-cpu.otlp", null);
    CompareResult result = ProfileCompare.compare(cpu, cpu, all(false));
    assertFalse(result.frames().isEmpty());
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(0.0, row.get("deltaPct"), row.toString());
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  @Test
  void twoRunsOfOneProgramJudgeNoChange() throws Exception {
    CompareResult result =
        ProfileCompare.compare(read("ap-cpu.otlp", null), read("ap-cpu-b.otlp", null), all(false));
    assertFalse(result.frames().isEmpty(), "the runs differ by noise, so rows exist");
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  @Test
  void theAgentsCpuAndWallProfilesAreRefusedTogether() throws Exception {
    RecordingProfile cpu = read("ap-cpu.otlp", null);
    RecordingProfile wall = read("ap-wall.otlp", null);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> ProfileCompare.compare(cpu, wall, all(false)));
    assertTrue(
        e.getMessage()
            .startsWith(
                "Different samplers (baseline otlp perf_events/count measures CPU, candidate otlp"
                    + " wall/count measures WALL)"),
        e.getMessage());
    RecordingProfile alloc = read("ap-alloc.otlp", null);
    assertThrows(
        IllegalArgumentException.class, () -> ProfileCompare.compare(cpu, alloc, all(true)));
  }

  // --- helpers --------------------------------------------------------------------------------

  private static RecordingProfile read(String file, String sampleType) throws Exception {
    return new OtlpProfileReader().read(REAL.resolve(file), new ProfileSelection(sampleType, true));
  }

  private static CompareOptions all(boolean allowMixed) {
    return new CompareOptions(
        null,
        0.0,
        100_000,
        true,
        CompareOptions.MixedSamplers.forPair(
            OtlpProfileReader.FORMAT, OtlpProfileReader.FORMAT, allowMixed));
  }

  static List<Map<String, Object>> run(String file, String query) throws Exception {
    try (OtlpSession session = OtlpSession.open(REAL.resolve(file))) {
      return OtlpPathEvaluator.evaluate(session, OtlpPathParser.parse(query));
    }
  }

  private static long sum(String file, String field) throws Exception {
    long total = 0;
    for (Map<String, Object> row : run(file, "samples")) {
      total += ((Number) row.get(field)).longValue();
    }
    return total;
  }

  @SuppressWarnings("unchecked")
  private static List<String> names(Map<String, Object> row) {
    return ((List<Map<String, Object>>) row.get("stackTrace"))
        .stream().map(f -> (String) f.get("name")).toList();
  }
}
