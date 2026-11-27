package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.otlp.shell.OtlpProfileReader;
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
 * Comparing OTLP profiles async-profiler 4.6 wrote against JFR and pprof: one run recorded as JFR
 * and converted by {@code jfrconv} to OTLP and to pprof, and agent OTLP files from separate runs.
 * The files and how they were made are in {@code otlp-shell/src/test/resources/real/README.md}.
 */
class RealOtlpCompareTest {

  private static final Path REAL =
      Path.of("..", "otlp-shell", "src", "test", "resources", "real").toAbsolutePath().normalize();

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
  void oneRunAsJfrOtlpAndPprofHasTheSameFramesAndCounts() throws Exception {
    RecordingProfile jfr =
        new JfrCompare()
            .read(REAL.resolve("ap-cpu.jfr"), new ProfileSelection("jdk.ExecutionSample", true));
    RecordingProfile otlp = otlp("conv-cpu.otlp", null);
    RecordingProfile pprof =
        new PprofProfileReader()
            .read(REAL.resolve("conv-cpu.pprof"), new ProfileSelection(null, true));
    assertEquals(216, jfr.sampleCount());
    assertEquals(jfr.sampleCount(), otlp.sampleCount());
    assertEquals(jfr.sampleCount(), pprof.sampleCount());
    assertTrue(otlp.countsKnown());
    // Every frame carries the same key and count: Java frames dotted, hidden-class addresses
    // dropped, native, C++ and kernel frames by their symbol alone.
    assertEquals(jfr.leafFrameCounts(), otlp.leafFrameCounts());
    assertEquals(jfr.inclusiveFrameCounts(), otlp.inclusiveFrameCounts());
    assertEquals(pprof.inclusiveFrameCounts(), otlp.inclusiveFrameCounts());
    assertTrue(otlp.inclusiveFrameCounts().containsKey("java.lang.String.repeat"));

    CompareResult result = ProfileCompare.compare(jfr, otlp, ALL);
    assertFalse(result.frames().isEmpty());
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      for (Map<String, Object> row : rows) {
        assertEquals(0.0, row.get("deltaPct"), row.toString());
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  @Test
  void theAgentsOtlpAndAnotherRunsJfrShareTheirFrameKeys() throws Exception {
    // Two runs, so counts differ; the shape agrees: the same hot frames under the same keys.
    RecordingProfile jfr =
        new JfrCompare()
            .read(REAL.resolve("ap-cpu.jfr"), new ProfileSelection("jdk.ExecutionSample", true));
    RecordingProfile agent = otlp("ap-cpu.otlp", null);
    for (String frame :
        List.of(
            "RealProf$Hasher.mix",
            "RealProf$Hasher.sum",
            "RealProf.lambda$burn$",
            "RealProf$$Lambda.applyAsInt",
            "RealProf.burn",
            "RealProf.main",
            "java.lang.String.repeat")) {
      assertTrue(jfr.inclusiveFrameCounts().containsKey(frame), "JFR: " + frame);
      assertTrue(agent.inclusiveFrameCounts().containsKey(frame), "OTLP: " + frame);
    }
    CompareResult result = ProfileCompare.compare(jfr, agent, ALL);
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.contains("otlp perf_events/count")),
        result.comparability().toString());
  }

  @Test
  void theCompareCommandReadsOtlp() {
    assertEquals(
        0,
        compare(
            REAL.resolve("ap-cpu.jfr").toString(),
            REAL.resolve("conv-cpu.otlp").toString(),
            "--json",
            "--min-delta-pct",
            "0"),
        this::streams);
    String json = out.toString(StandardCharsets.UTF_8);
    assertTrue(json.matches("(?s).*\"format\"\\s*:\\s*\"otlp\".*"), json);
    assertTrue(json.matches("(?s).*\"significant\"\\s*:\\s*false.*"), json);
    assertFalse(json.matches("(?s).*\"significant\"\\s*:\\s*true.*"), json);
  }

  @Test
  void theCompareCommandRefusesCpuAgainstWallAndListsOtlpSampleTypes() {
    assertEquals(
        1,
        compare(REAL.resolve("ap-cpu.otlp").toString(), REAL.resolve("ap-wall.otlp").toString()),
        this::streams);
    String error = err.toString(StandardCharsets.UTF_8);
    assertTrue(error.contains("Different samplers"), error);
    assertTrue(error.contains("otlp wall/count measures WALL"), error);

    out.reset();
    assertEquals(
        0,
        compare(
            REAL.resolve("ap-cpu.otlp").toString(),
            REAL.resolve("ap-alloc.otlp").toString(),
            "--list-sample-types"),
        this::streams);
    String text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.matches("(?s).*ap-cpu\\.otlp \\(otlp\\).*perf_events/count\\s+CPU.*"), text);
    assertTrue(text.matches("(?s).*object_sampler/count\\s+ALLOC_COUNT.*"), text);
  }

  @Test
  void theOlderLayoutIsNamedNotMisread() {
    assertEquals(
        1,
        compare(REAL.resolve("ap41-cpu.otlp").toString(), REAL.resolve("ap-cpu.otlp").toString()),
        this::streams);
    String error = err.toString(StandardCharsets.UTF_8);
    assertTrue(error.contains("unsupported OTLP profiles layout"), error);
  }

  // ---------------------------------------------------------------------------------------------

  private static RecordingProfile otlp(String name, String sampleType) throws Exception {
    return new OtlpProfileReader().read(REAL.resolve(name), new ProfileSelection(sampleType, true));
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
