package io.jafar.shell.core.compare;

import io.jafar.shell.core.findings.Finding;
import io.jafar.shell.core.findings.Findings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of comparing a candidate recording against a baseline.
 *
 * <p>The metric and frame rows are maps rather than records because their keys are a published
 * contract: they are exactly the rows {@code jfr_compare} has always returned, and a caller that
 * serialises them gets the same JSON.
 *
 * <p>Metric row keys: {@code name}, {@code unit}, {@code baselineCount}, {@code candidateCount},
 * {@code baselineRate}, {@code candidateRate}, {@code deltaRate}, and when a rate is known {@code
 * deltaPct} (with {@code note} when the baseline had none).
 *
 * <p>Self-time frame row keys: {@code method}, {@code baselineSelfPct}, {@code candidateSelfPct},
 * {@code deltaPct}, {@code baselineSamples}, {@code candidateSamples}, {@code direction} ({@code
 * regression}, {@code improvement} or {@code inconclusive}), {@code significant}, optionally {@code
 * note}, and {@code type} ({@code java} or {@code native}). Inclusive rows carry {@code
 * baselineTotalPct} and {@code candidateTotalPct} in place of the two self shares.
 *
 * @param baseline what the baseline recording contributed
 * @param candidate what the candidate recording contributed
 * @param comparability notes on whether the two recordings can be compared at all
 * @param metrics event rates per second, baseline against candidate
 * @param frames frames whose self-time share moved by at least {@code minDeltaPct}, significant
 *     first, then by size of move
 * @param inclusiveFrames the same for inclusive (total-time) shares, or {@code null} when they were
 *     not requested
 * @param minDeltaPct the noise floor that was applied
 * @param sufficientSamples whether both recordings reached {@code minSamplesPerRecording}
 * @param minSamplesPerRecording {@link JfrCompare#MIN_RECORDING_SAMPLES}
 * @param findings the judgement, from self-time frames and event rates
 */
public record CompareResult(
    RecordingProfile baseline,
    RecordingProfile candidate,
    List<String> comparability,
    List<Map<String, Object>> metrics,
    List<Map<String, Object>> frames,
    List<Map<String, Object>> inclusiveFrames,
    double minDeltaPct,
    boolean sufficientSamples,
    long minSamplesPerRecording,
    List<Finding> findings) {

  /**
   * One side's description, keyed as {@code jfr_compare} keys it: {@code recordingPath}, {@code
   * eventType}, {@code samples}, {@code observedDurationSeconds}, {@code totalEvents}. A caller
   * with session ids puts them in front.
   */
  public static Map<String, Object> describe(RecordingProfile profile) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(
        "recordingPath",
        profile.recordingPath() == null ? null : profile.recordingPath().toString());
    map.put("eventType", profile.eventType());
    map.put("samples", profile.sampleCount());
    map.put("observedDurationSeconds", JfrCompare.round(profile.durationSeconds(), 3));
    map.put("totalEvents", profile.totalEvents());
    return map;
  }

  /**
   * The whole result as one map, in {@code jfr_compare}'s key order: {@code baseline}, {@code
   * candidate}, {@code comparability}, {@code metrics}, {@code frames}, then {@code
   * inclusiveFrames} when present, {@code minDeltaPct}, {@code sufficientSamples}, {@code
   * minSamplesPerRecording}, {@code findings} and {@code findingCounts}.
   *
   * @param baselineDescription the {@code baseline} entry, normally {@link #describe} of the
   *     baseline, possibly with a caller's session id added
   * @param candidateDescription the {@code candidate} entry
   */
  public Map<String, Object> toMap(
      Map<String, Object> baselineDescription, Map<String, Object> candidateDescription) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("baseline", baselineDescription);
    result.put("candidate", candidateDescription);
    result.put("comparability", comparability);
    result.put("metrics", metrics);
    result.put("frames", frames);
    if (inclusiveFrames != null) {
      result.put("inclusiveFrames", inclusiveFrames);
    }
    result.put("minDeltaPct", minDeltaPct);
    result.put("sufficientSamples", sufficientSamples);
    result.put("minSamplesPerRecording", minSamplesPerRecording);
    result.put("findings", Findings.toMaps(findings));
    result.put("findingCounts", Findings.countBySeverity(findings));
    return result;
  }

  /** {@link #toMap(Map, Map)} with each side's plain {@link #describe} description. */
  public Map<String, Object> toMap() {
    return toMap(describe(baseline), describe(candidate));
  }
}
