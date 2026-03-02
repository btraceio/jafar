package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;

/**
 * Converts JFR tick-based temporal field values to nanoseconds using metadata annotations.
 *
 * <p>Fields annotated with {@code @Timestamp(TICKS)} are converted to epoch nanoseconds via {@link
 * Control.ChunkInfo#asEpochNanos(long)}. Fields annotated with {@code @Timespan(TICKS)} are
 * converted to a nanosecond duration via {@link Control.ChunkInfo#asDuration(long)}.
 *
 * <p>All other fields are returned unchanged.
 */
final class TemporalNormalizer {

  private static final String TIMESTAMP = "jdk.jfr.Timestamp";
  private static final String TIMESPAN = "jdk.jfr.Timespan";
  // The default (and most common) unit for both annotations in JFR recordings.
  private static final String TICKS = "TICKS";

  /**
   * Normalizes a long field value if the owning class has a {@code @Timestamp(TICKS)} or
   * {@code @Timespan(TICKS)} annotation on the named field.
   *
   * @param owner the metadata class that owns the field (may be null)
   * @param fld the field name (may be null or empty)
   * @param value the raw field value (ticks)
   * @param chunkInfo the chunk timing info used for conversion (may be null)
   * @return the normalized value in epoch nanoseconds or nanosecond duration, or {@code value}
   *     unchanged when no applicable annotation is found
   */
  static long normalize(MetadataClass owner, String fld, long value, Control.ChunkInfo chunkInfo) {
    if (owner == null || fld == null || fld.isEmpty() || chunkInfo == null) {
      return value;
    }
    for (MetadataField field : owner.getFields()) {
      if (!fld.equals(field.getName())) continue;
      for (MetadataAnnotation ann : field.getAnnotations()) {
        MetadataClass annType = ann.getType();
        if (annType == null) continue;
        String annName = annType.getName();
        // A null annotation value means the default unit, which is TICKS for both annotations.
        String annValue = ann.getValue();
        boolean isTicks = annValue == null || TICKS.equals(annValue);
        if (isTicks && TIMESTAMP.equals(annName)) {
          return chunkInfo.asEpochNanos(value);
        }
        if (isTicks && TIMESPAN.equals(annName)) {
          return chunkInfo.asDuration(value).toNanos();
        }
      }
      break; // field found but no applicable annotation — return unchanged
    }
    return value;
  }

  private TemporalNormalizer() {}
}
