package io.jafar.shell.core.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Locale;
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

  @Test
  void inclusiveFramesFromProfilesReadWithoutThemAreRefused() throws Exception {
    List<Map<String, Object>> events = samples(Map.of(List.of("a.A.a", "b.B.b"), 100L));
    RecordingProfile base =
        new JfrCompare(scripted(events)).profile(target("base.jfr"), "jdk.ExecutionSample", false);
    RecordingProfile cand =
        new JfrCompare(scripted(events)).profile(target("cand.jfr"), "jdk.ExecutionSample", true);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new JfrCompare(scripted(List.of())).compare(base, cand, SELF_AND_INCLUSIVE));
    assertTrue(e.getMessage().contains("baseline"), e.getMessage());
    // Self time alone is fine from either profile.
    assertNull(
        new JfrCompare(scripted(List.of()))
            .compare(base, cand, CompareOptions.defaults())
            .inclusiveFrames());
  }

  @Test
  void truncatedStacksMakeInclusiveChangesInconclusive() {
    // Same work on both sides; the candidate's stacks are cut before Thread.run, as a deeper
    // stack is at JFR's 64-frame default. Thread.run's inclusive share falls with no time moving.
    CompareResult result =
        compare(
            samples(Map.of(List.of("m.Leaf.work", "m.Mid.call", "java.lang.Thread.run"), 1000L)),
            samples(Map.of(List.of("m.Leaf.work", "m.Mid.call"), 1000L), true),
            SELF_AND_INCLUSIVE);

    assertEquals(0L, result.baseline().truncatedSamples());
    assertEquals(1000L, result.candidate().truncatedSamples());
    Map<String, Object> run = row(result.inclusiveFrames(), "java.lang.Thread.run");
    assertEquals(-100.0, run.get("deltaPct"));
    assertEquals(false, run.get("significant"), run.toString());
    assertEquals("inconclusive", run.get("direction"));
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Truncated stacks")),
        result.comparability().toString());
    assertFalse(result.comparability().contains("Recordings appear comparable."));
    assertEquals(1000L, CompareResult.describe(result.candidate()).get("truncatedStacks"));
  }

  @Test
  void truncationIsNotReportedWithoutInclusiveFrames() {
    CompareResult result =
        compare(
            samples(Map.of(List.of("m.Leaf.work"), 100L), true),
            samples(Map.of(List.of("m.Leaf.work"), 100L), true),
            CompareOptions.defaults());
    assertEquals(0L, result.candidate().truncatedSamples());
    // jfr_compare's description keys stay as they were.
    assertFalse(CompareResult.describe(result.candidate()).containsKey("truncatedStacks"));
  }

  @Test
  void aProfileCannotChangeAfterItIsRead() {
    RecordingProfile profile =
        compare(
                samples(Map.of(List.of("a.A.a", "b.B.b"), 100L)),
                samples(Map.of(List.of("a.A.a", "b.B.b"), 100L)),
                SELF_AND_INCLUSIVE)
            .baseline();
    assertThrows(UnsupportedOperationException.class, () -> profile.leafFrameCounts().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> profile.inclusiveFrameCounts().put("x", 1L));
    assertThrows(UnsupportedOperationException.class, () -> profile.eventCounts().clear());
  }

  @Test
  void comparabilityNotesDoNotFollowTheDefaultLocale() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(Locale.GERMANY);
    try {
      // 131 samples one second apart span 130 s, 40 span 39 s: durations differ by 3.3x.
      CompareResult result =
          compare(
              samples(Map.of(List.of("a.A.a"), 131L)),
              samples(Map.of(List.of("a.A.a"), 40L)),
              CompareOptions.defaults());
      String note =
          result.comparability().stream()
              .filter(n -> n.startsWith("Observed durations"))
              .findFirst()
              .orElseThrow(() -> new AssertionError(result.comparability()));
      assertTrue(note.contains("differ by 3.3x (130.0s vs 39.0s)"), note);
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void framesOfGeneratedClassesKeepOneNameAcrossJvms() {
    // The same work in two JVM runs: the hidden classes got different addresses and the lambda a
    // different sequence number, so without normalising every such frame looks gone and new.
    CompareResult result =
        compare(
            samples(
                Map.of(
                    List.of(
                        "m.Work.leaf",
                        "java.lang.invoke.LambdaForm$MH/0x00007f2e141a1000.invoke",
                        "m.Main$$Lambda$14/0x0000000800c01234.run"),
                    100L)),
            samples(
                Map.of(
                    List.of(
                        "m.Work.leaf",
                        "java.lang.invoke.LambdaForm$MH/0x00007fb3aa0c4400.invoke",
                        "m.Main$$Lambda$27/0x0000000800d05678.run"),
                    100L)),
            SELF_AND_INCLUSIVE);

    assertEquals(List.of(), result.inclusiveFrames(), "the same frames, renamed per JVM");
    assertEquals(
        100L,
        result.baseline().inclusiveFrameCounts().get("java.lang.invoke.LambdaForm$MH.invoke"));
    assertEquals(100L, result.candidate().inclusiveFrameCounts().get("m.Main$$Lambda.run"));
  }

  @Test
  void stableNamesLeaveOrdinaryFramesAlone() {
    assertEquals("m.Foo.bar", JfrCompare.stableName("m.Foo.bar"));
    assertEquals("m.Foo$Inner.bar", JfrCompare.stableName("m.Foo$Inner.bar"));
    assertEquals("m.Foo$$Lambda.run", JfrCompare.stableName("m.Foo$$Lambda/0x0000000801234.run"));
    assertEquals("m.Foo$$Lambda.run", JfrCompare.stableName("m.Foo$$Lambda$3/0x00000008abcd.run"));
    // javac's per-compilation numbers: accessors (Java 8 targets) and lambda bodies.
    assertEquals("m.Foo.access$", JfrCompare.stableName("m.Foo.access$300"));
    assertEquals("m/x/Outer.access$", JfrCompare.stableName("m/x/Outer.access$400"));
    assertEquals("m.Foo.lambda$main$", JfrCompare.stableName("m.Foo.lambda$main$0"));
    assertEquals("m.Foo.lambda$static$", JfrCompare.stableName("m.Foo.lambda$static$12"));
    // Not javac's: left as they are.
    assertEquals("m.Foo.access", JfrCompare.stableName("m.Foo.access"));
    assertEquals("m.Foo$1.run", JfrCompare.stableName("m.Foo$1.run"));
    assertEquals("m.Foo.value$1x", JfrCompare.stableName("m.Foo.value$1x"));
  }

  @Test
  void accessorsRenumberedBetweenBuildsAreOneFrame() {
    // Seen in a perf-ab run: the same accessor was access$400 in the base build and access$300
    // in the head build, and read as -53 and +57 points.
    CompareResult result =
        compare(
            samples(Map.of(List.of("m.Work.leaf", "m.Parser.access$400", "m.Main.run"), 100L)),
            samples(Map.of(List.of("m.Work.leaf", "m.Parser.access$300", "m.Main.run"), 100L)),
            SELF_AND_INCLUSIVE);
    assertEquals(List.of(), result.inclusiveFrames(), "the same accessor, renumbered by javac");
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
    return samples(stacks, false);
  }

  /** As {@link #samples(Map)}, with every stack marked {@code truncated} as given. */
  private static List<Map<String, Object>> samples(
      Map<List<String>, Long> stacks, boolean truncated) {
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
      Map<String, Object> stackTrace = Map.of("frames", frames, "truncated", truncated);
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
