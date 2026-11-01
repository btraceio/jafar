package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>The generated untyped deserializers decide a field's kind once, at generation time, with
 * {@link #kindOf(MetadataField)}, and convert with {@link #apply(long, int, ParserContext)}; both
 * share the annotation rule and the conversion with the generic reader.
 */
@Internal
public final class TemporalNormalizer {

  private static final String TIMESTAMP_NAME = "jdk.jfr.Timestamp";
  private static final String TIMESPAN_NAME = "jdk.jfr.Timespan";
  // The default (and most common) unit for both annotations in JFR recordings.
  private static final String TICKS = "TICKS";

  /** The field needs no conversion. */
  public static final int KIND_NONE = 0;

  /** The field is a {@code @Timestamp(TICKS)}: converted to epoch nanoseconds. */
  public static final int KIND_TIMESTAMP = 1;

  /** The field is a {@code @Timespan(TICKS)}: converted to a nanosecond duration. */
  public static final int KIND_TIMESPAN = 2;

  /**
   * Per-class cache mapping field names to their normalization kind (TIMESTAMP or TIMESPAN). Only
   * fields that require conversion are stored; absent entries mean no conversion.
   */
  private static final ConcurrentHashMap<MetadataClass, Map<String, Integer>> NORM_CACHE =
      new ConcurrentHashMap<>();

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
    Map<String, Integer> kinds =
        NORM_CACHE.computeIfAbsent(owner, TemporalNormalizer::buildKindMap);
    Integer kind = kinds.get(fld);
    if (kind == null) {
      return value;
    }
    if (kind == KIND_TIMESTAMP) {
      return chunkInfo.asEpochNanos(value);
    }
    // KIND_TIMESPAN
    return chunkInfo.asDurationNanos(value);
  }

  /**
   * Returns how values of a field are converted: {@link #KIND_TIMESTAMP} for a field annotated
   * {@code jdk.jfr.Timestamp(TICKS)}, {@link #KIND_TIMESPAN} for one annotated {@code
   * jdk.jfr.Timespan(TICKS)} (an annotation without a value means TICKS, the default of both), and
   * {@link #KIND_NONE} otherwise.
   *
   * @param field the field
   * @return the field's conversion kind
   */
  public static int kindOf(MetadataField field) {
    for (MetadataAnnotation ann : field.getAnnotations()) {
      MetadataClass annType = ann.getType();
      if (annType == null) continue;
      String annName = annType.getName();
      String annValue = ann.getValue();
      boolean isTicks = annValue == null || TICKS.equals(annValue);
      if (isTicks && TIMESTAMP_NAME.equals(annName)) {
        return KIND_TIMESTAMP;
      }
      if (isTicks && TIMESPAN_NAME.equals(annName)) {
        return KIND_TIMESPAN;
      }
    }
    return KIND_NONE;
  }

  /**
   * Converts a tick value of the given kind using the chunk timing in {@code context}. Values of
   * {@link #KIND_NONE}, and all values when the context has no chunk timing, are returned unchanged
   * - as {@link #normalize} does.
   *
   * @param value the raw value (ticks)
   * @param kind the field's kind from {@link #kindOf(MetadataField)}
   * @param context the chunk's parser context (may be null)
   * @return the converted value
   */
  public static long apply(long value, int kind, ParserContext context) {
    if (kind == KIND_NONE || context == null) {
      return value;
    }
    Control.ChunkInfo chunkInfo = context.get(Control.ChunkInfo.class);
    if (chunkInfo == null) {
      return value;
    }
    return kind == KIND_TIMESTAMP
        ? chunkInfo.asEpochNanos(value)
        : chunkInfo.asDurationNanos(value);
  }

  /**
   * Converts the first {@code length} values in place, like {@link #apply} applied to each, looking
   * the chunk timing up once.
   *
   * @param values the raw values (ticks)
   * @param length how many values to convert
   * @param kind the field's kind from {@link #kindOf(MetadataField)}
   * @param context the chunk's parser context (may be null)
   */
  public static void applyAll(long[] values, int length, int kind, ParserContext context) {
    if (kind == KIND_NONE || context == null) {
      return;
    }
    Control.ChunkInfo chunkInfo = context.get(Control.ChunkInfo.class);
    if (chunkInfo == null) {
      return;
    }
    for (int i = 0; i < length; i++) {
      values[i] =
          kind == KIND_TIMESTAMP
              ? chunkInfo.asEpochNanos(values[i])
              : chunkInfo.asDurationNanos(values[i]);
    }
  }

  private static Map<String, Integer> buildKindMap(MetadataClass owner) {
    Map<String, Integer> map = null;
    for (MetadataField field : owner.getFields()) {
      int kind = kindOf(field);
      if (kind != KIND_NONE) {
        if (map == null) map = new HashMap<>();
        map.put(field.getName(), kind);
      }
    }
    return map != null ? map : Collections.emptyMap();
  }

  private TemporalNormalizer() {}
}
