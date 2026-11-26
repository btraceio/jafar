package io.jafar.shell.core.compare;

/**
 * What a comparison looks at and how strict it is.
 *
 * @param eventType the execution-sample event type to compare, or {@code null} to detect it per
 *     recording. Read by {@link JfrCompare} only; a pprof side's sample type is chosen when it is
 *     read ({@link ProfileSelection})
 * @param minDeltaPct noise floor in percentage points: a frame whose share moved by less is not
 *     reported at all. Not validated: {@code jfr_compare} has always accepted any number here
 * @param limit the most frame rows to return, per frame list
 * @param inclusiveFrames whether to also compare inclusive (total-time) frame shares; costs a full
 *     stack walk per sample instead of reading only the leaf frame
 * @param mixedSamplers what to do when the two sides' samplers measure different things
 */
public record CompareOptions(
    String eventType,
    double minDeltaPct,
    int limit,
    boolean inclusiveFrames,
    MixedSamplers mixedSamplers) {

  /**
   * Two profiles whose sampler kinds differ (CPU against wall clock, say). An allocation profile
   * against a CPU or wall-clock one is refused under every policy: the shares measure different
   * things.
   */
  public enum MixedSamplers {
    /**
     * {@code jfr_compare}'s behaviour, kept as it was: two JFR event types of different kinds get
     * the "different execution sample event types" note and are judged as usual.
     */
    NOTE,
    /** Refuse with an error naming both samplers: the default of the newer entry points. */
    REFUSE,
    /** Compare with a note; no change is called significant. */
    ALLOW
  }

  public CompareOptions {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (eventType != null && eventType.isBlank()) {
      eventType = null;
    }
    if (mixedSamplers == null) {
      mixedSamplers = MixedSamplers.NOTE;
    }
  }

  /** {@code jfr_compare}'s options: mixed samplers get a note, as they always have. */
  public CompareOptions(String eventType, double minDeltaPct, int limit, boolean inclusiveFrames) {
    this(eventType, minDeltaPct, limit, inclusiveFrames, MixedSamplers.NOTE);
  }

  /** Detected event type, {@link JfrCompare#DEFAULT_MIN_DELTA_PCT}, 25 rows, self time only. */
  public static CompareOptions defaults() {
    return new CompareOptions(
        null, ProfileCompare.DEFAULT_MIN_DELTA_PCT, ProfileCompare.DEFAULT_LIMIT, false);
  }

  public CompareOptions withEventType(String type) {
    return new CompareOptions(type, minDeltaPct, limit, inclusiveFrames, mixedSamplers);
  }

  public CompareOptions withMinDeltaPct(double pct) {
    return new CompareOptions(eventType, pct, limit, inclusiveFrames, mixedSamplers);
  }

  public CompareOptions withLimit(int rows) {
    return new CompareOptions(eventType, minDeltaPct, rows, inclusiveFrames, mixedSamplers);
  }

  public CompareOptions withInclusiveFrames(boolean inclusive) {
    return new CompareOptions(eventType, minDeltaPct, limit, inclusive, mixedSamplers);
  }

  public CompareOptions withMixedSamplers(MixedSamplers policy) {
    return new CompareOptions(eventType, minDeltaPct, limit, inclusiveFrames, policy);
  }
}
