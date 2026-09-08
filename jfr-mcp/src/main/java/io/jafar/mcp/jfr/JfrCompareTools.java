package io.jafar.mcp.jfr;

import io.jafar.mcp.findings.Finding;
import io.jafar.mcp.findings.Findings;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.jfrpath.JfrPath;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jfr_compare} — compares a candidate recording against a baseline.
 *
 * <p>"Is this build slower than the last one, and where?" was previously unanswerable in a single
 * call: JfrPath has no cross-recording join, so a comparison meant running every analysis twice and
 * diffing the numbers by hand, which invites the classic mistake of comparing raw counts between
 * recordings of different lengths.
 *
 * <p>This tool normalises before it compares. Event counts become per-second rates using each
 * recording's own observed span, and stack frames are compared as a percentage of that recording's
 * samples rather than as sample counts, so two recordings profiled at different sampling intervals
 * remain comparable. Where the two recordings are not safely comparable at all — different
 * execution-sample event types, or wildly different durations — the result says so in {@code
 * comparability} instead of quietly producing a plausible number.
 */
public final class JfrCompareTools {

  private static final Logger LOG = LoggerFactory.getLogger(JfrCompareTools.class);

  /**
   * Frames whose share moved by less than this many percentage points are reported as noise. A
   * sampling profiler's per-frame share varies run to run even with no code change; a
   * sub-percentage-point move is not evidence of anything.
   */
  private static final double DEFAULT_MIN_DELTA_PCT = 1.0;

  /** Beyond this ratio between the two observed durations, rate comparisons get a warning. */
  private static final double DURATION_RATIO_WARN = 3.0;

  private final SessionRegistry sessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;
  private final McpResultFactory resultFactory;
  private final JfrAnalysisTools analysisTools;

  public JfrCompareTools(
      SessionRegistry sessionRegistry,
      QueryEvaluator evaluator,
      QueryParser queryParser,
      McpResultFactory resultFactory,
      JfrAnalysisTools analysisTools) {
    this.sessionRegistry = sessionRegistry;
    this.evaluator = evaluator;
    this.queryParser = queryParser;
    this.resultFactory = resultFactory;
    this.analysisTools = analysisTools;
  }

