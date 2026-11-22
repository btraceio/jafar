package io.jafar.shell.core.compare;

import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.analysis.JfrAnalyses;
import io.jafar.shell.core.analysis.JfrQuerySource;
import io.jafar.shell.core.findings.Finding;
import io.jafar.shell.core.findings.Findings;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Compares a candidate recording against a baseline: the logic behind {@code jfr_compare}, with no
 * transport attached.
 *
 * <p>It was written inside {@code jfr-mcp}'s tool handler, reachable only by speaking MCP, so CI
 * could not compare two recordings headless. Here it returns data; {@code jfr_compare} wraps that
 * data in its protocol, and {@code jfr-shell compare} prints it.
 *
 * <p>The comparison normalises before it compares. Event counts become per-second rates using each
 * recording's own observed span, and stack frames are compared as a percentage of that recording's
 * samples rather than as sample counts, so two recordings profiled at different sampling intervals
 * remain comparable. Where the two recordings are not safely comparable at all — different
 * execution-sample event types, or wildly different durations — the result says so in {@code
 * comparability} instead of quietly producing a plausible number. A frame is only called a
 * regression when its move is significant given both recordings' sample counts; anything less is
 * {@code inconclusive}, and a recording too thin to judge at all says so in the findings.
 *
 * <p>Two frame lists can be compared. Self time (the leaf frame of each sample) is always compared.
 * Inclusive time (every frame on the stack, once per sample) is compared on request: it names a
 * caller whose own code is unchanged but whose callees got slower, which self time cannot.
 */
public final class JfrCompare {

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

  /** Findings' {@code source}: the comparison is {@code jfr_compare}'s, whoever runs it. */
  private static final String SOURCE = "jfr_compare";

  private final JfrQuerySource source;
  private final JfrAnalyses analyses;

  /** Reads recordings with the real JfrPath engine. */
  public JfrCompare() {
    this(JfrQuerySource.defaultSource());
  }

  /**
   * @param source how to read the recordings — injected, so a caller (the MCP server, a test) can
   *     substitute its own engine
   */
  public JfrCompare(JfrQuerySource source) {
    this.source = source;
    this.analyses = new JfrAnalyses(source);
  }

  /** Profiles both recordings and compares them. */
  public CompareResult compare(
      AnalysisTarget baseline, AnalysisTarget candidate, CompareOptions options) throws Exception {
    RecordingProfile base = profile(baseline, options.eventType(), options.inclusiveFrames());
    RecordingProfile cand = profile(candidate, options.eventType(), options.inclusiveFrames());
    return compare(base, cand, options);
  }

  /**
   * Reads what one recording contributes to a comparison, in one pass over its execution samples
   * plus one count of every event type.
   *
   * @param target the recording
   * @param requestedEventType the execution-sample event type, or {@code null} or blank to detect
   *     it
   * @param inclusive whether to also count inclusive frames (a full stack walk per sample)
   */
  public RecordingProfile profile(
      AnalysisTarget target, String requestedEventType, boolean inclusive) throws Exception {
    Map<String, Long> eventCounts = source.countAllEventTypes(target.session());
    long totalEvents = eventCounts.values().stream().mapToLong(Long::longValue).sum();

    String eventType =
        requestedEventType != null && !requestedEventType.isBlank()
            ? requestedEventType
            : analyses.detectExecutionEventType(target);

    // The source calls back from one thread per chunk, concurrently.
    Map<String, Long> leafFrames = new ConcurrentHashMap<>();
    Map<String, Long> inclusiveFrames = new ConcurrentHashMap<>();
    LongAdder samples = new LongAdder();
    LongAdder truncated = new LongAdder();
    AtomicLong minStart = new AtomicLong(Long.MAX_VALUE);
    AtomicLong maxStart = new AtomicLong(Long.MIN_VALUE);

    if (eventType != null) {
      JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType);
      source.consume(
          target.session(),
          parsed,
          event -> {
            samples.increment();
            if (event.get("startTime") instanceof Number startTime) {
              long value = startTime.longValue();
              minStart.accumulateAndGet(value, Math::min);
              maxStart.accumulateAndGet(value, Math::max);
            }
            if (inclusive) {
              if (event.get("stackTrace") instanceof Map<?, ?> stackTrace
                  && Boolean.TRUE.equals(stackTrace.get("truncated"))) {
                truncated.increment();
              }
              List<String> frames = analyses.extractFrames(event, "bottom-up", null);
              if (!frames.isEmpty()) {
                leafFrames.merge(frames.get(0), 1L, Long::sum);
                // Once per sample: a recursive method is on the stack, not on it twice as often.
                for (String frame : new HashSet<>(frames)) {
                  inclusiveFrames.merge(frame, 1L, Long::sum);
                }
              }
            } else {
              List<String> frames = analyses.extractFrames(event, "bottom-up", 1);
              if (!frames.isEmpty()) {
                leafFrames.merge(frames.get(0), 1L, Long::sum);
              }
            }
          });
    }

