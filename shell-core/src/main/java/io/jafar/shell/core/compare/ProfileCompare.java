package io.jafar.shell.core.compare;

import io.jafar.shell.core.analysis.JfrAnalyses;
import io.jafar.shell.core.findings.Finding;
import io.jafar.shell.core.findings.Findings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares a candidate profile against a baseline, whatever format each was read from: the logic
 * behind {@code jfr_compare}, {@code profile_compare} and {@code jfr-shell compare}, with no
 * transport and no reader attached. {@link JfrCompare} reads JFR recordings; each other format's
 * {@link ProfileReader} reads its files into the same {@link RecordingProfile}.
 *
 * <p>The comparison normalises before it compares. Frames are compared as a share of each side's
 * total weight, never as absolute amounts, so two profiles taken at different sampling intervals,
 * or by different profilers of the same kind, remain comparable. Event counts become per-second
 * rates over each recording's own observed span, and only when both sides are JFR recordings: other
 * formats carry no events. Where the two sides are not safely comparable at all — samplers that
 * measure different things, different event types, wildly different durations — the result says so
 * in {@code comparability} or refuses, instead of quietly producing a plausible number.
 *
 * <p>A frame is only called a regression when its move is significant: a two-proportion test on
 * observation counts, never on weights. When a side's observations cannot be counted (a pprof
 * profile weighted in nanoseconds with no count column and no period to divide by), its shares are
 * still reported, but nothing is called significant.
 *
 * <p>Two frame lists can be compared. Self time (the leaf frame of each sample) is always compared.
 * Inclusive time (every frame on the stack, once per sample) is compared on request: it names a
 * caller whose own code is unchanged but whose callees got slower, which self time cannot.
 */
public final class ProfileCompare {

  /**
   * Frames whose share moved by less than this many percentage points are reported as noise. A
   * sampling profiler's per-frame share varies run to run even with no code change; a
   * sub-percentage-point move is not evidence of anything.
   */
  public static final double DEFAULT_MIN_DELTA_PCT = 1.0;

  /**
   * Below this many samples in either recording no frame or rate change is called a regression. It
   * is the usual floor for the two-proportion test below, and the samples' span is also what rates
   * are measured over, so a thin recording undermines both.
   */
  public static final long MIN_RECORDING_SAMPLES = 30;

  /** A frame needs this many samples in at least one recording before its move can mean much. */
  public static final long MIN_FRAME_SAMPLES = 5;

  /**
   * Two-proportion z score a frame's change must reach. Strict because every frame is its own test,
   * and a recording has hundreds of them.
   */
  public static final double MIN_SIGNIFICANCE_Z = 3.0;

  /** Beyond this ratio between the two observed durations, rate comparisons get a warning. */
  public static final double DURATION_RATIO_WARN = 3.0;

  /** Frame rows returned per list unless the caller asks for another number. */
  public static final int DEFAULT_LIMIT = 25;

  /**
   * Above this share of truncated stacks on either side, in percentage points, no inclusive change
   * is called significant. A truncated stack lacks its outermost frames, so a change in how many
   * stacks get cut moves those frames' inclusive shares with no time moving.
   */
  public static final double MAX_TRUNCATED_PCT_FOR_INCLUSIVE = 1.0;

  /** Event types compared as rates, besides the total. */
  private static final List<String> TRACKED_EVENT_TYPES =
      List.of(
          "jdk.GCPhasePause",
          "jdk.GarbageCollection",
          "jdk.JavaMonitorEnter",
          "jdk.JavaMonitorWait",
          "jdk.ThreadPark",
          "jdk.ObjectAllocationSample",
          "jdk.ObjectAllocationInNewTLAB",
          "jdk.SocketRead",
          "jdk.FileRead",
          "jdk.JavaErrorThrow",
          "jdk.ExceptionThrow");

  /**
   * Findings' {@code source}: the comparison is {@code jfr_compare}'s, whoever runs it, so {@code
   * Findings.merge} de-duplicates findings of the two tools.
   */
  private static final String SOURCE = "jfr_compare";

  private static final String COMPARABLE = "Recordings appear comparable.";

  private ProfileCompare() {}

