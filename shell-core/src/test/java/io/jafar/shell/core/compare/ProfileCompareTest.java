package io.jafar.shell.core.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.findings.Finding;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The format-neutral comparison: the sampler policy (decision D5), shares from weights and
 * significance from counts, and unknown truncation (D4). Profiles are built directly, with exact
 * numbers.
 */
class ProfileCompareTest {

  /** 1000 samples, all in {@code m.Busy.work}. */
  private static final Map<String, Long> BEFORE = Map.of("m.Busy.work", 1000L);

  /** 1000 samples, 400 of them moved to {@code m.New.work}: significant by any measure. */
  private static final Map<String, Long> AFTER = Map.of("m.Busy.work", 600L, "m.New.work", 400L);

  private static final CompareOptions REFUSE =
      CompareOptions.defaults().withMixedSamplers(CompareOptions.MixedSamplers.REFUSE);

  private static final CompareOptions ALLOW =
      CompareOptions.defaults().withMixedSamplers(CompareOptions.MixedSamplers.ALLOW);

  @Test
  void cpuAgainstWallIsRefusedByDefault() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProfileCompare.compare(
                    pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, true),
                    pprof("wall/nanoseconds", SamplerKind.WALL, AFTER, true),
                    REFUSE));
    assertTrue(e.getMessage().startsWith("Different samplers"), e.getMessage());
    assertTrue(e.getMessage().contains("cpu/nanoseconds measures CPU"), e.getMessage());
    assertTrue(e.getMessage().contains("wall/nanoseconds measures WALL"), e.getMessage());
    assertTrue(e.getMessage().contains("--allow-mixed-samplers"), e.getMessage());
  }

  @Test
  void cpuAgainstWallWhenAllowedIsReportedButNotJudged() {
    CompareResult result =
        ProfileCompare.compare(
            pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, true),
            pprof("wall/nanoseconds", SamplerKind.WALL, AFTER, true),
            ALLOW);
    Map<String, Object> moved = row(result.frames(), "m.New.work");
    assertEquals(40.0, moved.get("candidateSelfPct"));
    assertEquals(false, moved.get("significant"));
    assertEquals("inconclusive", moved.get("direction"));
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Mixed samplers")),
        result.comparability().toString());
    assertEquals(List.of("regression:not-judged"), ids(result.findings()));
  }

  @Test
  void theSameMoveBetweenTwoCpuProfilesIsJudged() {
    // The control for the two tests above: the refusal and the inconclusive rows come from the
    // sampler kinds, not from the data.
    CompareResult result =
        ProfileCompare.compare(
            pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, true),
            pprof("cpu/nanoseconds", SamplerKind.CPU, AFTER, true),
            REFUSE);
    assertEquals(true, row(result.frames(), "m.New.work").get("significant"));
    assertTrue(ids(result.findings()).contains("regression:frame-m-new-work"));
  }

  @Test
  void allocationAgainstTimeIsRefusedEvenWhenMixedSamplersAreAllowed() {
    for (CompareOptions options : List.of(REFUSE, ALLOW, CompareOptions.defaults())) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  ProfileCompare.compare(
                      pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, true),
                      pprof("alloc_space/bytes", SamplerKind.ALLOC_BYTES, AFTER, true),
                      options));
      assertTrue(
          e.getMessage().startsWith("Cannot compare an allocation profile with a time profile"),
          e.getMessage());
    }
  }

  @Test
  void jfrCompareKeepsItsNoteForTwoJfrEventTypesOfDifferentKinds() {
    // jfr_compare's options (MixedSamplers.NOTE): execution samples against native method samples
    // are judged as they always were, with the event-type note.
    CompareResult result =
        ProfileCompare.compare(
            jfr("jdk.ExecutionSample", BEFORE),
            jfr("jdk.NativeMethodSample", AFTER),
            CompareOptions.defaults());
    assertEquals(true, row(result.frames(), "m.New.work").get("significant"));
    assertTrue(
        result.comparability().stream()
            .anyMatch(n -> n.startsWith("Different execution sample event types")),
        result.comparability().toString());
    // REFUSE itself would refuse the pair; the entry points never pass it for two JFR sides.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProfileCompare.compare(
                jfr("jdk.ExecutionSample", BEFORE), jfr("jdk.NativeMethodSample", AFTER), REFUSE));
  }

  @Test
  void twoJfrSidesAreNeverRefusedByTheCrossFormatEntryPoints() {
    // jfr-shell compare and profile_compare pick their policy here: two recordings get the note
    // jfr_compare gives, whatever --allow-mixed-samplers says; a pprof side gets the policy.
    assertEquals(
        CompareOptions.MixedSamplers.NOTE,
        CompareOptions.MixedSamplers.forPair("jfr", "jfr", false));
    assertEquals(
        CompareOptions.MixedSamplers.NOTE,
        CompareOptions.MixedSamplers.forPair("jfr", "jfr", true));
    assertEquals(
        CompareOptions.MixedSamplers.REFUSE,
        CompareOptions.MixedSamplers.forPair("jfr", "pprof", false));
    assertEquals(
        CompareOptions.MixedSamplers.ALLOW,
        CompareOptions.MixedSamplers.forPair("pprof", "pprof", true));
  }

  @Test
  void sharesComeFromWeightsAndSignificanceFromCounts() {
    // Nanosecond weights with no observation counts: a 40-point move, never significant.
    RecordingProfile base = pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, false);
    RecordingProfile cand = pprof("cpu/nanoseconds", SamplerKind.CPU, AFTER, false);
    CompareResult result = ProfileCompare.compare(base, cand, REFUSE);
    Map<String, Object> moved = row(result.frames(), "m.New.work");
    assertEquals(40.0, moved.get("candidateSelfPct"));
    assertEquals(false, moved.get("significant"));
    assertNull(moved.get("candidateSamples"), "records are not observations: " + moved);
    assertEquals(false, result.sufficientSamples());
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Observation counts unknown")),
        result.comparability().toString());
    assertEquals(List.of("regression:not-judged"), ids(result.findings()));
    assertEquals(false, CompareResult.describe(cand).get("sampleCountKnown"));
  }

  @Test
  void unknownTruncationStillJudgesInclusiveMovesWithANote() {
    RecordingProfile base = pprof("cpu/nanoseconds", SamplerKind.CPU, BEFORE, true);
    RecordingProfile cand = pprof("cpu/nanoseconds", SamplerKind.CPU, AFTER, true);
    CompareResult result = ProfileCompare.compare(base, cand, REFUSE.withInclusiveFrames(true));
    assertEquals(true, row(result.inclusiveFrames(), "m.New.work").get("significant"));
    assertEquals(
        2,
        result.comparability().stream()
            .filter(n -> n.startsWith("Stack truncation unknown"))
            .count(),
        result.comparability().toString());
    // A format without the signal reports no truncated-stack count at all, not 0.
    assertTrue(!CompareResult.describe(cand).containsKey("truncatedStacks"));
  }

  @Test
  void aPprofSideGetsNoEventRates() {
    CompareResult result =
        ProfileCompare.compare(
            jfr("jdk.ExecutionSample", BEFORE),
            pprof("cpu/nanoseconds", SamplerKind.CPU, AFTER, true),
            REFUSE);
    assertEquals(List.of(), result.metrics());
    assertTrue(
        result.comparability().stream().anyMatch(n -> n.startsWith("Different formats")),
        result.comparability().toString());
    // The JFR side keeps exactly jfr_compare's keys; the pprof side says what it is.
    assertEquals(
        List.of("recordingPath", "eventType", "samples", "observedDurationSeconds", "totalEvents"),
        List.copyOf(CompareResult.describe(result.baseline()).keySet()));
    assertEquals("pprof", CompareResult.describe(result.candidate()).get("format"));
  }

  // ---------------------------------------------------------------------------------------------

  /** A JFR profile: one sample per count, leaf frames only. */
  private static RecordingProfile jfr(String eventType, Map<String, Long> leaves) {
    long samples = leaves.values().stream().mapToLong(Long::longValue).sum();
    return new RecordingProfile(
        Path.of(eventType + ".jfr"),
        eventType,
        samples,
        samples,
        leaves,
        Map.of(),
        false,
        0,
        Map.of(eventType, samples),
        samples);
  }

  /**
   * A pprof profile weighing each sample 10 ms; {@code countsKnown} says whether the counts are
   * observations. Inclusive frames are the leaves plus {@code m.Main.main} under every sample.
   */
  private static RecordingProfile pprof(
      String type, SamplerKind kind, Map<String, Long> leaves, boolean countsKnown) {
    long samples = leaves.values().stream().mapToLong(Long::longValue).sum();
    Map<String, Long> weights = new LinkedHashMap<>();
    leaves.forEach((frame, count) -> weights.put(frame, count * 10_000_000L));
    Map<String, Long> inclusive = new LinkedHashMap<>(leaves);
    inclusive.put("m.Main.main", samples);
    Map<String, Long> inclusiveWeights = new LinkedHashMap<>(weights);
    inclusiveWeights.put("m.Main.main", samples * 10_000_000L);
    return new RecordingProfile(
        Path.of(type.replace('/', '-') + ".pb.gz"),
        type,
        samples,
        30.0,
        leaves,
        inclusive,
        true,
        0,
        Map.of(),
        0,
        "pprof",
        type.substring(type.indexOf('/') + 1),
        kind,
        samples * 10_000_000L,
        weights,
        inclusiveWeights,
        countsKnown,
        false,
        "requested");
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
}
