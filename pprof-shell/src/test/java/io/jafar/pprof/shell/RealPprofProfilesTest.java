package io.jafar.pprof.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SampleTypeInfo;
import io.jafar.shell.core.compare.SamplerKind;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reader against pprof files real producers wrote: Go's {@code runtime/pprof} (1.24.7) and
 * async-profiler 4.1's {@code jfrconv}. How each file was made, and its sha256, is in {@code
 * src/test/resources/real/README.md}. The expected numbers were read independently, with {@code
 * github.com/google/pprof/profile} and {@code go tool pprof -raw}.
 */
class RealPprofProfilesTest {

  static final Path REAL = Path.of("src", "test", "resources", "real");

  // --- Go -------------------------------------------------------------------------------------

  @Test
  void goCpuDefaultsToItsSampleCountColumn() throws Exception {
    Path file = REAL.resolve("go-cpu.pb.gz");
    assertEquals(
        List.of("samples/count CPU", "cpu/nanoseconds CPU"), labels(reader().sampleTypes(file)));
    RecordingProfile profile = reader().read(file, new ProfileSelection(null, true));
    // Go writes no default_sample_type; samples/count is CPU through the cpu/nanoseconds period.
    assertEquals("samples/count", profile.eventType());
    assertSame(SamplerKind.CPU, profile.samplerKind());
    assertTrue(profile.countsKnown());
    assertEquals(
        "default: the first CPU sample type; counts: the column itself",
        profile.sampleTypeReason());
    assertEquals(202, profile.sampleCount());
    assertEquals(2.111354140, profile.durationSeconds(), 1e-9);
  }

  @Test
  void goCpuNanosecondsCountTheirSamplesColumn() throws Exception {
    RecordingProfile profile =
        reader().read(REAL.resolve("go-cpu.pb.gz"), new ProfileSelection("cpu/nanoseconds", true));
    assertTrue(profile.countsKnown());
    assertEquals("requested; counts: the samples/count column", profile.sampleTypeReason());
    assertEquals(202, profile.sampleCount());
    assertEquals(2_020_000_000L, profile.totalWeight());
    // Go's cpu value is samples x period exactly, so the shares are the samples column's.
    RecordingProfile samples =
        reader().read(REAL.resolve("go-cpu.pb.gz"), new ProfileSelection(null, true));
    assertEquals(samples.leafFrameCounts(), profile.leafFrameCounts());
  }

  @Test
  void goNamesStayAsStoredAndTheProfileIsNotJvm() throws Exception {
    PprofProfile.Profile parsed = PprofParser.parse(REAL.resolve("go-cpu.pb.gz"));
    assertFalse(PprofProfileReader.looksJvm(parsed));
    RecordingProfile profile =
        reader().read(REAL.resolve("go-cpu.pb.gz"), new ProfileSelection(null, true));
    Map<String, Long> inclusive = profile.inclusiveFrameCounts();
    // A slashed Go name with a Java internal name's shape, a pointer-receiver method, a closure,
    // a generic instantiation: each kept exactly as Go wrote it.
    for (String name :
        List.of(
            "encoding/json.Marshal",
            "example.com/realprof/work.(*Hasher).Sum",
            "example.com/realprof/work.Burn.func1",
            "example.com/realprof/work.Map[go.shape.int,go.shape.*uint8]",
            "main.main")) {
      assertTrue(inclusive.containsKey(name), () -> name + " not in " + inclusive.keySet());
    }
    assertFalse(
        inclusive.keySet().stream().anyMatch(k -> k.startsWith("encoding.json")),
        "a Go name was dotted");
  }

  @Test
  void goInlinedFramesPutTheInnermostFirst() throws Exception {
    // Go writes (*Hasher).Sum inlined into Burn.func1 inlined into Burn as one location with
    // three lines, the innermost first (profile.proto: the last line is the caller). Read that
    // way, Sum is the hottest leaf and the three share one inclusive count.
    RecordingProfile profile =
        reader().read(REAL.resolve("go-cpu.pb.gz"), new ProfileSelection(null, true));
    String hottest =
        Collections.max(profile.leafFrameCounts().entrySet(), Map.Entry.comparingByValue())
            .getKey();
    assertEquals("example.com/realprof/work.(*Hasher).Sum", hottest);
    long sum = profile.inclusiveFrameCounts().get("example.com/realprof/work.(*Hasher).Sum");
    assertEquals(sum, profile.inclusiveFrameCounts().get("example.com/realprof/work.Burn.func1"));
    assertEquals(sum, profile.inclusiveFrameCounts().get("example.com/realprof/work.Burn"));
    assertFalse(profile.leafFrameCounts().containsKey("example.com/realprof/work.Burn"));
  }

  @Test
  void goHeapHasNoSampleCountsForAnyColumn() throws Exception {
    Path file = REAL.resolve("go-heap.pb.gz");
    assertEquals(
        List.of(
            "alloc_objects/count ALLOC_COUNT",
            "alloc_space/bytes ALLOC_BYTES",
            "inuse_objects/count ALLOC_COUNT",
            "inuse_space/bytes ALLOC_BYTES"),
        labels(reader().sampleTypes(file)));
    for (String type : new String[] {null, "alloc_space", "inuse_objects", "inuse_space"}) {
      RecordingProfile profile = reader().read(file, new ProfileSelection(type, true));
      assertFalse(profile.countsKnown(), () -> type + ": " + profile.sampleTypeReason());
      assertEquals(0.0, profile.durationSeconds(), "Go writes no duration for a heap profile");
    }
    RecordingProfile defaulted = reader().read(file, new ProfileSelection(null, false));
    assertEquals("alloc_objects/count", defaulted.eventType());
    // The profile has a count column and a period; the reason says why neither counts samples.
    assertEquals(
        "default: no CPU sample type, so the first sample type; observation counts unknown:"
            + " alloc_objects/count is an allocation measure, which producers scale up from the"
            + " sampled events (so its period does not give counts either), and no column counts"
            + " the samples",
        defaulted.sampleTypeReason());
  }