  /**
   * Compares two profiles already read.
   *
   * @throws IllegalArgumentException when {@code options} asks for inclusive frames but a profile
   *     was read without counting them (its empty counts would read as "no caller moved"), and when
   *     the two samplers measure different things and {@code options} does not allow it
   */
  public static CompareResult compare(
      RecordingProfile baseline, RecordingProfile candidate, CompareOptions options) {
    if (options.inclusiveFrames()
        && !(baseline.inclusiveCounted() && candidate.inclusiveCounted())) {
      throw new IllegalArgumentException(
          "inclusive frames were requested, but the "
              + (baseline.inclusiveCounted() ? "candidate" : "baseline")
              + " profile was read without counting them: read both with inclusive frames");
    }
    List<String> comparability = new ArrayList<>();
    boolean judge = checkSamplers(baseline, candidate, options.mixedSamplers(), comparability);
    comparability.addAll(comparabilityNotes(baseline, candidate, options.inclusiveFrames()));
    boolean countsKnown = baseline.countsKnown() && candidate.countsKnown();
    judge &= countsKnown;

    boolean judgeInclusive = judge;
    if (options.inclusiveFrames()
        && (truncatedAboveLimit(baseline) || truncatedAboveLimit(candidate))) {
      judgeInclusive = false;
      comparability.add(
          String.format(
              Locale.ROOT,
              "Truncated stacks (baseline %s, candidate %s, above %.1f%%):"
                  + " JFR cut them at its stack depth, so the outermost frames' inclusive shares"
                  + " are understated and no inclusive change is called significant. Record with"
                  + " -XX:FlightRecorderOptions:stackdepth=2048 to keep deeper stacks.",
              truncatedShare(baseline),
              truncatedShare(candidate),
              MAX_TRUNCATED_PCT_FOR_INCLUSIVE));
    }
    if (comparability.isEmpty()) {
      comparability.add(COMPARABLE);
    }

    List<Map<String, Object>> metrics =
        baseline.isJfr() && candidate.isJfr() ? compareMetrics(baseline, candidate) : List.of();
    List<Map<String, Object>> frames =
        compareFrames(
            baseline, candidate, false, "SelfPct", judge, options.minDeltaPct(), options.limit());
    List<Map<String, Object>> inclusive =
        options.inclusiveFrames()
            ? compareFrames(
                baseline,
                candidate,
                true,
                "TotalPct",
                judgeInclusive,
                options.minDeltaPct(),
                options.limit())
            : List.of();
    boolean sufficient = countsKnown && hasSufficientSamples(baseline, candidate);
    String notJudged =
        !countsKnown
            ? "the observation counts of "
                + unknownCountSides(baseline, candidate)
                + " are unknown, so sampling noise cannot be told apart"
            : !judge ? "the two samplers measure different things" : null;
    List<Finding> findings = findings(baseline, candidate, frames, metrics, notJudged);
    return new CompareResult(
        baseline,
        candidate,
        comparability,
        metrics,
        frames,
        options.inclusiveFrames() ? inclusive : null,
        options.minDeltaPct(),
        sufficient,
        MIN_RECORDING_SAMPLES,
        findings);
  }

  /** Whether both recordings have at least {@link #MIN_RECORDING_SAMPLES} samples. */
  public static boolean hasSufficientSamples(
      RecordingProfile baseline, RecordingProfile candidate) {
    return baseline.sampleCount() >= MIN_RECORDING_SAMPLES
        && candidate.sampleCount() >= MIN_RECORDING_SAMPLES;
  }

  /**
   * Whether a frame's change in share is more than sampling noise could plausibly produce: both
   * recordings have enough samples, the frame has at least {@link #MIN_FRAME_SAMPLES} in one of
   * them, and the two-proportion z score reaches {@link #MIN_SIGNIFICANCE_Z}. The inputs are
   * observation counts, out of each side's {@code sampleCount}; never weights.
   *
   * @param baseSamples the frame's samples in the baseline
   * @param candSamples the frame's samples in the candidate
   */
  public static boolean isSignificant(
      RecordingProfile baseline, RecordingProfile candidate, long baseSamples, long candSamples) {
    if (!hasSufficientSamples(baseline, candidate)) {
      return false;
    }
    if (Math.max(baseSamples, candSamples) < MIN_FRAME_SAMPLES) {
      return false;
    }
    double baseN = baseline.sampleCount();
    double candN = candidate.sampleCount();
    double pooled = (baseSamples + candSamples) / (baseN + candN);
    double standardError = Math.sqrt(pooled * (1 - pooled) * (1 / baseN + 1 / candN));
    if (standardError == 0.0) {
      return false;
    }
    double difference = Math.abs(candSamples / candN - baseSamples / baseN);
    return difference / standardError >= MIN_SIGNIFICANCE_Z;
  }

