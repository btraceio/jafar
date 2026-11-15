package io.jafar.parser;

/**
 * A range of event start times, in nanoseconds since the epoch, both ends inclusive.
 *
 * <p>Given to {@link io.jafar.parser.api.UntypedJafarParser#handle(TypeFilter, TimeRange,
 * io.jafar.parser.api.UntypedJafarParser.EventHandler)} it lets the parser skip whole chunks that
 * cannot hold an event starting in the range. It is the unit of an untyped event's {@code
 * startTime} value.
 */
public final class TimeRange {
  private static final TimeRange ALL = new TimeRange(Long.MIN_VALUE, Long.MAX_VALUE);

  private final long from;
  private final long to;

  private TimeRange(long from, long to) {
    this.from = from;
    this.to = to;
  }

  /**
   * Start times from {@code fromEpochNanos} to {@code toEpochNanos}, both included. A range whose
   * start is after its end holds no start time.
   */
  public static TimeRange between(long fromEpochNanos, long toEpochNanos) {
    return new TimeRange(fromEpochNanos, toEpochNanos);
  }

  /** Start times at or after {@code fromEpochNanos}. */
  public static TimeRange since(long fromEpochNanos) {
    return new TimeRange(fromEpochNanos, Long.MAX_VALUE);
  }

  /** Start times at or before {@code toEpochNanos}. */
  public static TimeRange until(long toEpochNanos) {
    return new TimeRange(Long.MIN_VALUE, toEpochNanos);
  }

  /** Every start time. */
  public static TimeRange all() {
    return ALL;
  }

  /** The earliest start time in the range; {@link Long#MIN_VALUE} when it has no lower bound. */
  public long fromEpochNanos() {
    return from;
  }

  /** The latest start time in the range; {@link Long#MAX_VALUE} when it has no upper bound. */
  public long toEpochNanos() {
    return to;
  }

  /** Whether the range has neither bound, so that it excludes nothing. */
  public boolean isUnbounded() {
    return from == Long.MIN_VALUE && to == Long.MAX_VALUE;
  }

  /** The start times in both this range and {@code other}. */
  public TimeRange intersect(TimeRange other) {
    return new TimeRange(Math.max(from, other.from), Math.min(to, other.to));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TimeRange && from == ((TimeRange) o).from && to == ((TimeRange) o).to;
  }

  @Override
  public int hashCode() {
    return 31 * Long.hashCode(from) + Long.hashCode(to);
  }

  @Override
  public String toString() {
    return "["
        + (from == Long.MIN_VALUE ? "-inf" : String.valueOf(from))
        + ", "
        + (to == Long.MAX_VALUE ? "+inf" : String.valueOf(to))
        + "]";
  }
}
