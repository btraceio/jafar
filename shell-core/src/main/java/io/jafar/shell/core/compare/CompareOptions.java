package io.jafar.shell.core.compare;

/**
 * What a comparison looks at and how strict it is.
 *
 * @param eventType the execution-sample event type to compare, or {@code null} to detect it per
 *     recording
 * @param minDeltaPct noise floor in percentage points: a frame whose share moved by less is not
 *     reported at all. Not validated: {@code jfr_compare} has always accepted any number here
 * @param limit the most frame rows to return, per frame list
 * @param inclusiveFrames whether to also compare inclusive (total-time) frame shares; costs a full
 *     stack walk per sample instead of reading only the leaf frame
 */
public record CompareOptions(
    String eventType, double minDeltaPct, int limit, boolean inclusiveFrames) {

  public CompareOptions {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (eventType != null && eventType.isBlank()) {
      eventType = null;
    }
  }

  /** Detected event type, {@link JfrCompare#DEFAULT_MIN_DELTA_PCT}, 25 rows, self time only. */
  public static CompareOptions defaults() {
    return new CompareOptions(
        null, JfrCompare.DEFAULT_MIN_DELTA_PCT, JfrCompare.DEFAULT_LIMIT, false);
  }

  public CompareOptions withEventType(String type) {
    return new CompareOptions(type, minDeltaPct, limit, inclusiveFrames);
  }

  public CompareOptions withMinDeltaPct(double pct) {
    return new CompareOptions(eventType, pct, limit, inclusiveFrames);
  }

  public CompareOptions withLimit(int rows) {
    return new CompareOptions(eventType, minDeltaPct, rows, inclusiveFrames);
  }

  public CompareOptions withInclusiveFrames(boolean inclusive) {
    return new CompareOptions(eventType, minDeltaPct, limit, inclusive);
  }
}