  /** {@code 21.7% of samples}, or {@code unknown} for a format that records no truncation. */
  private static String truncatedShare(RecordingProfile profile) {
    return profile.truncationKnown()
        ? String.format(Locale.ROOT, "%.1f%% of samples", profile.truncatedPct())
        : "unknown";
  }

  private static boolean truncatedAboveLimit(RecordingProfile profile) {
    return profile.truncationKnown() && profile.truncatedPct() > MAX_TRUNCATED_PCT_FOR_INCLUSIVE;
  }

  /** A side as a note names it: {@code pprof samples/count}, or just the event type for JFR. */
  private static String sampler(RecordingProfile profile) {
    return profile.isJfr() ? profile.eventType() : profile.format() + " " + profile.eventType();
  }

  /**
   * Applies the sampler policy (decision D5 of the cross-format design): an allocation profile
   * against a time profile is always refused; two different known kinds are refused, noted, or
   * allowed with nothing judged, as {@code policy} says.
   *
   * @return whether changes may be judged significant
   */
  private static boolean checkSamplers(
      RecordingProfile baseline,
      RecordingProfile candidate,
      CompareOptions.MixedSamplers policy,
      List<String> notes) {
    if (baseline.eventType() == null || candidate.eventType() == null) {
      return true;
    }
    if (policy == CompareOptions.MixedSamplers.NOTE && baseline.isJfr() && candidate.isJfr()) {
      // jfr_compare, as it always was: the event-type note below says what differs.
      return true;
    }
    SamplerKind base = baseline.samplerKind();
    SamplerKind cand = candidate.samplerKind();
    String pair =
        "baseline "
            + sampler(baseline)
            + " measures "
            + base
            + ", candidate "
            + sampler(candidate)
            + " measures "
            + cand;
    if ((base.isAllocation() && cand.isTime()) || (base.isTime() && cand.isAllocation())) {
      throw new IllegalArgumentException(
          "Cannot compare an allocation profile with a time profile ("
              + pair
              + "): their shares measure different things. Compare two profiles of the same"
              + " kind.");
    }
    if (base == SamplerKind.UNKNOWN || cand == SamplerKind.UNKNOWN) {
      if (baseline.format().equals(candidate.format())
          && baseline.eventType().equals(candidate.eventType())) {
        return true; // one sample type on both sides: nothing to tell apart
      }
      notes.add(
          "Sampler kind not recognised ("
              + pair
              + "): compared as requested, but check that both sides measure the same thing.");
      return true;
    }
    if (base == cand) {
      return true;
    }
    switch (policy) {
      case NOTE:
        return true;
      case ALLOW:
        notes.add(
            "Mixed samplers ("
                + pair
                + "): compared because mixed samplers were allowed, but their shares measure"
                + " different things, so no change is called significant.");
        return false;
      case REFUSE:
      default:
        throw new IllegalArgumentException(
            "Different samplers ("
                + pair
                + "): their shares are not comparable. Pass --allow-mixed-samplers"
                + " (profile_compare: allowMixedSamplers) to compare anyway, with no change called"
                + " significant.");
    }
  }

  private static String unknownCountSides(RecordingProfile baseline, RecordingProfile candidate) {
    if (!baseline.countsKnown() && !candidate.countsKnown()) {
      return "both sides";
    }
    return baseline.countsKnown() ? "the candidate" : "the baseline";
  }

