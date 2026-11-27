package io.jafar.otlp.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.otlp.MinimalOtlpBuilder;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SampleTypeInfo;
import io.jafar.shell.core.compare.SamplerKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What an OTLP file contributes to a comparison: weights, counts, frames and sample type. */
class OtlpProfileReaderTest {

  @TempDir Path dir;

  private static final long MS10 = 10_000_000L;

  @Test
  void theDefaultSampleTypeIsTheFirstCpuProfile() throws Exception {
    // An allocation profile first, a CPU one second, over one dictionary.
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("alloc_space"), b.addString("bytes"));
    int stack = stack(b, "m.Leaf.work");
    b.addSample(stack, List.of(), List.of(4096L));
    b.addProfile(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(stack, List.of(), List.of(3 * MS10));
    Path file = b.write(dir);

    assertEquals(
        List.of(
            new SampleTypeInfo("alloc_space", "bytes", SamplerKind.ALLOC_BYTES),
            new SampleTypeInfo("cpu", "nanoseconds", SamplerKind.CPU)),
        new OtlpProfileReader().sampleTypes(file));
    RecordingProfile profile = read(file, null, false);
    assertEquals("cpu/nanoseconds", profile.eventType());
    assertEquals("otlp", profile.format());
    assertSame(SamplerKind.CPU, profile.samplerKind());
    assertTrue(profile.sampleTypeReason().startsWith("default: the first CPU"), profile.toString());
    assertEquals(3 * MS10, profile.totalWeight());
    assertFalse(profile.truncationKnown());

    RecordingProfile alloc = read(file, "alloc_space/bytes", false);
    assertSame(SamplerKind.ALLOC_BYTES, alloc.samplerKind());
    assertEquals(4096L, alloc.totalWeight());
    assertFalse(alloc.countsKnown());
    assertEquals(
        "requested; observation counts unknown: alloc_space/bytes is an allocation measure, which"
            + " producers scale up from the sampled events (so its period does not give counts"
            + " either)",
        alloc.sampleTypeReason());
  }