  public McpServerFeatures.SyncToolSpecification createJfrCompareTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "baselineSessionId": {
              "type": "string",
              "description": "Session ID or alias of the baseline (the 'before' recording). Required."
            },
            "candidateSessionId": {
              "type": "string",
              "description": "Session ID or alias of the candidate (the 'after' recording). Defaults to the current session."
            },
            "eventType": {
              "type": "string",
              "description": "Execution sample event type. Auto-detected per recording when omitted."
            },
            "minDeltaPct": {
              "type": "number",
              "description": "Noise floor in percentage points for per-frame changes (default: 1.0)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of changed frames to return (default: 25)"
            }
          },
          "required": ["baselineSessionId"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        Tool.builder()
            .name("jfr_compare")
            .description(
                "Compares a candidate JFR recording against a baseline and reports what changed: "
                    + "event rates per second, GC and exception rates, and per-frame CPU self-time "
                    + "shares. Normalises for recording duration and sampling rate, flags changes "
                    + "below the noise floor as insignificant, and warns when the two recordings "
                    + "are not comparable. Use for before/after regression checks; open both "
                    + "recordings with jfr_open first.")
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, args) -> handleJfrCompare(exchange, args.arguments()));
  }

  public CallToolResult handleJfrCompare(McpSyncServerExchange exchange, Map<String, Object> args) {
    String baselineId = (String) args.get("baselineSessionId");
    String candidateId = (String) args.get("candidateSessionId");
    String eventType = (String) args.get("eventType");
    double minDeltaPct =
        args.get("minDeltaPct") instanceof Number n ? n.doubleValue() : DEFAULT_MIN_DELTA_PCT;
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 25;

    if (baselineId == null || baselineId.isBlank()) {
      return resultFactory.error("baselineSessionId is required");
    }
    if (limit <= 0) {
      return resultFactory.error("limit must be positive");
    }

    try {
      SessionRegistry.SessionInfo baseline = sessionRegistry.getOrCurrent(baselineId);
      SessionRegistry.SessionInfo candidate = sessionRegistry.getOrCurrent(candidateId);

      if (baseline.id() == candidate.id()) {
        return resultFactory.error(
            "Baseline and candidate are the same session ("
                + baseline.id()
                + "). Open the second recording with jfr_open and pass both session ids.");
      }

      Profile baseProfile = profile(baseline, eventType);
      Profile candProfile = profile(candidate, eventType);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("baseline", describe(baseline, baseProfile));
      result.put("candidate", describe(candidate, candProfile));

      List<String> comparability = comparabilityNotes(baseProfile, candProfile);
      result.put("comparability", comparability);

      List<Map<String, Object>> metrics = compareMetrics(baseProfile, candProfile);
      result.put("metrics", metrics);

      List<Map<String, Object>> frames =
          compareFrames(baseProfile, candProfile, minDeltaPct, limit);
      result.put("frames", frames);
      result.put("minDeltaPct", minDeltaPct);

      List<Finding> findings = findings(baseProfile, candProfile, frames, metrics);
      result.put("findings", Findings.toMaps(findings));
      result.put("findingCounts", Findings.countBySeverity(findings));

      return resultFactory.success(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("Compare error: {}", e.getMessage());
      return resultFactory.error(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to compare recordings: {}", e.getMessage(), e);
      return resultFactory.error("Failed to compare recordings: " + e.getMessage());
    }
  }

  /** What a single recording contributes to the comparison. */
  private record Profile(
      SessionRegistry.SessionInfo session,
      String eventType,
      long sampleCount,
      double durationSeconds,
      Map<String, Long> leafFrameCounts,
      Map<String, Long> eventCounts,
      long totalEvents) {

    /** Share of samples whose leaf frame is {@code frame}, in percentage points. */
    double framePct(String frame) {
      if (sampleCount <= 0) {
        return 0.0;
      }
      return leafFrameCounts.getOrDefault(frame, 0L) * 100.0 / sampleCount;
    }

    double ratePerSecond(long count) {
      return durationSeconds > 0 ? count / durationSeconds : 0.0;
    }
  }

  private Profile profile(SessionRegistry.SessionInfo info, String requestedEventType)
      throws Exception {
    Map<String, Long> eventCounts = evaluator.countAllEventTypes(info.session());
    long totalEvents = eventCounts.values().stream().mapToLong(Long::longValue).sum();

    String eventType =
        requestedEventType != null && !requestedEventType.isBlank()
            ? requestedEventType
            : analysisTools.detectExecutionEventType(info);

    Map<String, Long> leafFrames = new ConcurrentHashMap<>();
    LongAdder samples = new LongAdder();
    AtomicLong minStart = new AtomicLong(Long.MAX_VALUE);
    AtomicLong maxStart = new AtomicLong(Long.MIN_VALUE);

    if (eventType != null) {
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);
      evaluator.consume(
          info.session(),
          parsed,
          event -> {
            samples.increment();
            if (event.get("startTime") instanceof Number startTime) {
              long value = startTime.longValue();
              minStart.accumulateAndGet(value, Math::min);
              maxStart.accumulateAndGet(value, Math::max);
            }
            List<String> frames = analysisTools.extractFrames(event, "bottom-up", 1);
            if (!frames.isEmpty()) {
              leafFrames.merge(frames.get(0), 1L, Long::sum);
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

    return new Profile(
        info, eventType, samples.sum(), durationSeconds, leafFrames, eventCounts, totalEvents);
  }

  private Map<String, Object> describe(SessionRegistry.SessionInfo info, Profile profile) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("sessionId", info.id());
    if (info.alias() != null) {
      map.put("alias", info.alias());
    }
    map.put("recordingPath", info.recordingPath().toString());
    map.put("eventType", profile.eventType());
    map.put("samples", profile.sampleCount());
    map.put("observedDurationSeconds", round(profile.durationSeconds(), 3));
    map.put("totalEvents", profile.totalEvents());
    return map;
  }

  private List<String> comparabilityNotes(Profile baseline, Profile candidate) {
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
                "Observed durations differ by %.1fx (%.1fs vs %.1fs): rates are normalised, but"
                    + " a much shorter recording may simply have missed periodic work.",
                ratio, baseline.durationSeconds(), candidate.durationSeconds()));
      }
    }

    if (baseline.sampleCount() < 1000 || candidate.sampleCount() < 1000) {
      notes.add(
          String.format(
              "Low sample count (baseline %,d, candidate %,d): per-frame shares are noisy below"
                  + " a few thousand samples, so treat small moves as inconclusive.",
              baseline.sampleCount(), candidate.sampleCount()));
    }

    if (notes.isEmpty()) {
      notes.add("Recordings appear comparable.");
    }
    return notes;
  }

  private List<Map<String, Object>> compareMetrics(Profile baseline, Profile candidate) {
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
    Set<String> interesting = new HashSet<>();
    interesting.addAll(baseline.eventCounts().keySet());
    interesting.retainAll(candidate.eventCounts().keySet());

    List<String> tracked =
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

    for (String type : tracked) {
      long baseCount = baseline.eventCounts().getOrDefault(type, 0L);
      long candCount = candidate.eventCounts().getOrDefault(type, 0L);
      if (baseCount == 0 && candCount == 0) {
        continue;
      }
      metrics.add(rateMetric(type, baseCount, candCount, baseline, candidate, "events/s"));
    }

    return metrics;
  }

  private Map<String, Object> rateMetric(
      String name,
      long baseCount,
      long candCount,
      Profile baseline,
      Profile candidate,
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

  private List<Map<String, Object>> compareFrames(
      Profile baseline, Profile candidate, double minDeltaPct, int limit) {
    Set<String> allFrames = new HashSet<>(baseline.leafFrameCounts().keySet());
    allFrames.addAll(candidate.leafFrameCounts().keySet());

    List<Map<String, Object>> changed = new ArrayList<>();
    for (String frame : allFrames) {
      double basePct = baseline.framePct(frame);
      double candPct = candidate.framePct(frame);
      double delta = candPct - basePct;
      if (Math.abs(delta) < minDeltaPct) {
        continue;
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("method", frame);
      row.put("baselineSelfPct", round(basePct, 2));
      row.put("candidateSelfPct", round(candPct, 2));
      row.put("deltaPct", round(delta, 2));
      row.put("baselineSamples", baseline.leafFrameCounts().getOrDefault(frame, 0L));
      row.put("candidateSamples", candidate.leafFrameCounts().getOrDefault(frame, 0L));
      row.put("direction", delta > 0 ? "regression" : "improvement");
      if (basePct == 0.0) {
        row.put("note", "not present in baseline");
      } else if (candPct == 0.0) {
        row.put("note", "gone in candidate");
      }
      row.put("type", analysisTools.isNativeMethod(frame) ? "native" : "java");
      changed.add(row);
    }

    changed.sort(
        Comparator.comparingDouble(
                (Map<String, Object> row) -> Math.abs(((Number) row.get("deltaPct")).doubleValue()))
            .reversed());

    return changed.size() > limit ? new ArrayList<>(changed.subList(0, limit)) : changed;
  }

  private List<Finding> findings(
      Profile baseline,
      Profile candidate,
      List<Map<String, Object>> frames,
      List<Map<String, Object>> metrics) {
    List<Finding> findings = new ArrayList<>();

    for (Map<String, Object> frame : frames) {
      double delta = ((Number) frame.get("deltaPct")).doubleValue();
      if (delta <= 0) {
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
              .source("jfr_compare")
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
      if (!(deltaPctObj instanceof Number deltaPct)) {
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
                .source("jfr_compare")
                .evidence("metric", name)
                .evidence("baselineRate", metric.get("baselineRate"))
                .evidence("candidateRate", metric.get("candidateRate"))
                .evidence("deltaPct", deltaPct)
                .build());
      }
    }

    if (findings.isEmpty()) {
      findings.add(
          Finding.of("regression", "none")
              .info()
              .title("No regression above the noise floor")
              .description(
                  "No frame moved by more than the configured minDeltaPct and no tracked event"
                      + " rate rose by half again. That is not proof of equivalence: a change"
                      + " smaller than sampling noise cannot be seen this way.")
              .source("jfr_compare")
              .evidence("baselineSamples", baseline.sampleCount())
              .evidence("candidateSamples", candidate.sampleCount())
              .build());
    }

    return Findings.merge(findings);
  }

  private static Double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
