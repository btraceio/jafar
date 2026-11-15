package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts JFR tick-based temporal field values to nanoseconds using metadata annotations.
 *
 * <p>Fields annotated with {@code @Timestamp(TICKS)} are converted to epoch nanoseconds via {@link
 * Control.ChunkInfo#asEpochNanos(long)}. Fields annotated with {@code @Timespan(TICKS)} are
 * converted to a nanosecond duration via {@link Control.ChunkInfo#asDurationNanos(long)}.
 *
 * <p>All other fields are returned unchanged.
 *
 * <p>The annotation lookup result is cached per {@link MetadataClass} so that the field and
 * annotation scan runs at most once per distinct metadata type rather than on every event.
 */
final class TemporalNormalizer {

  private static final String TIMESTAMP_NAME = "jdk.jfr.Timestamp";
  private static final String TIMESPAN_NAME = "jdk.jfr.Timespan";
  // The default (and most common) unit for both annotations in JFR recordings.
  private static final String TICKS = "TICKS";

  private static final int KIND_TIMESTAMP = 1;
  private static final int KIND_TIMESPAN = 2;

  /**
   * Per-class cache mapping field names to their normalization kind (TIMESTAMP or TIMESPAN). Only
   * fields that require conversion are stored; absent entries mean no conversion.
   */
  // The kind table is held by each MetadataClass instance (per chunk), not in a global map keyed by
  // MetadataClass.equals: that equality ignores annotations and field names, so a shared table gave
  // one chunk another chunk's decision (TemporalAnnotationPerChunkTest).

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
    if (chunkInfo == null) {
      return value;
    }
    return normalizeKind(kindOf(owner, fld), value, chunkInfo);
  }

  /**
   * Returns how {@link #normalize} treats {@code owner.fld}: {@code 0} (left as is), {@link
   * #KIND_TIMESTAMP} or {@link #KIND_TIMESPAN}. Generated deserializers resolve this once, at
   * generation time, from the same table {@link #normalize} consults per value.
   */
  static int kindOf(MetadataClass owner, String fld) {
    if (owner == null || fld == null || fld.isEmpty()) {
      return 0;
    }
    Integer kind = owner.temporalKinds(TemporalNormalizer::buildKindMap).get(fld);
    return kind == null ? 0 : kind;
  }

  /** Applies a kind previously returned by {@link #kindOf}; identical to {@link #normalize}. */
  static long normalizeKind(int kind, long value, Control.ChunkInfo chunkInfo) {
    if (kind == 0 || chunkInfo == null) {
      return value;
    }
    return kind == KIND_TIMESTAMP
        ? chunkInfo.asEpochNanos(value)
        : chunkInfo.asDurationNanos(value);
  }

  private static Map<String, Integer> buildKindMap(MetadataClass owner) {
    Map<String, Integer> map = null;
    for (MetadataField field : owner.getFields()) {
      for (MetadataAnnotation ann : field.getAnnotations()) {
        MetadataClass annType = ann.getType();
        if (annType == null) continue;
        String annName = annType.getName();
        String annValue = ann.getValue();
        boolean isTicks = annValue == null || TICKS.equals(annValue);
        if (isTicks && TIMESTAMP_NAME.equals(annName)) {
          if (map == null) map = new HashMap<>();
          map.put(field.getName(), KIND_TIMESTAMP);
          break;
        }
        if (isTicks && TIMESPAN_NAME.equals(annName)) {
          if (map == null) map = new HashMap<>();
          map.put(field.getName(), KIND_TIMESPAN);
          break;
        }
      }
    }
    return map != null ? map : Collections.emptyMap();
  }

  private TemporalNormalizer() {}
}