  @Test
  void withoutACpuTypeTheDefaultIsTheFirstAndSaysSo() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("wall"), b.addString("count"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(2L));
    RecordingProfile profile = read(b.write(dir), null, false);
    assertSame(SamplerKind.WALL, profile.samplerKind());
    assertTrue(profile.sampleTypeReason().startsWith("default: no CPU sample type"));
  }

  @Test
  void anUnknownSampleTypeNamesTheOnesThereAre() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(MS10));
    Path file = b.write(dir);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> read(file, "wall", false));
    assertTrue(
        e.getMessage().endsWith("has no sample type wall; it has cpu/nanoseconds"), e.getMessage());
  }

  @Test
  void aCountSampleTypeCountsItsValuesNotItsRecords() throws Exception {
    // Two records standing for 7 and 3 samples, as async-profiler's agent aggregates equal stacks.
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("wall"), b.addString("count"));
    int a = location(b, "m.A.a");
    int main = location(b, "m.Main.main");
    b.addSample(b.addStack(List.of(a, main)), List.of(), List.of(7L));
    b.addSample(b.addStack(List.of(main)), List.of(), List.of(3L));
    RecordingProfile profile = read(b.write(dir), null, true);
    assertTrue(profile.countsKnown());
    assertEquals(10, profile.sampleCount());
    assertEquals(Map.of("m.A.a", 7L, "m.Main.main", 3L), profile.leafFrameCounts());
    assertEquals(Map.of("m.A.a", 7L, "m.Main.main", 10L), profile.inclusiveFrameCounts());
    assertTrue(profile.sampleTypeReason().endsWith("counts: the sample type itself"));
  }

  @Test
  void timestampsAreTheObservationsOfATimeSampleType() throws Exception {
    // cpu/nanoseconds with a timestamp per sample: the values weigh, the timestamps count.
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(MS10, 2 * MS10), List.of(1L, 2L));
    b.addSample(stack(b, "m.B.b"), List.of(), List.of(MS10), List.of(3L));
    RecordingProfile profile = read(b.write(dir), null, false);
    assertTrue(profile.countsKnown());
    assertEquals(3, profile.sampleCount());
    assertEquals(4 * MS10, profile.totalWeight());
    assertEquals(Map.of("m.A.a", 2L, "m.B.b", 1L), profile.leafFrameCounts());
    assertEquals(Map.of("m.A.a", 3 * MS10, "m.B.b", MS10), profile.leafFrameWeights());
    assertTrue(profile.sampleTypeReason().endsWith("counts: one per sample timestamp"));
  }

  @Test
  void aSampleWithOnlyTimestampsCountsOnePerTimestamp() throws Exception {
    // What jfrconv -o otlp writes for a single-event recording: no values at all.
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("count"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(), List.of(1L, 2L, 3L));
    RecordingProfile profile = read(b.write(dir), null, false);
    assertEquals(3, profile.sampleCount());
    assertEquals(3, profile.totalWeight());
  }

  @Test
  void countsComeFromThePeriodWhenItHasTheSampleTypesUnit() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.setPeriod(b.addString("cpu"), b.addString("nanoseconds"), MS10);
    // 0.4 periods each: rounding per sample would make 5 of them none; once at the aggregate, 2.
    int s = stack(b, "m.A.a");
    for (int i = 0; i < 5; i++) {
      b.addSample(s, List.of(), List.of(4_000_000L));
    }
    RecordingProfile profile = read(b.write(dir), null, false);
    assertTrue(profile.countsKnown());
    assertEquals(2, profile.sampleCount());
    assertEquals(Map.of("m.A.a", 2L), profile.leafFrameCounts());
    assertTrue(profile.sampleTypeReason().endsWith("counts: value / period 10000000"));
  }

  @Test
  void nanosecondsWithoutTimestampsOrAPeriodHaveUnknownCounts() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(3 * MS10));
    b.addSample(stack(b, "m.B.b"), List.of(), List.of(MS10));
    RecordingProfile profile = read(b.write(dir), null, false);
    assertFalse(profile.countsKnown());
    assertEquals(2, profile.sampleCount(), "the number of sample records");
    assertEquals(4 * MS10, profile.totalWeight());
  }

  @Test
  void profilesOfOneSampleTypeAreReadTogether() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int cpu = b.addString("cpu");
    int count = b.addString("count");
    b.setSampleType(cpu, count);
    b.setTimeUnixNano(1_000_000_000L).setDurationNanos(2_000_000_000L);
    int s = stack(b, "m.A.a");
    b.addSample(s, List.of(), List.of(4L));
    b.addProfile(cpu, count);
    b.setTimeUnixNano(4_000_000_000L).setDurationNanos(1_000_000_000L);
    b.addSample(s, List.of(), List.of(6L));
    RecordingProfile profile = read(b.write(dir), null, false);
    assertEquals(10, profile.sampleCount());
    assertEquals(4.0, profile.durationSeconds(), 1e-9, "from the first start to the last end");
    assertTrue(
        profile.sampleTypeReason().contains("2 profiles of cpu/count read together"),
        profile.sampleTypeReason());
  }

  @Test
  void theZeroStackIsStillASample() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("count"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(1L));
    b.addSample(0, List.of(), List.of(1L));
    RecordingProfile profile = read(b.write(dir), null, true);
    assertEquals(2, profile.sampleCount());
    assertEquals(Map.of("m.A.a", 1L), profile.leafFrameCounts());
  }

  @Test
  void javaInternalNamesAreDottedOnlyInAJvmProfile() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("count"));
    int init = location(b, "java/lang/String.<init>");
    int hidden = location(b, "java/lang/invoke/LambdaForm$MH.0x00007f324c002c00.invoke");
    b.addSample(b.addStack(List.of(hidden, init)), List.of(), List.of(1L));
    b.addSample(stack(b, "byte[]_[i]"), List.of(), List.of(1L));
    RecordingProfile jvm = read(b.write(dir), null, true);
    assertEquals(
        Map.of("java.lang.invoke.LambdaForm$MH.invoke", 1L, "byte[]", 1L), jvm.leafFrameCounts());

    MinimalOtlpBuilder go = new MinimalOtlpBuilder();
    go.setSampleType(go.addString("cpu"), go.addString("count"));
    go.addSample(stack(go, "encoding/json.Marshal"), List.of(), List.of(1L));
    assertEquals(
        Map.of("encoding/json.Marshal", 1L), read(go.write(dir), null, false).leafFrameCounts());
  }

  @Test
  void anOtlpProfileComparedWithItselfFindsNothing() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("count"));
    b.addSample(stack(b, "m.A.a"), List.of(), List.of(60L));
    b.addSample(stack(b, "m.B.b"), List.of(), List.of(40L));
    RecordingProfile profile = read(b.write(dir), null, true);
    CompareResult result = ProfileCompare.compare(profile, profile, options(false));
    // At a floor of 0 every frame is a row, each unchanged and none significant.
    assertEquals(2, result.frames().size());
    for (Map<String, Object> row : result.frames()) {
      assertEquals(0.0, ((Number) row.get("deltaPct")).doubleValue(), 1e-12, row.toString());
      assertEquals(false, row.get("significant"), row.toString());
    }
  }

  @Test
  void cpuAgainstWallIsRefusedUnlessAllowed() throws Exception {
    RecordingProfile cpu = single("cpu", "count");
    RecordingProfile wall = single("wall", "count");
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProfileCompare.compare(cpu, wall, options(false)));
    assertTrue(e.getMessage().startsWith("Different samplers"), e.getMessage());
    CompareResult allowed = ProfileCompare.compare(cpu, wall, options(true));
    assertTrue(allowed.frames().stream().noneMatch(r -> Boolean.TRUE.equals(r.get("significant"))));
  }

  @Test
  void allocationAgainstCpuIsAlwaysRefused() throws Exception {
    RecordingProfile cpu = single("cpu", "count");
    RecordingProfile alloc = single("object_sampler", "count");
    assertSame(SamplerKind.ALLOC_COUNT, alloc.samplerKind());
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProfileCompare.compare(cpu, alloc, options(true)));
    assertTrue(e.getMessage().startsWith("Cannot compare an allocation profile"), e.getMessage());
  }

  @Test
  void theModuleOffersThisReader() {
    assertInstanceOf(OtlpProfileReader.class, new OtlpModule().profileReader());
    assertEquals("otlp", new OtlpModule().profileReader().format());
  }

  // --- helpers --------------------------------------------------------------------------------

  private RecordingProfile single(String type, String unit) throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString(type), b.addString(unit));
    for (int i = 0; i < 40; i++) {
      b.addSample(stack(b, "m.F" + (i % 4) + ".f"), List.of(), List.of(1L + i % 3));
    }
    return read(b.write(dir), null, true);
  }

  private static CompareOptions options(boolean allowMixed) {
    return new CompareOptions(
        null,
        0.0,
        25,
        true,
        CompareOptions.MixedSamplers.forPair(
            OtlpProfileReader.FORMAT, OtlpProfileReader.FORMAT, allowMixed));
  }

  private static RecordingProfile read(Path file, String type, boolean inclusive) throws Exception {
    return new OtlpProfileReader().read(file, new ProfileSelection(type, inclusive));
  }

  private static int location(MinimalOtlpBuilder b, String name) {
    return b.addLocation(b.addFunction(b.addString(name), 0), 0);
  }

  private static int stack(MinimalOtlpBuilder b, String name) {
    return b.addStack(List.of(location(b, name)));
  }
}