  @Test
  void goMutexContentionsAreScaledSoTheyAreNotSampleCounts() throws Exception {
    // Recorded at SetMutexProfileFraction(5): Go scales each sampled contention by 5 (1,430 is
    // 286 sampled events) and writes period 1 whatever the fraction, so value / period is not a
    // count of samples.
    for (String name : List.of("go-mutex.pb.gz", "go-block.pb.gz")) {
      Path file = REAL.resolve(name);
      assertEquals(
          List.of("contentions/count LOCK", "delay/nanoseconds LOCK"),
          labels(reader().sampleTypes(file)));
      for (String type : new String[] {null, "delay"}) {
        RecordingProfile profile = reader().read(file, new ProfileSelection(type, false));
        assertFalse(
            profile.countsKnown(), () -> name + " " + type + ": " + profile.sampleTypeReason());
      }
    }
    RecordingProfile mutex =
        reader().read(REAL.resolve("go-mutex.pb.gz"), new ProfileSelection(null, false));
    assertEquals(1_430, mutex.totalWeight());
  }

  // --- async-profiler -------------------------------------------------------------------------

  @Test
  void asyncProfilerCpuCountsItsOwnColumnAndIsDotted() throws Exception {
    Path file = REAL.resolve("ap-cpu.pb.gz");
    assertEquals(List.of("cpu/count CPU"), labels(reader().sampleTypes(file)));
    PprofProfile.Profile parsed = PprofParser.parse(file);
    assertEquals(0, parsed.period(), "jfrconv writes no period");
    assertTrue(PprofProfileReader.looksJvm(parsed));
    RecordingProfile profile = reader().read(file, new ProfileSelection(null, true));
    assertTrue(profile.countsKnown());
    assertEquals(215, profile.sampleCount());
    Map<String, Long> inclusive = profile.inclusiveFrameCounts();
    for (String name :
        List.of(
            "RealProf$Hasher.sum",
            "RealProf.lambda$burn$",
            // RealProf$$Lambda.0x<address>.applyAsInt, without the per-run address
            "RealProf$$Lambda.applyAsInt",
            "java.lang.String.repeat",
            "Compile::Compile")) {
      assertTrue(inclusive.containsKey(name), () -> name + " not in " + inclusive.keySet());
    }
    assertFalse(
        inclusive.keySet().stream().anyMatch(k -> k.startsWith("java/")),
        "a slashed Java name survived");
  }

  @Test
  void asyncProfilerUncompressedOutputReadsTheSame() throws Exception {
    // jfrconv -o pprof writes the protobuf without gzip.
    RecordingProfile gz =
        reader().read(REAL.resolve("ap-cpu.pb.gz"), new ProfileSelection(null, true));
    RecordingProfile raw =
        reader().read(REAL.resolve("ap-cpu.pprof"), new ProfileSelection(null, true));
    assertEquals(gz.sampleCount(), raw.sampleCount());
    assertEquals(gz.inclusiveFrameCounts(), raw.inclusiveFrameCounts());
  }

  @Test
  void asyncProfilerWallIsLabelledCpu() throws Exception {
    // A producer fact the reader cannot see past: jfrconv --wall names its column cpu/count, so a
    // wall profile reads as CPU. Pinned so a change in either is noticed.
    RecordingProfile wall =
        reader().read(REAL.resolve("ap-wall.pb.gz"), new ProfileSelection(null, false));
    assertEquals("cpu/count", wall.eventType());
    assertSame(SamplerKind.CPU, wall.samplerKind());
    assertEquals(945, wall.sampleCount());
  }

  @Test
  void asyncProfilerAllocationIsAnAllocationProfile() throws Exception {
    RecordingProfile count =
        reader().read(REAL.resolve("ap-alloc.pb.gz"), new ProfileSelection(null, false));
    assertEquals("allocations/count", count.eventType());
    assertSame(SamplerKind.ALLOC_COUNT, count.samplerKind());
    assertFalse(count.countsKnown(), count.sampleTypeReason());
    assertEquals(1_430, count.totalWeight());
    // The leaf of an allocation stack is the allocated class, not a method.
    assertTrue(count.leafFrameCounts().containsKey("byte[]"), count.leafFrameCounts().toString());

    RecordingProfile bytes =
        reader().read(REAL.resolve("ap-alloc-total.pb.gz"), new ProfileSelection(null, false));
    assertEquals("allocations/bytes", bytes.eventType());
    assertSame(SamplerKind.ALLOC_BYTES, bytes.samplerKind());
    assertFalse(bytes.countsKnown());
  }

  // ---------------------------------------------------------------------------------------------

  private static PprofProfileReader reader() {
    return new PprofProfileReader();
  }

  private static List<String> labels(List<SampleTypeInfo> types) {
    return types.stream().map(t -> t.label() + " " + t.kind()).toList();
  }
}
