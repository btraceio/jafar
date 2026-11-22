package io.jafar.shell.core.compare;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one recording contributes to a comparison.
 *
 * @param recordingPath the file the profile was read from; may be {@code null} for a profile built
 *     from something other than a file
 * @param eventType the execution-sample event type that was read, or {@code null} when the
 *     recording has none
 * @param sampleCount how many samples of {@code eventType} were read
 * @param durationSeconds the span between the first and last sample's start time; the denominator
 *     for every rate. {@code 0} when it could not be established
 * @param leafFrameCounts per frame, the samples whose leaf (top) frame it is: self time
 * @param inclusiveFrameCounts per frame, the samples whose stack contains it, counted once per
 *     sample even under recursion: total time. Empty when inclusive frames were not counted
 * @param inclusiveCounted whether inclusive frames were counted; when {@code false}, {@code
 *     inclusiveFrameCounts} is empty because nothing was counted, not because no frame was seen
 * @param truncatedSamples how many samples had a stack JFR cut at its stack depth (64 frames unless
 *     {@code -XX:FlightRecorderOptions:stackdepth} raised it). Such a stack lacks its outermost
 *     frames, so their inclusive share is understated. Counted only with {@code inclusiveCounted};
 *     {@code 0} otherwise
 * @param eventCounts per event type, the events in the recording
 * @param totalEvents the sum of {@code eventCounts}
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
    long totalEvents) {

  /** Copies the maps, so a profile cannot change after it is read. Iteration order is kept. */
  public RecordingProfile {
    leafFrameCounts = copy(leafFrameCounts);
    inclusiveFrameCounts = copy(inclusiveFrameCounts);
    eventCounts = copy(eventCounts);
  }

  private static Map<String, Long> copy(Map<String, Long> counts) {
    return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
  }

  /** Share of samples whose stack was truncated, in percentage points. */
  public double truncatedPct() {
    return sampleCount <= 0 ? 0.0 : truncatedSamples * 100.0 / sampleCount;
  }

  /** Share of samples whose leaf frame is {@code frame}, in percentage points. */
  public double selfPct(String frame) {
    return pct(leafFrameCounts, frame);
  }

  /** Share of samples whose stack contains {@code frame}, in percentage points. */
  public double totalPct(String frame) {
    return pct(inclusiveFrameCounts, frame);
  }

  /** {@code count} per second of this recording's observed duration; {@code 0} when unknown. */
  public double ratePerSecond(long count) {
    return durationSeconds > 0 ? count / durationSeconds : 0.0;
  }

  private double pct(Map<String, Long> counts, String frame) {
    if (sampleCount <= 0) {
      return 0.0;
    }
    return counts.getOrDefault(frame, 0L) * 100.0 / sampleCount;
  }
}