  private static List<String> comparabilityNotes(
      RecordingProfile baseline, RecordingProfile candidate, boolean inclusive) {
    List<String> notes = new ArrayList<>();

    if (baseline.eventType() == null || candidate.eventType() == null) {
      notes.add(
          "No execution samples in "
              + (baseline.eventType() == null ? "baseline" : "candidate")
              + ": per-frame CPU comparison is unavailable, event-rate comparison still applies.");
    } else if (!baseline.format().equals(candidate.format())) {
      notes.add(
          "Different formats ("
              + baseline.format()
              + " "
              + baseline.eventType()
              + " vs "
              + candidate.format()
              + " "
              + candidate.eventType()
              + "): frame shares are compared, sample counts depend on each profiler's interval,"
              + " and event rates need two JFR recordings.");
    } else if (!baseline.eventType().equals(candidate.eventType())) {
      notes.add(
          baseline.isJfr()
              ? "Different execution sample event types ("
                  + baseline.eventType()
                  + " vs "
                  + candidate.eventType()
                  + "): the two recordings used different profilers, so frame shares are only"
                  + " loosely comparable and sample counts are not comparable at all."
              : "Different sample types ("
                  + baseline.eventType()
                  + " vs "
                  + candidate.eventType()
                  + "): frame shares are only loosely comparable.");
    }

    if (!baseline.isJfr() || !candidate.isJfr()) {
      if (baseline.durationSeconds() <= 0 || candidate.durationSeconds() <= 0) {
        notes.add("Could not establish a duration for both profiles.");
      }
    } else if (baseline.durationSeconds() <= 0 || candidate.durationSeconds() <= 0) {
      notes.add(
          "Could not establish an observed duration for both recordings; rates are omitted"
              + " where the denominator is unknown.");
    } else {
      double ratio =
          Math.max(baseline.durationSeconds(), candidate.durationSeconds())
              / Math.min(baseline.durationSeconds(), candidate.durationSeconds());
      if (ratio > DURATION_RATIO_WARN) {
        notes.add(
            String.format(
                Locale.ROOT,
                "Observed durations differ by %.1fx (%.1fs vs %.1fs): rates are normalised, but"
                    + " a much shorter recording may simply have missed periodic work.",
                ratio,
                baseline.durationSeconds(),
                candidate.durationSeconds()));
      }
    }

    for (RecordingProfile side : List.of(baseline, candidate)) {
      if (side.eventType() != null && !side.countsKnown()) {
        notes.add(
            "Observation counts unknown for the "
                + (side == baseline ? "baseline" : "candidate")
                + " ("
                + sampler(side)
                + " has no count column, and no period in its unit to divide by): shares are"
                + " reported, but no change is called significant.");
      }
    }
    if (inclusive) {
      for (RecordingProfile side : List.of(baseline, candidate)) {
        if (!side.truncationKnown()) {
          notes.add(
              "Stack truncation unknown for the "
                  + (side == baseline ? "baseline" : "candidate")
                  + " ("
                  + side.format()
                  + " records no truncation): if the profiler cut deep stacks, the outermost"
                  + " frames' inclusive shares are understated. Inclusive changes are still"
                  + " judged.");
        }
      }
    }

    if (baseline.countsKnown() && candidate.countsKnown()) {
      if (!hasSufficientSamples(baseline, candidate)) {
        notes.add(
            String.format(
                Locale.ROOT,
                "Too few samples to judge changes (baseline %,d, candidate %,d, need %d in each):"
                    + " no regression is raised, and none being raised is not evidence of no"
                    + " change.",
                baseline.sampleCount(),
                candidate.sampleCount(),
                MIN_RECORDING_SAMPLES));
      } else if (baseline.sampleCount() < 1000 || candidate.sampleCount() < 1000) {
        notes.add(
            String.format(
                Locale.ROOT,
                "Low sample count (baseline %,d, candidate %,d): per-frame shares are noisy below"
                    + " a few thousand samples, so treat small moves as inconclusive.",
                baseline.sampleCount(),
                candidate.sampleCount()));
      }
    }
    return notes;
  }

  private static List<Map<String, Object>> compareMetrics(
      RecordingProfile baseline, RecordingProfile candidate) {
    List<Map<String, Object>> metrics = new ArrayList<>();

    metrics.add(
        rateMetric(
            "totalEvents",
            baseline.totalEvents(),
            candidate.totalEvents(),
            baseline,
            candidate,
            "events/s"));

    // Event types worth comparing as rates. Absolute counts across recordings of different
    // lengths are meaningless, so every one of these is normalised per second.
    for (String type : TRACKED_EVENT_TYPES) {
      long baseCount = baseline.eventCounts().getOrDefault(type, 0L);
      long candCount = candidate.eventCounts().getOrDefault(type, 0L);
      if (baseCount == 0 && candCount == 0) {
        continue;
      }
      metrics.add(rateMetric(type, baseCount, candCount, baseline, candidate, "events/s"));
    }

    return metrics;
  }

