package io.jafar.shell.core.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.analysis.JfrQuerySource;
import io.jafar.shell.core.findings.Finding;
import io.jafar.shell.jfrpath.JfrPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The compare core: what it calls a regression, what it calls noise, and how inclusive shares
 * count. Scripted samples state their counts exactly; the identical-recordings case reads a real,
 * checked-in recording through the real engine.
 */
class JfrCompareTest {

  /** Checked into the repository; 14,202 {@code datadog.ExecutionSample} events. */
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static final CompareOptions SELF_AND_INCLUSIVE =
      CompareOptions.defaults().withInclusiveFrames(true);

  @Test
  void identicalRecordingsGiveNoSignificantFrame() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    try (JFRSession a = new JFRSession(TCK_RECORDING, ctx);
        JFRSession b = new JFRSession(TCK_RECORDING, ctx)) {
      CompareResult result =
          new JfrCompare()
              .compare(AnalysisTarget.of(1, a), AnalysisTarget.of(2, b), SELF_AND_INCLUSIVE);

      // Not vacuous: there were samples and frames to compare.
      assertNotNull(result.baseline().eventType(), "no execution sample type detected");
      assertTrue(result.sufficientSamples(), () -> "samples: " + result.baseline().sampleCount());
      assertFalse(result.baseline().leafFrameCounts().isEmpty(), "no leaf frames read");
      assertFalse(result.baseline().inclusiveFrameCounts().isEmpty(), "no inclusive frames read");
      assertTrue(
          result.baseline().inclusiveFrameCounts().size()
              > result.baseline().leafFrameCounts().size(),
          "a stack has callers as well as leaves");

      assertEquals(List.of(), result.frames(), "identical recordings, but frames moved");
      assertEquals(List.of(), result.inclusiveFrames(), "identical, but inclusive frames moved");
      assertEquals(List.of("regression:none"), ids(result.findings()));
    }
  }

  @Test
  void aShiftedPairReportsTheShiftedFrame() {
    CompareResult result =
        compare(
            samples(Map.of(List.of("base.Busy.run", "app.Main.main"), 1000L)),
            samples(
                Map.of(
                    List.of("base.Busy.run", "app.Main.main"), 800L,
                    List.of("new.Frame.run", "app.Main.main"), 200L)),
            SELF_AND_INCLUSIVE);

    assertTrue(result.sufficientSamples());
    Map<String, Object> shifted = row(result.frames(), "new.Frame.run");
    assertEquals("regression", shifted.get("direction"));
    assertEquals(true, shifted.get("significant"));
    assertEquals(0.0, shifted.get("baselineSelfPct"));
    assertEquals(20.0, shifted.get("candidateSelfPct"));
    assertEquals("improvement", row(result.frames(), "base.Busy.run").get("direction"));
    assertTrue(
        ids(result.findings()).contains("regression:frame-new-frame-run"),
        () -> ids(result.findings()).toString());
    assertEquals(1, warnings(result), () -> ids(result.findings()).toString());
  }

  @Test
  void inclusiveSharesCountARecursiveFrameOncePerSample() {
    // Baseline: every sample is deep in a recursion. Candidate: half of them are elsewhere.
    CompareResult result =
        compare(
            samples(Map.of(List.of("m.Leaf.work", "m.Fib.fib", "m.Fib.fib", "m.Fib.fib"), 1000L)),
            samples(
                Map.of(
                    List.of("m.Leaf.work", "m.Fib.fib", "m.Fib.fib", "m.Fib.fib"), 500L,
                    List.of("m.Other.idle"), 500L)),
            SELF_AND_INCLUSIVE);

    assertEquals(1000L, result.baseline().inclusiveFrameCounts().get("m.Fib.fib"));
    Map<String, Object> fib = row(result.inclusiveFrames(), "m.Fib.fib");
    assertEquals(100.0, fib.get("baselineTotalPct"), "on the stack of every sample, not 300%");
    assertEquals(50.0, fib.get("candidateTotalPct"));
    assertEquals("improvement", fib.get("direction"));
    assertEquals(true, fib.get("significant"));

    // fib is never a leaf, so self time cannot see it at all.
    assertFalse(hasRow(result.frames(), "m.Fib.fib"), result.frames().toString());
    Map<String, Object> leafSelf = row(result.frames(), "m.Leaf.work");
    Map<String, Object> leafTotal = row(result.inclusiveFrames(), "m.Leaf.work");
    assertEquals(100.0, leafSelf.get("baselineSelfPct"));
    assertEquals(leafSelf.get("baselineSelfPct"), leafTotal.get("baselineTotalPct"));
  }

  @Test
  void inclusiveSharesAreLeftOutUnlessAskedFor() {
    CompareResult result =
        compare(
            samples(Map.of(List.of("a.A.a", "b.B.b"), 100L)),
            samples(Map.of(List.of("a.A.a", "b.B.b"), 100L)),
            CompareOptions.defaults());

    assertNull(result.inclusiveFrames());
    assertTrue(result.baseline().inclusiveFrameCounts().isEmpty());
    assertFalse(result.toMap().containsKey("inclusiveFrames"));
    assertTrue(
        compare(
                samples(Map.of(List.of("a.A.a"), 100L)),
                samples(Map.of(List.of("a.A.a"), 100L)),
                SELF_AND_INCLUSIVE)
            .toMap()
            .containsKey("inclusiveFrames"));
  }

  @Test
  void thinRecordingsCallNoRegression() {
    // 7 and 3 samples, and a frame going from 0 to 1 of them: too thin to judge.
    CompareResult result =
        compare(
            samples(Map.of(List.of("base.Busy.run", "app.Main.main"), 7L)),
            samples(
                Map.of(
                    List.of("base.Busy.run", "app.Main.main"), 2L,
                    List.of("new.Frame.run", "app.Main.main"), 1L)),
            SELF_AND_INCLUSIVE);

    assertFalse(result.sufficientSamples());
    assertEquals(0, warnings(result));
    assertEquals(List.of("regression:insufficient-samples"), ids(result.findings()));
    assertTrue(
        String.join(" ", result.comparability()).contains("Too few samples"),
        result.comparability().toString());
    for (List<Map<String, Object>> rows : List.of(result.frames(), result.inclusiveFrames())) {
      assertFalse(rows.isEmpty(), "the move is shown, just not judged");
      for (Map<String, Object> row : rows) {
        assertEquals("inconclusive", row.get("direction"), row.toString());
        assertEquals(false, row.get("significant"), row.toString());
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  private static CompareResult compare(
      List<Map<String, Object>> baseline,
      List<Map<String, Object>> candidate,
      CompareOptions options) {
    try {
      RecordingProfile base =
          new JfrCompare(scripted(baseline))
              .profile(target("base.jfr"), "jdk.ExecutionSample", options.inclusiveFrames());
      RecordingProfile cand =
          new JfrCompare(scripted(candidate))
              .profile(target("cand.jfr"), "jdk.ExecutionSample", options.inclusiveFrames());
      return new JfrCompare(scripted(List.of())).compare(base, cand, options);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static AnalysisTarget target(String name) {
    return new AnalysisTarget(0, Path.of(name), null);
  }

  private static JfrQuerySource scripted(List<Map<String, Object>> events) {
    return new JfrQuerySource() {
      @Override
      public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query) {
        return events;
      }

      @Override
      public void consume(
          JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer) {
        events.forEach(consumer);
      }

      @Override
      public Map<String, Long> countAllEventTypes(JFRSession session) {
        return Map.of("jdk.ExecutionSample", (long) events.size());
      }
    };
  }

  /**
   * {@code count} samples per stack, one second apart. A stack lists its frames leaf first, each as
   * {@code package.Class.method}.
   */
  private static List<Map<String, Object>> samples(Map<List<String>, Long> stacks) {
    List<Map<String, Object>> events = new ArrayList<>();
    long startTime = 0;
    for (Map.Entry<List<String>, Long> stack : stacks.entrySet()) {
      List<Map<String, Object>> frames = new ArrayList<>();
      for (String frame : stack.getKey()) {
        int dot = frame.lastIndexOf('.');
        frames.add(
            Map.of(
                "method",
                Map.of(
                    "type", Map.of("name", frame.substring(0, dot)),
                    "name", frame.substring(dot + 1))));
      }
      Map<String, Object> stackTrace = Map.of("frames", frames);
      for (long i = 0; i < stack.getValue(); i++) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("startTime", startTime);
        event.put("stackTrace", stackTrace);
        events.add(event);
        startTime += 1_000_000_000L;
      }
    }
    return events;
  }

  private static boolean hasRow(List<Map<String, Object>> rows, String method) {
    return rows.stream().anyMatch(r -> method.equals(r.get("method")));
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String method) {
    return rows.stream()
        .filter(r -> method.equals(r.get("method")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + method + " in " + rows));
  }

  private static List<String> ids(List<Finding> findings) {
    return findings.stream().map(Finding::id).toList();
  }

  private static long warnings(CompareResult result) {
    return result.findings().stream().filter(f -> f.severity() == Finding.Severity.WARNING).count();
  }
}