    // The observed span of the sampled event type is the denominator for rates. It is a lower
    // bound on the recording's wall clock, which is the right choice here: it is the interval
    // over which we actually have evidence.
    double durationSeconds = 0.0;
    if (minStart.get() != Long.MAX_VALUE && maxStart.get() > minStart.get()) {
      durationSeconds = (maxStart.get() - minStart.get()) / 1_000_000_000.0;
    }

    return new RecordingProfile(
        target.recordingPath(),
        eventType,
        samples.sum(),
        durationSeconds,
        leafFrames,
        inclusiveFrames,
        inclusive,
        truncated.sum(),
        eventCounts,
        totalEvents);
  }

  /**
   * Compares two profiles already read.
   *
   * @throws IllegalArgumentException when {@code options} asks for inclusive frames but a profile
   *     was read without counting them: its empty counts would read as "no caller moved"
   */
  public CompareResult compare(
      RecordingProfile baseline, RecordingProfile candidate, CompareOptions options) {
    if (options.inclusiveFrames()
        && !(baseline.inclusiveCounted() && candidate.inclusiveCounted())) {
      throw new IllegalArgumentException(
          "inclusive frames were requested, but the "
              + (baseline.inclusiveCounted() ? "candidate" : "baseline")
              + " profile was read without counting them: read both with profile(..., true)");
    }
    List<String> comparability = comparabilityNotes(baseline, candidate);
    boolean judgeInclusive = true;
    if (options.inclusiveFrames()
        && (baseline.truncatedPct() > MAX_TRUNCATED_PCT_FOR_INCLUSIVE
            || candidate.truncatedPct() > MAX_TRUNCATED_PCT_FOR_INCLUSIVE)) {
      judgeInclusive = false;
      comparability.add(
          String.format(
              Locale.ROOT,
              "Truncated stacks (baseline %.1f%%, candidate %.1f%% of samples, above %.1f%%):"
                  + " JFR cut them at its stack depth, so the outermost frames' inclusive shares"
                  + " are understated and no inclusive change is called significant. Record with"
                  + " -XX:FlightRecorderOptions:stackdepth=2048 to keep deeper stacks.",
              baseline.truncatedPct(),
              candidate.truncatedPct(),
              MAX_TRUNCATED_PCT_FOR_INCLUSIVE));
      comparability.remove("Recordings appear comparable.");
    }
    List<Map<String, Object>> metrics = compareMetrics(baseline, candidate);
    List<Map<String, Object>> frames =
        compareFrames(
            baseline,
            candidate,
            baseline.leafFrameCounts(),
            candidate.leafFrameCounts(),
            "SelfPct",
            true,
            options.minDeltaPct(),
            options.limit());
    List<Map<String, Object>> inclusive =
        options.inclusiveFrames()
            ? compareFrames(
                baseline,
                candidate,
                baseline.inclusiveFrameCounts(),
                candidate.inclusiveFrameCounts(),
                "TotalPct",
                judgeInclusive,
                options.minDeltaPct(),
                options.limit())
            : List.of();
    boolean sufficient = hasSufficientSamples(baseline, candidate);
    List<Finding> findings = findings(baseline, candidate, frames, metrics);
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
   * them, and the two-proportion z score reaches {@link #MIN_SIGNIFICANCE_Z}.
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

  private static List<String> comparabilityNotes(
      RecordingProfile baseline, RecordingProfile candidate) {
    List<String> notes = new ArrayList<>();

    if (baseline.eventType() == null || candidate.eventType() == null) {
      notes.add(
          "No execution samples in "
              + (baseline.eventType() == null ? "baseline" : "candidate")
              + ": per-frame CPU comparison is unavailable, event-rate comparison still applies.");
    } else if (!baseline.eventType().equals(candidate.eventType())) {
      notes.add(
          "Different execution sample event types ("
              + baseline.eventType()
              + " vs "
              + candidate.eventType()
              + "): the two recordings used different profilers, so frame shares are only"
              + " loosely comparable and sample counts are not comparable at all.");
    }

    if (baseline.durationSeconds() <= 0 || candidate.durationSeconds() <= 0) {
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

    if (!hasSufficientSamples(baseline, candidate)) {
      notes.add(
          String.format(
              Locale.ROOT,
              "Too few samples to judge changes (baseline %,d, candidate %,d, need %d in each):"
                  + " no regression is raised, and none being raised is not evidence of no change.",
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

    if (notes.isEmpty()) {
      notes.add("Recordings appear comparable.");
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
   * TotalPct}). The row keys and the significance rule are the same for both; only the counts and
   * the share's name differ. With {@code judge} false no row is significant: every move is reported
   * as {@code inconclusive}.
   */
  private List<Map<String, Object>> compareFrames(
      RecordingProfile baseline,
      RecordingProfile candidate,
      Map<String, Long> baseCounts,
      Map<String, Long> candCounts,
      String shareKey,
      boolean judge,
      double minDeltaPct,
      int limit) {
    Set<String> allFrames = new HashSet<>(baseCounts.keySet());
    allFrames.addAll(candCounts.keySet());

    List<Map<String, Object>> changed = new ArrayList<>();
    for (String frame : allFrames) {
      long baseSamples = baseCounts.getOrDefault(frame, 0L);
      long candSamples = candCounts.getOrDefault(frame, 0L);
      double basePct = share(baseSamples, baseline.sampleCount());
      double candPct = share(candSamples, candidate.sampleCount());
      double delta = candPct - basePct;
      if (Math.abs(delta) < minDeltaPct) {
        continue;
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("method", frame);
      row.put("baseline" + shareKey, round(basePct, 2));
      row.put("candidate" + shareKey, round(candPct, 2));
      row.put("deltaPct", round(delta, 2));
      row.put("baselineSamples", baseSamples);
      row.put("candidateSamples", candSamples);
      boolean significant = judge && isSignificant(baseline, candidate, baseSamples, candSamples);
      row.put(
          "direction", !significant ? "inconclusive" : delta > 0 ? "regression" : "improvement");
      row.put("significant", significant);
      if (basePct == 0.0) {
        row.put("note", "not present in baseline");
      } else if (candPct == 0.0) {
        row.put("note", "gone in candidate");
      }
      row.put("type", analyses.isNativeMethod(frame) ? "native" : "java");
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

  private static double share(long frameSamples, long sampleCount) {
    return sampleCount <= 0 ? 0.0 : frameSamples * 100.0 / sampleCount;
  }

  private static List<Finding> findings(
      RecordingProfile baseline,
      RecordingProfile candidate,
      List<Map<String, Object>> frames,
      List<Map<String, Object>> metrics) {
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

    if (!sufficientSamples) {
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