  private static Map<String, Object> rateMetric(
      String name,
      long baseCount,
      long candCount,
      RecordingProfile baseline,
      RecordingProfile candidate,
      String unit) {
    double baseRate = baseline.ratePerSecond(baseCount);
    double candRate = candidate.ratePerSecond(candCount);

    Map<String, Object> metric = new LinkedHashMap<>();
    metric.put("name", name);
    metric.put("unit", unit);
    metric.put("baselineCount", baseCount);
    metric.put("candidateCount", candCount);
    metric.put("baselineRate", round(baseRate, 3));
    metric.put("candidateRate", round(candRate, 3));
    metric.put("deltaRate", round(candRate - baseRate, 3));
    if (baseRate > 0) {
      metric.put("deltaPct", round((candRate - baseRate) * 100.0 / baseRate, 1));
    } else if (candRate > 0) {
      metric.put("deltaPct", null);
      metric.put("note", "absent in baseline");
    }
    return metric;
  }

  /**
   * One frame list: self time ({@code shareKey} {@code SelfPct}) or inclusive time ({@code
   * TotalPct}). Shares come from the weights, significance from the counts. With {@code judge}
   * false no row is significant: every move is reported as {@code inconclusive}. A side whose
   * counts are not observations reports {@code null} samples.
   */
  private static List<Map<String, Object>> compareFrames(
      RecordingProfile baseline,
      RecordingProfile candidate,
      boolean inclusive,
      String shareKey,
      boolean judge,
      double minDeltaPct,
      int limit) {
    Map<String, Long> baseWeights =
        inclusive ? baseline.inclusiveFrameWeights() : baseline.leafFrameWeights();
    Map<String, Long> candWeights =
        inclusive ? candidate.inclusiveFrameWeights() : candidate.leafFrameWeights();
    Map<String, Long> baseCounts =
        inclusive ? baseline.inclusiveFrameCounts() : baseline.leafFrameCounts();
    Map<String, Long> candCounts =
        inclusive ? candidate.inclusiveFrameCounts() : candidate.leafFrameCounts();
    Set<String> allFrames = new HashSet<>(baseWeights.keySet());
    allFrames.addAll(candWeights.keySet());

    List<Map<String, Object>> changed = new ArrayList<>();
    for (String frame : allFrames) {
      double basePct = share(baseWeights.getOrDefault(frame, 0L), baseline.totalWeight());
      double candPct = share(candWeights.getOrDefault(frame, 0L), candidate.totalWeight());
      double delta = candPct - basePct;
      if (Math.abs(delta) < minDeltaPct) {
        continue;
      }
      long baseSamples = baseCounts.getOrDefault(frame, 0L);
      long candSamples = candCounts.getOrDefault(frame, 0L);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("method", frame);
      row.put("baseline" + shareKey, round(basePct, 2));
      row.put("candidate" + shareKey, round(candPct, 2));
      row.put("deltaPct", round(delta, 2));
      row.put("baselineSamples", baseline.countsKnown() ? baseSamples : null);
      row.put("candidateSamples", candidate.countsKnown() ? candSamples : null);
      boolean significant = judge && isSignificant(baseline, candidate, baseSamples, candSamples);
      row.put(
          "direction", !significant ? "inconclusive" : delta > 0 ? "regression" : "improvement");
      row.put("significant", significant);
      if (basePct == 0.0) {
        row.put("note", "not present in baseline");
      } else if (candPct == 0.0) {
        row.put("note", "gone in candidate");
      }
      row.put("type", JfrAnalyses.isNativeFrame(frame) ? "native" : "java");
      changed.add(row);
    }

    // Significant rows first: findings come from this list, so noise must not push them past limit.
    changed.sort(
        Comparator.comparing((Map<String, Object> row) -> !(Boolean) row.get("significant"))
            .thenComparing(
                Comparator.comparingDouble(
                        (Map<String, Object> row) ->
                            Math.abs(((Number) row.get("deltaPct")).doubleValue()))
                    .reversed()));

    return changed.size() > limit ? new ArrayList<>(changed.subList(0, limit)) : changed;
  }

  private static double share(long frameWeight, long totalWeight) {
    return totalWeight <= 0 ? 0.0 : frameWeight * 100.0 / totalWeight;
  }

