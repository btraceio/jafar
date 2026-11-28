package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.jfr2pprof.config.MappingLoader;
import io.jafar.jfr2pprof.convert.Jfr2PprofConverter;
import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.pprof.shell.PprofProfileReader;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.JfrCompare;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Comparing across formats: a JFR recording against its own jfr2pprof conversion, which must read
 * as the same profile, and the sampler policy on pprof profiles, through {@code compare} as the
 * unified shell routes it (to jfr-shell's command, with this module's pprof reader on the class
 * path).
 */
class CrossFormatCompareTest {

  /** Checked in: 14,202 {@code datadog.ExecutionSample} events. */
  private static final Path TCK =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  /** One count per event, the default frame format: the conversion the doc's round trip names. */
  private static final String MAPPING =
      """
      profiles:
        - event: datadog.ExecutionSample
          stackField: stackTrace
          values:
            - name: samples
              unit: count
              field: "@count"
      """;

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
  void aRecordingAndItsPprofConversionAreTheSameProfile(@TempDir Path dir) throws Exception {
    Path pprof = convert(dir, MAPPING);
    RecordingProfile jfr =
        new JfrCompare().read(TCK, new ProfileSelection("datadog.ExecutionSample", true));
    RecordingProfile converted =
        new PprofProfileReader().read(pprof, new ProfileSelection(null, true));

    // Not vacuous: thousands of samples and hundreds of frames, dotted on both sides.
    assertTrue(jfr.sampleCount() > 10_000, () -> "samples: " + jfr.sampleCount());
    assertTrue(jfr.inclusiveFrameCounts().size() > 100, "frames: " + jfr.inclusiveFrameCounts());
    assertTrue(
        jfr.inclusiveFrameCounts().keySet().stream().anyMatch(k -> k.startsWith("java.lang.")),
        "no dotted java.lang frame: " + jfr.inclusiveFrameCounts().keySet());
    assertFalse(
        jfr.inclusiveFrameCounts().keySet().stream().anyMatch(k -> k.startsWith("java/")),
        "a slashed class name survived");

    assertEquals("pprof", converted.format());
    assertEquals("samples/count", converted.eventType());
    assertTrue(converted.countsKnown(), "a count column gives observation counts");
    // The conversion merges samples with one stack into one Sample: the reader sums the counts.
    assertEquals(jfr.sampleCount(), converted.sampleCount());
    assertEquals(jfr.sampleCount(), converted.totalWeight());
    assertEquals(
        jfr.leafFrameCounts(),
        converted.leafFrameCounts(),
        () -> difference(jfr.leafFrameCounts(), converted.leafFrameCounts()));
    assertEquals(
        jfr.inclusiveFrameCounts(),
        converted.inclusiveFrameCounts(),
        () -> difference(jfr.inclusiveFrameCounts(), converted.inclusiveFrameCounts()));
    // pprof's duration is the chunks' span, JFR's the samples' span.
    assertNotEquals(jfr.durationSeconds(), converted.durationSeconds());

    CompareResult result =
        ProfileCompare.compare(
            jfr,
            converted,
            new CompareOptions(null, 0.0, 100_000, true, CompareOptions.MixedSamplers.REFUSE));
    assertFalse(result.frames().isEmpty(), "at a floor of 0 every frame is a row");
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(0.0, row.get("deltaPct"), row.toString());
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
    assertEquals(jfr.leafFrameCounts().size(), result.frames().size());
    assertEquals(jfr.inclusiveFrameCounts().size(), result.inclusiveFrames().size());
    assertEquals(List.of(), result.metrics(), "event rates need two JFR recordings");
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Different formats")),
        result.comparability().toString());
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Stack truncation unknown")),
        result.comparability().toString());
  }

  @Test
  void lineNumbersInTheConvertedNamesStillMatch(@TempDir Path dir) throws Exception {
    // includeLineNumbers writes Class.method:<line>; the key drops the line, as JFR's does.
    Path pprof =
        convert(
            dir, MAPPING + "frame:\n  format: \"{class}.{method}\"\n  includeLineNumbers: true\n");
    RecordingProfile jfr =
        new JfrCompare().read(TCK, new ProfileSelection("datadog.ExecutionSample", false));
    RecordingProfile converted =
        new PprofProfileReader().read(pprof, new ProfileSelection(null, false));
    assertEquals(jfr.sampleCount(), converted.sampleCount());
    assertEquals(
        jfr.leafFrameCounts(),
        converted.leafFrameCounts(),
        () -> difference(jfr.leafFrameCounts(), converted.leafFrameCounts()));
  }

  @Test
  void compareReadsAJfrRecordingAgainstItsConversion(@TempDir Path dir) throws Exception {
    Path pprof = convert(dir, MAPPING);
    assertEquals(0, compare(TCK.toString(), pprof.toString()), this::streams);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("Candidate " + pprof + ": pprof samples/count"), text);
    assertTrue(text.contains("none: event rates need two JFR recordings"), text);
    assertTrue(text.contains("no frame moved by the noise floor or more"), text);
  }

  @Test
  void differentSamplerKindsAreRefusedUnlessAllowed(@TempDir Path dir) throws Exception {
    Path cpu = shifted(dir, "cpu", 0);
    Path wall = shifted(dir, "wall", 400);

    assertEquals(1, compare(cpu.toString(), wall.toString(), "--json"), this::streams);
    String error = err.toString(StandardCharsets.UTF_8);
    assertTrue(error.contains("Different samplers"), error);
    assertTrue(error.contains("--allow-mixed-samplers"), error);
    assertEquals("", out.toString(StandardCharsets.UTF_8));

    out.reset();
    assertEquals(
        0,
        compare(cpu.toString(), wall.toString(), "--json", "--allow-mixed-samplers"),
        this::streams);
    String json = out.toString(StandardCharsets.UTF_8);
    assertTrue(json.contains("Mixed samplers"), json);
    assertTrue(json.contains("\"significant\": false"), json);
    assertFalse(json.contains("\"significant\": true"), "a mixed pair judged a change: " + json);

    // The same pair as two CPU profiles: the 40-point shift is judged, so the refusal and the
    // ALLOW result above come from the sampler kinds, not from thin data.
    out.reset();
    Path cpuShifted = shifted(dir, "cpu", 400);
    assertEquals(0, compare(cpu.toString(), cpuShifted.toString(), "--json"), this::streams);
    assertTrue(
        out.toString(StandardCharsets.UTF_8).contains("\"significant\": true"),
        out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void allocationAgainstCpuIsRefusedEvenWhenMixedSamplersAreAllowed(@TempDir Path dir)
      throws Exception {
    Path cpu = shifted(dir, "cpu", 0);
    Path alloc = shifted(dir, "alloc_space", 0);
    assertEquals(
        1,
        compare(
            cpu.toString(),
            alloc.toString(),
            "--candidate-sample-type",
            "alloc_space",
            "--allow-mixed-samplers"),
        this::streams);
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("allocation profile with a time profile"),
        streams());
  }

  @Test
  void sampleTypeOptionsAreCheckedAgainstEachSidesFormat(@TempDir Path dir) throws Exception {
    Path cpu = shifted(dir, "cpu", 0);
    // Option misuse is a usage error: exit 2, with the command's usage.
    assertEquals(2, compare(TCK.toString(), TCK.toString(), "--sample-type", "cpu"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("--event-type"), streams());
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("Usage: jfr-shell compare"), streams());
    err.reset();
    assertEquals(2, compare(cpu.toString(), cpu.toString(), "--event-type", "jdk.ExecutionSample"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("--sample-type"), streams());
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("Usage: jfr-shell compare"), streams());
    err.reset();
    assertEquals(
        2, compare(TCK.toString(), cpu.toString(), "--baseline-sample-type", "cpu"), this::streams);
    err.reset();
    // A sample type the file does not have is about the file, not the command line: exit 1.
    assertEquals(1, compare(cpu.toString(), cpu.toString(), "--sample-type", "nope"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("has no sample type nope"), streams());
  }

  @Test
  void listSampleTypesNamesEachSidesTypes(@TempDir Path dir) throws Exception {
    Path cpu = shifted(dir, "cpu", 0);
    assertEquals(0, compare(TCK.toString(), cpu.toString(), "--list-sample-types"), this::streams);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains(TCK + " (jfr)"), text);
    // The bare event type, as --event-type takes it.
    assertTrue(text.contains("  datadog.ExecutionSample "), text);
    assertFalse(text.contains("datadog.ExecutionSample/"), text);
    assertTrue(text.contains(cpu + " (pprof)"), text);
    assertTrue(text.contains("samples/count"), text);
    assertTrue(text.contains("cpu/nanoseconds"), text);
  }

  @Test
  void aBackendGivenToAPprofOnlyCompareIsStillChecked(@TempDir Path dir) throws Exception {
    // #268: with no recording on either side the backend used to be ignored, typo or conflict
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JFRSHELL_BACKEND") == null);
    Path a = shifted(dir, "cpu", 0);
    Path b = shifted(dir, "cpu", 10);
    String previous = System.getProperty("jfr.shell.backend");
    try {
      List<String> conflict =
          List.of("-b", "jdk", "compare", "-b", "jafar", a.toString(), b.toString());
      assertEquals(
          2,
          new CommandLine(new io.jafar.shell.Main()).execute(conflict.toArray(String[]::new)),
          this::streams);
      assertTrue(
          err.toString(StandardCharsets.UTF_8).contains("before 'compare' and 'jafar' after it"),
          streams());
      err.reset();
      assertNotEquals(0, compare("-b", "nosuch", a.toString(), b.toString()), this::streams);
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("nosuch"), streams());
    } finally {
      if (previous == null) {
        System.clearProperty("jfr.shell.backend");
      } else {
        System.setProperty("jfr.shell.backend", previous);
      }
      io.jafar.shell.backend.BackendRegistry.getInstance().rediscover();
    }
  }

  @Test
  void theUnifiedShellRoutesCompareToJfrShell() {
    assertTrue(Main.isJfrCommandLine(new String[] {"compare", "a.jfr", "b.pb.gz"}));
    assertTrue(Main.isJfrCommandLine(new String[] {"-q", "compare", "a.jfr", "b.jfr", "--json"}));
  }

  @Test
  void anUnknownSampleTypeNamesTheOnesThereAre(@TempDir Path dir) throws Exception {
    Path cpu = shifted(dir, "cpu", 0);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PprofProfileReader().read(cpu, new ProfileSelection("wall", false)));
    assertTrue(e.getMessage().contains("samples/count, cpu/nanoseconds"), e.getMessage());
  }

  // ---------------------------------------------------------------------------------------------

  private int compare(String... args) {
    List<String> argv = new ArrayList<>(List.of("compare"));
    argv.addAll(List.of(args));
    return new CommandLine(new io.jafar.shell.Main()).execute(argv.toArray(String[]::new));
  }

  private static Path convert(Path dir, String mapping) throws Exception {
    Path pprof = Files.createTempFile(dir, "tck-", ".pb.gz");
    try (var stream = Files.newOutputStream(pprof)) {
      new Jfr2PprofConverter()
          .convert(TCK, MappingLoader.load(new StringReader(mapping)), true, stream);
    }
    return pprof;
  }

  /**
   * A profile with sample types {@code samples/count} and {@code <type>/<unit>}: 1000 samples, of
   * which {@code moved} have {@code m.New.work} as their leaf instead of {@code m.Busy.work}, both
   * called from {@code m.Main.main}.
   */
  private static Path shifted(Path dir, String type, int moved) throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    b.addSampleType(b.addString("samples"), b.addString("count"));
    b.addSampleType(
        b.addString(type), b.addString(type.startsWith("alloc") ? "bytes" : "nanoseconds"));
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
    Path written = b.write(dir);
    return Files.move(
        written, dir.resolve(type + "-" + moved + "-" + System.nanoTime() + ".pb.gz"));
  }

  /** The entries of two frame-count maps that differ, as {@code key: jfr vs pprof}. */
  private static String difference(Map<String, Long> jfr, Map<String, Long> pprof) {
    java.util.Set<String> keys = new java.util.TreeSet<>(jfr.keySet());
    keys.addAll(pprof.keySet());
    StringBuilder diff = new StringBuilder("frames that differ (jfr vs pprof):");
    for (String key : keys) {
      if (!java.util.Objects.equals(jfr.get(key), pprof.get(key))) {
        diff.append("\n  ").append(key).append(": ").append(jfr.get(key)).append(" vs ");
        diff.append(pprof.get(key));
      }
    }
    return diff.toString();
  }

  private String streams() {
    return "stdout:\n"
        + out.toString(StandardCharsets.UTF_8)
        + "\nstderr:\n"
        + err.toString(StandardCharsets.UTF_8);
  }
}
