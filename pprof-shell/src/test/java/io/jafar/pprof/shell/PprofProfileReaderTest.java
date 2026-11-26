package io.jafar.pprof.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.pprof.api.PprofProfile;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SamplerKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a pprof profile contributes to a comparison: weights, counts, frames and sample type. */
class PprofProfileReaderTest {

  @TempDir Path dir;

  private static final long MS10 = 10_000_000L;

  @Test
  void theDefaultSampleTypeIsTheFirstCpuOne() throws Exception {
    // alloc first, cpu second: the default skips the first column for the CPU one.
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("alloc_space"), b.addString("bytes"));
    b.addSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    long leaf = location(b, "m.Leaf.work");
    b.addSample(List.of(leaf), List.of(4096L, 3 * MS10), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertEquals("cpu/nanoseconds", profile.eventType());
    assertSame(SamplerKind.CPU, profile.samplerKind());
    assertTrue(profile.sampleTypeReason().startsWith("default: the first CPU"), profile.toString());
    assertEquals(3 * MS10, profile.totalWeight());

    RecordingProfile alloc =
        new PprofProfileReader().read(b.write(dir), selection("alloc_space/bytes"));
    assertSame(SamplerKind.ALLOC_BYTES, alloc.samplerKind());
    assertEquals(4096L, alloc.totalWeight());
    assertEquals(
        "requested; observation counts unknown: no sample-count column for alloc_space/bytes, and"
            + " no period to divide by",
        alloc.sampleTypeReason());
  }

