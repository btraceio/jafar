package io.jafar.parser.impl;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.Collection;

/**
 * Decides whether a chunk can hold an event a handler asked for by start time.
 *
 * <p>There is one rule, and it looks one way only. An event is written when it is committed, into
 * the chunk that is current then, so no event starts after its chunk ends: <b>a chunk that ends
 * before the range begins is skipped.</b>
 *
 * <p>The mirror image does not hold, and is deliberately not implemented: a chunk that starts after
 * the range ends can still hold events that start in it.
 *
 * <ul>
 *   <li>An event with a duration is written when it ends. A {@code jdk.ThreadSleep} was measured
 *       starting 2.5 s before its chunk's start.
 *   <li>An event without a duration field is no safer. A Datadog profiler recording ({@code
 *       tck-test.jfr}) holds 14 {@code datadog.HeapLiveObject} events, which have no {@code
 *       duration} field, stamped up to 36 minutes before their chunk's start: the writer emits them
 *       when the chunk is finished, with the time of the allocation they describe. A rule that
 *       skipped later chunks for types without a duration lost all 14.
 * </ul>
 *
 * <p>The comparison leaves {@link #MARGIN_NANOS} of slack: a chunk's start and its events' start
 * times come from the same tick counter, but its duration is a separate header field.
 *
 * <p>A chunk is never skipped when its header carries no duration (it was not finished), or when a
 * wanted type's {@code startTime} is not a tick timestamp the parser converts to epoch nanoseconds:
 * its values are then in some other unit, and comparing them with the header would be guessing.
 */
final class ChunkPruning {
  /** Slack on the comparison with the chunk's end. */
  static final long MARGIN_NANOS = 1_000_000_000L;

  private static final String START_TIME = "startTime";
  private static final String EVENT_SUPER_TYPE = "jdk.jfr.Event";

  private ChunkPruning() {}

  /**
   * Whether a chunk spanning {@code chunkStartNanos} for {@code chunkDurationNanos} can hold an
   * event whose start time is in {@code range}. Only the range's lower bound can rule a chunk out.
   */
  static boolean canHoldMatch(TimeRange range, long chunkStartNanos, long chunkDurationNanos) {
    if (chunkDurationNanos <= 0) {
      return true;
    }
    long chunkEnd = saturatingAdd(chunkStartNanos, chunkDurationNanos);
    return saturatingAdd(chunkEnd, MARGIN_NANOS) >= range.fromEpochNanos();
  }

  /**
   * Whether a handler wanting the types {@code filter} accepts, with start times in {@code range},
   * needs a chunk with these metadata classes.
   */
  static boolean needsChunk(
      TypeFilter filter,
      TimeRange range,
      long chunkStartNanos,
      long chunkDurationNanos,
      Collection<MetadataClass> classes) {
    if (canHoldMatch(range, chunkStartNanos, chunkDurationNanos)) {
      return true;
    }
    // The header rules the chunk out, provided every wanted type's startTime is in its unit.
    for (MetadataClass type : classes) {
      boolean hasStartTime = hasField(type, START_TIME);
      if (!hasStartTime && !EVENT_SUPER_TYPE.equals(type.getSuperType())) {
        continue; // not an event type: java.lang.String, jdk.types.StackTrace, ...
      }
      if (!filter.test(type)) {
        continue;
      }
      if (!hasStartTime
          || TemporalNormalizer.kindOf(type, START_TIME) != TemporalNormalizer.KIND_TIMESTAMP) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasField(MetadataClass type, String name) {
    for (MetadataField field : type.getFields()) {
      if (name.equals(field.getName())) {
        return true;
      }
    }
    return false;
  }

  private static long saturatingAdd(long a, long b) {
    long sum = a + b;
    if (((a ^ sum) & (b ^ sum)) < 0) {
      return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return sum;
  }
}
