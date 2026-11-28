package io.jafar.shell.core.compare;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one recording or profile file contributes to a comparison.
 *
 * <p>Two things are kept per frame. <b>Weights</b> give the shares: a frame's weight over the total
 * weight, in the sample type's own unit (nanoseconds of CPU, bytes, samples). <b>Counts</b> are
 * observations, how many samples saw the frame, and only they feed the significance test: a
 * nanosecond is not a trial. For JFR both are one per event, so they are the same maps. For a pprof
 * profile whose observations cannot be derived ({@code countsKnown} false), the counts are not
 * observations, and no change is called significant.
 *
 * @param recordingPath the file the profile was read from; may be {@code null} for a profile built
 *     from something other than a file
 * @param eventType the sample type that was read: for JFR the execution-sample event type, for
 *     pprof {@code type/unit}; {@code null} when the recording has none
 * @param sampleCount how many samples were read: observations when {@code countsKnown}, otherwise
 *     the number of sample records, each of which may stand for several observations
 * @param durationSeconds for JFR the span between the first and last sample's start time; for pprof
 *     the profile's {@code duration_nanos}. The denominator for every rate. {@code 0} when it could
 *     not be established
 * @param leafFrameCounts per frame, the samples whose leaf (top) frame it is: self time
 * @param inclusiveFrameCounts per frame, the samples whose stack contains it, counted once per
 *     sample even under recursion: total time. Empty when inclusive frames were not counted
 * @param inclusiveCounted whether inclusive frames were counted; when {@code false}, {@code
 *     inclusiveFrameCounts} is empty because nothing was counted, not because no frame was seen
 * @param truncatedSamples how many samples had a stack JFR cut at its stack depth (64 frames unless
 *     {@code -XX:FlightRecorderOptions:stackdepth} raised it). Such a stack lacks its outermost
 *     frames, so their inclusive share is understated. Counted only with {@code inclusiveCounted};
 *     {@code 0} otherwise, and {@code 0} when {@code truncationKnown} is false
 * @param eventCounts per event type, the events in the recording; empty outside JFR
 * @param totalEvents the sum of {@code eventCounts}
 * @param format the file's format: {@code jfr} or {@code pprof}
 * @param sampleUnit the unit of the weights: {@code count} for JFR
 * @param samplerKind what the samples measure
 * @param totalWeight the sum of every sample's weight: the denominator of every share
 * @param leafFrameWeights per frame, the weight of the samples whose leaf frame it is
 * @param inclusiveFrameWeights per frame, the weight of the samples whose stack contains it, once
 *     per sample
 * @param countsKnown whether {@code sampleCount} and the frame counts are observations
 * @param truncationKnown whether the format says when a stack was cut; pprof does not
 * @param sampleTypeReason how the sample type was chosen ("requested", "first CPU sample type",
 *     ...); {@code null} when there was no choice to explain
 */
public record RecordingProfile(
    Path recordingPath,
    String eventType,
    long sampleCount,
    double durationSeconds,
    Map<String, Long> leafFrameCounts,
    Map<String, Long> inclusiveFrameCounts,
    boolean inclusiveCounted,
    long truncatedSamples,
    Map<String, Long> eventCounts,
    long totalEvents,
    String format,
    String sampleUnit,
    SamplerKind samplerKind,
    long totalWeight,
    Map<String, Long> leafFrameWeights,
    Map<String, Long> inclusiveFrameWeights,
    boolean countsKnown,
    boolean truncationKnown,
    String sampleTypeReason) {

  /** The format of a profile read from a JFR recording. */
  public static final String JFR = "jfr";

  /** Copies the maps, so a profile cannot change after it is read. Iteration order is kept. */
  public RecordingProfile {
    leafFrameCounts = copy(leafFrameCounts);
    inclusiveFrameCounts = copy(inclusiveFrameCounts);
    eventCounts = copy(eventCounts);
    leafFrameWeights = copy(leafFrameWeights);
    inclusiveFrameWeights = copy(inclusiveFrameWeights);
    if (format == null) {
      format = JFR;
    }
    if (samplerKind == null) {
      samplerKind = SamplerKind.UNKNOWN;
    }
  }

  /**
   * A JFR profile: every sample weighs 1, so the weights are the counts, and the sampler kind
   * follows from {@code eventType}.
   */
  public RecordingProfile(
      Path recordingPath,
      String eventType,
      long sampleCount,
      double durationSeconds,
      Map<String, Long> leafFrameCounts,
      Map<String, Long> inclusiveFrameCounts,
      boolean inclusiveCounted,
      long truncatedSamples,
      Map<String, Long> eventCounts,
      long totalEvents) {
    this(
        recordingPath,
        eventType,
        sampleCount,
        durationSeconds,
        leafFrameCounts,
        inclusiveFrameCounts,
        inclusiveCounted,
        truncatedSamples,
        eventCounts,
        totalEvents,
        JFR,
        "count",
        SamplerKind.forJfrEventType(eventType),
        sampleCount,
        leafFrameCounts,
        inclusiveFrameCounts,
        true,
        true,
        null);
  }

  private static Map<String, Long> copy(Map<String, Long> counts) {
    return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
  }

  /** Whether this profile was read from a JFR recording. */
  public boolean isJfr() {
    return JFR.equals(format);
  }

  /** Share of samples whose stack was truncated, in percentage points. */
  public double truncatedPct() {
    return sampleCount <= 0 ? 0.0 : truncatedSamples * 100.0 / sampleCount;
  }

  /** Share of the total weight whose leaf frame is {@code frame}, in percentage points. */
  public double selfPct(String frame) {
    return pct(leafFrameWeights, frame);
  }

  /** Share of the total weight whose stack contains {@code frame}, in percentage points. */
  public double totalPct(String frame) {
    return pct(inclusiveFrameWeights, frame);
  }

  /** {@code count} per second of this recording's observed duration; {@code 0} when unknown. */
  public double ratePerSecond(long count) {
    return durationSeconds > 0 ? count / durationSeconds : 0.0;
  }

  private double pct(Map<String, Long> weights, String frame) {
    if (totalWeight <= 0) {
      return 0.0;
    }
    return weights.getOrDefault(frame, 0L) * 100.0 / totalWeight;
  }
}