  @Test
  void withoutACpuTypeTheDefaultIsTheFirstAndSaysSo() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("wall"), b.addString("nanoseconds"));
    b.addSample(List.of(location(b, "m.A.a")), List.of(MS10), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertEquals("wall/nanoseconds", profile.eventType());
    assertSame(SamplerKind.WALL, profile.samplerKind());
    assertTrue(profile.sampleTypeReason().startsWith("default: no CPU sample type"));
  }

  @Test
  void countsComeFromACountColumnAndAreSummedNotCountedAsSamples() throws Exception {
    // Two Sample messages standing for 7 and 3 observations, as a writer merges equal stacks.
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    long a = location(b, "m.A.a");
    long main = location(b, "m.Main.main");
    b.addSample(List.of(a, main), List.of(7L, 7 * MS10), List.of());
    b.addSample(List.of(main), List.of(3L, 3 * MS10), List.of());
    RecordingProfile profile =
        new PprofProfileReader().read(b.write(dir), selection("cpu/nanoseconds", true));
    assertTrue(profile.countsKnown());
    assertEquals(10, profile.sampleCount());
    assertEquals(10 * MS10, profile.totalWeight());
    assertEquals(Map.of("m.A.a", 7L, "m.Main.main", 3L), profile.leafFrameCounts());
    assertEquals(Map.of("m.A.a", 7L, "m.Main.main", 10L), profile.inclusiveFrameCounts());
    assertEquals(Map.of("m.A.a", 7 * MS10, "m.Main.main", 3 * MS10), profile.leafFrameWeights());
    assertFalse(profile.truncationKnown());
    assertEquals(0.0, profile.durationSeconds());
  }

  @Test
  void countsComeFromThePeriodWhenItHasTheSampleTypesUnit() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    b.setPeriod(cpu, ns, MS10);
    b.setDurationNanos(30_000_000_000L);
    b.addSample(List.of(location(b, "m.A.a")), List.of(40 * MS10), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertTrue(profile.countsKnown());
    assertEquals(40, profile.sampleCount());
    assertEquals(Map.of("m.A.a", 40L), profile.leafFrameCounts());
    assertEquals(30.0, profile.durationSeconds());
  }

  @Test
  void nanosecondsWithoutACountOrAPeriodHaveUnknownCounts() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(List.of(location(b, "m.A.a")), List.of(40 * MS10), List.of());
    b.addSample(List.of(location(b, "m.B.b")), List.of(10 * MS10), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertFalse(profile.countsKnown());
    // Records, not observations: one per Sample message.
    assertEquals(2, profile.sampleCount());
    assertEquals(80.0, profile.selfPct("m.A.a"));
  }

  @Test
  void anEmptyStackIsStillASample() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSample(List.of(location(b, "m.A.a")), List.of(3L), List.of());
    b.addSample(List.of(), List.of(1L), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertEquals(4, profile.sampleCount());
    assertEquals(75.0, profile.selfPct("m.A.a"));
  }

  @Test
  void javaInternalNamesAreDottedAndOthersAreNot() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSample(
        List.of(
            location(b, "java/lang/String.indexOf"),
            location(b, "github.com/x/y.Func"),
            location(b, "java/lang/invoke/LambdaForm$MH/0x00007f2e141a1000.invoke")),
        List.of(1L),
        List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null, true));
    assertEquals(
        Map.of(
            "java.lang.String.indexOf", 1L,
            "github.com/x/y.Func", 1L,
            "java.lang.invoke.LambdaForm$MH.invoke", 1L),
        profile.inclusiveFrameCounts());
  }

  @Test
  void eachInlinedLineIsAFrameAndTheFirstIsTheLeaf() {
    PprofProfile.Profile profile =
        new PprofProfile.Profile(
            List.of(new PprofProfile.ValueType("samples", "count")),
            List.of(new PprofProfile.Sample(new long[] {1, 2}, new long[] {5}, List.of())),
            List.of(),
            List.of(
                new PprofProfile.Location(
                    1,
                    0,
                    0,
                    List.of(new PprofProfile.Line(10, 1), new PprofProfile.Line(11, 2)),
                    false),
                new PprofProfile.Location(2, 0, 0, List.of(new PprofProfile.Line(12, 3)), false)),
            List.of(
                new PprofProfile.Function(10, "inlined", "", "", 0),
                new PprofProfile.Function(11, "", "caller_sys", "", 0),
                new PprofProfile.Function(12, "root", "", "", 0)),
            List.of(),
            0,
            null,
            0,
            0);
    RecordingProfile read = PprofProfileReader.read(profile, null, selection(null, true));
    assertEquals(Map.of("inlined", 5L), read.leafFrameCounts());
    assertEquals(Map.of("inlined", 5L, "caller_sys", 5L, "root", 5L), read.inclusiveFrameCounts());
    assertNull(read.recordingPath());
  }

  @Test
  void anUnknownSampleTypeNamesTheOnesThereAre() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    b.addSample(List.of(location(b, "m.A.a")), List.of(1L), List.of());
    Path file = b.write(dir);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PprofProfileReader().read(file, selection("wall")));
    assertTrue(
        e.getMessage().endsWith("has no sample type wall; it has cpu/nanoseconds"), e.getMessage());
    assertEquals("cpu/nanoseconds", new PprofProfileReader().sampleTypes(file).get(0).label());
  }

  @Test
  void aGoHeapProfileHasNoTrialCounts() throws Exception {
    // Go's heap profile: object counts are estimates scaled up from the sampled allocations, and
    // the period is a byte sampling rate. None of it is a count of trials.
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("alloc_objects"), b.addString("count"));
    b.addSampleType(b.addString("alloc_space"), b.addString("bytes"));
    b.addSampleType(b.addString("inuse_objects"), b.addString("count"));
    b.addSampleType(b.addString("inuse_space"), b.addString("bytes"));
    b.setPeriod(b.addString("space"), b.addString("bytes"), 524_288);
    b.addSample(List.of(location(b, "main.alloc")), List.of(8L, 524_288L, 4L, 262_144L), List.of());
    Path file = b.write(dir);
    for (String type :
        List.of("alloc_objects", "alloc_space", "inuse_objects", "inuse_space", "")) {
      RecordingProfile profile =
          new PprofProfileReader().read(file, selection(type.isEmpty() ? null : type));
      assertFalse(profile.countsKnown(), () -> type + ": " + profile.sampleTypeReason());
    }
  }

  @Test
  void aTimeColumnTakesItsCountsFromItsOwnSamplesColumn() throws Exception {
    // cpu-time pairs with cpu-samples and wall-time with wall-samples: not with the first count
    // column of the profile.
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("cpu-samples"), b.addString("count"));
    b.addSampleType(b.addString("cpu-time"), b.addString("nanoseconds"));
    b.addSampleType(b.addString("wall-samples"), b.addString("count"));
    b.addSampleType(b.addString("wall-time"), b.addString("nanoseconds"));
    b.addSample(List.of(location(b, "m.A.a")), List.of(2L, 2 * MS10, 50L, 50 * MS10), List.of());
    Path file = b.write(dir);
    RecordingProfile wall = new PprofProfileReader().read(file, selection("wall-time"));
    assertEquals(50, wall.sampleCount(), wall.sampleTypeReason());
    assertSame(SamplerKind.WALL, wall.samplerKind());
    RecordingProfile cpu = new PprofProfileReader().read(file, selection("cpu-time"));
    assertEquals(2, cpu.sampleCount(), cpu.sampleTypeReason());
    assertSame(SamplerKind.CPU, cpu.samplerKind());
  }

  @Test
  void periodCountsAreDividedOnceAtTheAggregate() throws Exception {
    // Ten 3 ms samples at a 10 ms period are 3 samples' worth, not ten times round(0.3) = 0.
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    b.setPeriod(cpu, ns, MS10);
    long a = location(b, "m.A.a");
    for (int i = 0; i < 10; i++) {
      b.addSample(List.of(a), List.of(3_000_000L), List.of());
    }
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null));
    assertTrue(profile.countsKnown());
    assertEquals(3, profile.sampleCount());
    assertEquals(Map.of("m.A.a", 3L), profile.leafFrameCounts());
  }

  @Test
  void aGoProfileKeepsItsSlashedNames() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    int go = b.addString("encode.go");
    long marshal = b.addLocation(b.addFunction(b.addString("encoding/json.Marshal"), go), 1);
    long main = b.addLocation(b.addFunction(b.addString("main.main"), go), 1);
    b.addSample(List.of(marshal, main), List.of(1L), List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null, true));
    assertEquals(
        Map.of("encoding/json.Marshal", 1L, "main.main", 1L), profile.inclusiveFrameCounts());
  }

  @Test
  void aLineNumberSuffixIsDroppedFromTheKey() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSample(
        List.of(
            location(b, "java/lang/String.indexOf:42"), location(b, "java/lang/String.<init>:7")),
        List.of(1L),
        List.of());
    RecordingProfile profile = new PprofProfileReader().read(b.write(dir), selection(null, true));
    assertEquals(
        Map.of("java.lang.String.indexOf", 1L, "java.lang.String.<init>", 1L),
        profile.inclusiveFrameCounts());
  }

  @Test
  void theModuleOffersThisReader() {
    assertTrue(new PprofModule().profileReader() instanceof PprofProfileReader);
    assertEquals("pprof", new PprofModule().profileReader().format());
  }

  // ---------------------------------------------------------------------------------------------

  private static ProfileSelection selection(String sampleType) {
    return selection(sampleType, false);
  }

  private static ProfileSelection selection(String sampleType, boolean inclusive) {
    return new ProfileSelection(sampleType, inclusive);
  }

  private static long location(MinimalPprofBuilder b, String function) {
    return b.addLocation(b.addFunction(b.addString(function), b.addString("x.java")), 1);
  }
}
