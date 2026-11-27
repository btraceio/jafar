package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.pprof.shell.PprofProfileReader;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.JfrCompare;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Comparing profiles real producers wrote, not built ones: Go 1.24.7's {@code runtime/pprof} and
 * async-profiler 4.1 (its JFR and {@code jfrconv}'s pprof conversion of it). The files and how they
 * were made are in {@code pprof-shell/src/test/resources/real/README.md}.
 */
class RealProfilesCompareTest {

  private static final Path REAL =
      Path.of("..", "pprof-shell", "src", "test", "resources", "real").toAbsolutePath().normalize();

  /** Every frame a row, so nothing is hidden by the floor. */
  private static final CompareOptions ALL =
      new CompareOptions(null, 0.0, 100_000, true, CompareOptions.MixedSamplers.REFUSE);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream originalOut;
  private PrintStream originalErr;

  @BeforeEach
  void captureStreams() {
    originalOut = System.out;
    originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  void aGoCpuProfileAgainstItselfMovesNothing() throws Exception {
    RecordingProfile go = pprof("go-cpu.pb.gz", null);
    assertEquals("samples/count", go.eventType());
    assertTrue(go.countsKnown());
    CompareResult result = ProfileCompare.compare(go, go, ALL);
    assertFalse(result.inclusiveFrames().isEmpty());
    assertNothingMoved(result);
    assertEquals(go.inclusiveFrameCounts().size(), result.inclusiveFrames().size());
  }

  @Test
  void twoRunsOfOneGoProgramJudgeNoChange() throws Exception {
    // An A/A pair from two runs: real noise, ~200 samples each, and no change to find.
    for (String type : new String[] {null, "cpu/nanoseconds"}) {
      CompareResult result =
          ProfileCompare.compare(pprof("go-cpu.pb.gz", type), pprof("go-cpu-b.pb.gz", type), ALL);
      assertNoneSignificant(result);
      assertFalse(result.frames().isEmpty(), "the runs differ by noise, so rows exist");
    }
  }

  @Test
  void aGoHeapProfileIsComparedButNotJudged() throws Exception {
    RecordingProfile heap = pprof("go-heap.pb.gz", "inuse_space");
    RecordingProfile alloc = pprof("go-heap.pb.gz", "alloc_space");
    // Two different allocation measures of one program: large share moves, none judged.
    CompareResult result = ProfileCompare.compare(alloc, heap, ALL);
    assertFalse(result.frames().isEmpty());
    assertNoneSignificant(result);
    assertTrue(
        result.comparability().stream()
            .anyMatch(
                n ->
                    n.startsWith("Observation counts unknown for the baseline (pprof alloc_space")
                        && n.contains("neither a column nor the period gives")),
        result.comparability().toString());
  }

  @Test
  void goMutexContentionsAreNotJudgedAsSampleCounts() throws Exception {
    RecordingProfile mutex = pprof("go-mutex.pb.gz", null);
    RecordingProfile block = pprof("go-block.pb.gz", null);
    assertFalse(mutex.countsKnown(), mutex.sampleTypeReason());
    CompareResult result = ProfileCompare.compare(mutex, block, ALL);
    assertNoneSignificant(result);
  }

  @Test
  void anAsyncProfilerRecordingAndItsPprofConversionHaveTheSameFrames() throws Exception {
    RecordingProfile jfr =
        new JfrCompare()
            .read(REAL.resolve("ap-cpu.jfr"), new ProfileSelection("jdk.ExecutionSample", true));
    RecordingProfile converted = pprof("ap-cpu.pb.gz", null);
    assertEquals(215, jfr.sampleCount());
    assertEquals(jfr.sampleCount(), converted.sampleCount());
    assertTrue(jfr.inclusiveFrameCounts().containsKey("RealProf$Hasher.sum"));
    assertTrue(jfr.inclusiveFrameCounts().containsKey("java.lang.String.repeat"));

    // Every frame carries the same key and count on both sides: Java frames dotted, with lambda
    // addresses dropped, and native, C++ and kernel frames by their symbol alone. async-profiler's
    // JFR gives a C++ frame its library as the class (libjvm.so); JfrCompare drops it for those
    // frame types, as the pprof conversion (and any other pprof producer) does.
    assertEquals(converted.inclusiveFrameCounts(), jfr.inclusiveFrameCounts());
    assertEquals(converted.leafFrameCounts(), jfr.leafFrameCounts());
    assertFalse(
        jfr.inclusiveFrameCounts().keySet().stream().anyMatch(k -> k.startsWith("libjvm.so.")),
        "a C++ frame keyed with its library");
  }

  @Test
  void anAsyncProfilerAllocationProfileIsRefusedAgainstACpuProfile() {
    assertEquals(
        1,
        compare(REAL.resolve("go-cpu.pb.gz").toString(), REAL.resolve("ap-alloc.pb.gz").toString()),
        this::streams);
    String error = err.toString(StandardCharsets.UTF_8);
    assertTrue(error.contains("Cannot compare an allocation profile with a time profile"), error);
    assertTrue(error.contains("pprof allocations/count measures ALLOC_COUNT"), error);
  }

  @Test
  void listSampleTypesNamesTheRealProfilesTypes() {
    assertEquals(
        0,
        compare(
            REAL.resolve("go-heap.pb.gz").toString(),
            REAL.resolve("ap-alloc.pb.gz").toString(),
            "--list-sample-types"),
        this::streams);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.matches("(?s).*inuse_space/bytes\\s+ALLOC_BYTES.*"), text);
    assertTrue(text.matches("(?s).*allocations/count\\s+ALLOC_COUNT.*"), text);
  }

  // ---------------------------------------------------------------------------------------------

  private static RecordingProfile pprof(String name, String sampleType) throws Exception {
    return new PprofProfileReader()
        .read(REAL.resolve(name), new ProfileSelection(sampleType, true));
  }

  private static void assertNothingMoved(CompareResult result) {
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(0.0, row.get("deltaPct"), row.toString());
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  private static void assertNoneSignificant(CompareResult result) {
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  private int compare(String... args) {
    List<String> argv = new ArrayList<>(List.of("compare"));
    argv.addAll(List.of(args));
    return new CommandLine(new io.jafar.shell.Main()).execute(argv.toArray(String[]::new));
  }

  private String streams() {
    return "stdout:\n"
        + out.toString(StandardCharsets.UTF_8)
        + "\nstderr:\n"
        + err.toString(StandardCharsets.UTF_8);
  }
}