  /**
   * @param notJudged why no change could be judged significant, or {@code null} when they could be
   */
  private static List<Finding> findings(
      RecordingProfile baseline,
      RecordingProfile candidate,
      List<Map<String, Object>> frames,
      List<Map<String, Object>> metrics,
      String notJudged) {
    List<Finding> findings = new ArrayList<>();
    boolean sufficientSamples = hasSufficientSamples(baseline, candidate);

    for (Map<String, Object> frame : frames) {
      double delta = ((Number) frame.get("deltaPct")).doubleValue();
      if (delta <= 0 || !(Boolean) frame.get("significant")) {
        continue;
      }
      String method = String.valueOf(frame.get("method"));
      findings.add(
          Finding.of("regression", "frame-" + method)
              .severity(delta >= 5.0 ? Finding.Severity.WARNING : Finding.Severity.INFO)
              .title(
                  "%s grew from %.2f%% to %.2f%% of samples (+%.2f points)",
                  method,
                  ((Number) frame.get("baselineSelfPct")).doubleValue(),
                  ((Number) frame.get("candidateSelfPct")).doubleValue(),
                  delta)
              .description(
                  "Self time share of execution samples. A share change is not a wall-clock"
                      + " change: confirm against the event rates before calling it a slowdown.")
              .source(SOURCE)
              .evidence("method", method)
              .evidence("baselineSelfPct", frame.get("baselineSelfPct"))
              .evidence("candidateSelfPct", frame.get("candidateSelfPct"))
              .evidence("deltaPct", delta)
              .evidence("baselineSamples", frame.get("baselineSamples"))
              .evidence("candidateSamples", frame.get("candidateSamples"))
              .action("Inspect the call paths reaching this frame in both recordings")
              .build());
    }

    for (Map<String, Object> metric : metrics) {
      Object deltaPctObj = metric.get("deltaPct");
      if (!sufficientSamples || !(deltaPctObj instanceof Number deltaPct)) {
        continue;
      }
      String name = String.valueOf(metric.get("name"));
      // A rate change worth naming: more than half again as often, and not a trickle.
      double candidateRate = ((Number) metric.get("candidateRate")).doubleValue();
      if (deltaPct.doubleValue() >= 50.0 && candidateRate >= 1.0) {
        findings.add(
            Finding.of("regression", "rate-" + name)
                .warning()
                .title(
                    "%s rate rose %.0f%% (%.2f/s to %.2f/s)",
                    name,
                    deltaPct.doubleValue(),
                    ((Number) metric.get("baselineRate")).doubleValue(),
                    candidateRate)
                .source(SOURCE)
                .evidence("metric", name)
                .evidence("baselineRate", metric.get("baselineRate"))
                .evidence("candidateRate", metric.get("candidateRate"))
                .evidence("deltaPct", deltaPct)
                .build());
      }
    }

    if (notJudged != null) {
      findings.add(
          Finding.of("regression", "not-judged")
              .info()
              .title("No change was judged: %s", notJudged)
              .description(
                  "Shares are reported, but whether a move exceeds sampling noise could not be"
                      + " decided. This comparison neither shows a regression nor rules one out.")
              .source(SOURCE)
              .evidence("baselineSampler", sampler(baseline))
              .evidence("candidateSampler", sampler(candidate))
              .action("Compare two profiles of the same kind, with sample counts")
              .build());
    } else if (!sufficientSamples) {
      findings.add(
          Finding.of("regression", "insufficient-samples")
              .info()
              .title(
                  "Too few samples to call a regression (baseline %,d, candidate %,d)",
                  baseline.sampleCount(), candidate.sampleCount())
              .description(
                  "Each recording needs at least "
                      + MIN_RECORDING_SAMPLES
                      + " execution samples before a change can be told from sampling noise. This"
                      + " comparison is inconclusive: it neither shows a regression nor rules one"
                      + " out.")
              .source(SOURCE)
              .evidence("baselineSamples", baseline.sampleCount())
              .evidence("candidateSamples", candidate.sampleCount())
              .evidence("minSamplesPerRecording", MIN_RECORDING_SAMPLES)
              .action("Record both runs for longer, or under load, and compare again")
              .build());
    } else if (findings.isEmpty()) {
      findings.add(
          Finding.of("regression", "none")
              .info()
              .title("No regression above the noise floor")
              .description(
                  "No frame moved by more than both the configured minDeltaPct and sampling noise,"
                      + " and no tracked event rate rose by half again. That is not proof of"
                      + " equivalence: a change smaller than sampling noise cannot be seen this"
                      + " way.")
              .source(SOURCE)
              .evidence("baselineSamples", baseline.sampleCount())
              .evidence("candidateSamples", candidate.sampleCount())
              .build());
    }

    return Collections.unmodifiableList(Findings.merge(findings));
  }

  static Double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
