package io.jafar.shell.core.compare;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.analysis.JfrAnalyses;
import io.jafar.shell.core.analysis.JfrQuerySource;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reads JFR recordings for a comparison, and compares two of them: the reader behind {@code
 * jfr_compare}, with no transport attached. The comparison itself is {@link ProfileCompare}'s,
 * which every format shares; this class reads a recording into a {@link RecordingProfile} and
 * delegates.
 *
 * <p>It was written inside {@code jfr-mcp}'s tool handler, reachable only by speaking MCP, so CI
 * could not compare two recordings headless. Here it returns data; {@code jfr_compare} wraps that
 * data in its protocol, and {@code jfr-shell compare} prints it.
 *
 * <p>Every execution sample weighs 1: a {@code datadog.ExecutionSample}'s {@code weight} field is
 * not read, so shares are shares of samples.
 */
public final class JfrCompare implements ProfileReader {

  /** {@link ProfileCompare#DEFAULT_MIN_DELTA_PCT}. */
  public static final double DEFAULT_MIN_DELTA_PCT = ProfileCompare.DEFAULT_MIN_DELTA_PCT;

  /** {@link ProfileCompare#MIN_RECORDING_SAMPLES}. */
  public static final long MIN_RECORDING_SAMPLES = ProfileCompare.MIN_RECORDING_SAMPLES;

  /** {@link ProfileCompare#MIN_FRAME_SAMPLES}. */
  public static final long MIN_FRAME_SAMPLES = ProfileCompare.MIN_FRAME_SAMPLES;

  /** {@link ProfileCompare#MIN_SIGNIFICANCE_Z}. */
  public static final double MIN_SIGNIFICANCE_Z = ProfileCompare.MIN_SIGNIFICANCE_Z;

  /** {@link ProfileCompare#DURATION_RATIO_WARN}. */
  public static final double DURATION_RATIO_WARN = ProfileCompare.DURATION_RATIO_WARN;

  /** {@link ProfileCompare#DEFAULT_LIMIT}. */
  public static final int DEFAULT_LIMIT = ProfileCompare.DEFAULT_LIMIT;

  /** {@link ProfileCompare#MAX_TRUNCATED_PCT_FOR_INCLUSIVE}. */
  public static final double MAX_TRUNCATED_PCT_FOR_INCLUSIVE =
      ProfileCompare.MAX_TRUNCATED_PCT_FOR_INCLUSIVE;

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

  /** {@link FrameNames#stableName}. */
  static String stableName(String frame) {
    return FrameNames.stableName(frame);
  }

  @Override
  public String format() {
    return RecordingProfile.JFR;
  }

  /**
   * The event types in {@code file} that a comparison can read as samples: those whose sampler kind
   * is known, with at least one event, by name.
   */
  @Override
  public List<SampleTypeInfo> sampleTypes(Path file) throws Exception {
    try (JFRSession session = new JFRSession(file, ParsingContext.create())) {
      List<SampleTypeInfo> types = new ArrayList<>();
      for (Map.Entry<String, Long> type :
          new TreeMap<>(source.countAllEventTypes(session)).entrySet()) {
        SamplerKind kind = SamplerKind.forJfrEventType(type.getKey());
        if (kind != SamplerKind.UNKNOWN && type.getValue() > 0) {
          types.add(new SampleTypeInfo(type.getKey(), null, kind));
        }
      }
      return types;
    }
  }

  /** Opens {@code file}, reads it as {@link #profile} does, and closes it. */
  @Override
  public RecordingProfile read(Path file, ProfileSelection selection) throws Exception {
    try (JFRSession session = new JFRSession(file, ParsingContext.create())) {
      return profile(
          new AnalysisTarget(0, file, session), selection.sampleType(), selection.inclusive());
    }
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
              List<String> frames = frameKeys(event, null);
              if (!frames.isEmpty()) {
                leafFrames.merge(frames.get(0), 1L, Long::sum);
                // Once per sample: a recursive method is on the stack, not on it twice as often.
                for (String frame : new HashSet<>(frames)) {
                  inclusiveFrames.merge(frame, 1L, Long::sum);
                }
              }
            } else {
              List<String> frames = frameKeys(event, 1);
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
   * JFR frame types whose "class" is not a Java class. async-profiler gives a C++ frame its library
   * as the class ({@code libjvm.so.Compile::Compile}); in its pprof output, and in a pprof profile
   * from any other tool, the same frame is {@code Compile::Compile}.
   */
  private static final java.util.Set<String> NATIVE_FRAME_TYPES =
      java.util.Set.of("Native", "C++", "Kernel");

  /** The sample's frames, leaf first, each keyed by {@link FrameNames#jfrKey}. */
  private List<String> frameKeys(Map<String, Object> event, Integer maxDepth) {
    return analyses.extractFrames(
        event,
        "bottom-up",
        maxDepth,
        frame ->
            analyses.extractFrameName(
                frame,
                isNativeFrameType(frame)
                    ? (cls, method) -> FrameNames.jfrKey("", method)
                    : FrameNames::jfrKey));
  }

  /** Whether {@code frame}'s JFR frame type is one of {@link #NATIVE_FRAME_TYPES}. */
  private boolean isNativeFrameType(Object frame) {
    if (!(frame instanceof Map<?, ?> frameMap)) {
      return false;
    }
    Object type = analyses.unwrapValue(frameMap.get("type"));
    if (type instanceof Map<?, ?> typeMap) {
      type = analyses.unwrapValue(typeMap.get("description"));
      if (type instanceof Map<?, ?> str) {
        type = str.get("string");
      }
    }
    return type != null && NATIVE_FRAME_TYPES.contains(type.toString());
  }

  /**
   * Compares two profiles already read: {@link ProfileCompare#compare}.
   *
   * @throws IllegalArgumentException when {@code options} asks for inclusive frames but a profile
   *     was read without counting them: its empty counts would read as "no caller moved"
   */
  public CompareResult compare(
      RecordingProfile baseline, RecordingProfile candidate, CompareOptions options) {
    return ProfileCompare.compare(baseline, candidate, options);
  }

  /** {@link ProfileCompare#hasSufficientSamples}. */
  public static boolean hasSufficientSamples(
      RecordingProfile baseline, RecordingProfile candidate) {
    return ProfileCompare.hasSufficientSamples(baseline, candidate);
  }

  /** {@link ProfileCompare#isSignificant}. */
  public static boolean isSignificant(
      RecordingProfile baseline, RecordingProfile candidate, long baseSamples, long candSamples) {
    return ProfileCompare.isSignificant(baseline, candidate, baseSamples, candSamples);
  }

  static Double round(double value, int decimals) {
    return ProfileCompare.round(value, decimals);
  }
}
