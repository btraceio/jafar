package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;

import io.jafar.parser.impl.SyntheticRecording.Bytes;
import io.jafar.parser.impl.SyntheticRecording.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * A one-chunk recording with one event type per shape of {@code startTime} an event header must
 * handle, shared by both untyped parsers' header tests.
 *
 * <ul>
 *   <li>{@value #LATE}: {@code (int seq, String label, @Timestamp(TICKS) long startTime)}. The
 *       header skips two fields and carries the start time as epoch nanoseconds.
 *   <li>{@value #ARRAY}: {@code long[] startTime}. No start time.
 *   <li>{@value #POOLED}: {@code long startTime} as a constant-pool reference. No start time.
 *   <li>{@value #INT}: {@code int startTime}. No start time.
 *   <li>{@value #UNTIMED}: {@code long startTime} without {@code @Timestamp}. The stored value.
 *   <li>{@value #UNDECLARED_BEFORE}: {@code (ghost, @Timestamp(TICKS) long startTime)}, where
 *       {@code ghost} is of type {@value #T_GHOST}, which the metadata does not declare, so the
 *       field before the start time cannot be skipped. No start time; the parse goes on.
 *   <li>{@value #CORRUPT_BEFORE}: {@code (String label, @Timestamp(TICKS) long startTime)}, where
 *       the label's encoding id is one no encoding uses, so reading up to the start time fails. No
 *       start time; the next event is read from its own start.
 * </ul>
 *
 * <p>Events are written in {@link #EVENTS} order. The {@value #LATE} events surround the broken
 * ones, so a parse that decodes {@value #LATE} shows that a failed header read leaves the stream
 * where the next event starts. Start times are {@code START_TICKS + 100 * (seq + 1)} ticks; {@link
 * #epochNanos} converts them.
 *
 * <p>The Go parser's tests read the same bytes from {@code go-parser/jfr/testdata/{@value
 * #FILE_NAME}}; {@link SharedFixturesTest} fails when that file drifts from what this class builds.
 */
final class StartTimeShapesFixture {
  static final String FILE_NAME = "start-time-shapes.jfr";

  static final String LATE = "test.LateStart";
  static final String ARRAY = "test.ArrayStart";
  static final String POOLED = "test.PooledStart";
  static final String INT = "test.IntStart";
  static final String UNTIMED = "test.UntimedStart";
  static final String UNDECLARED_BEFORE = "test.UndeclaredBefore";
  static final String CORRUPT_BEFORE = "test.CorruptBefore";

  /** The event types in the order of the events, one event each. */
  static final String[] EVENTS = {
    LATE, CORRUPT_BEFORE, LATE, ARRAY, POOLED, INT, UNTIMED, UNDECLARED_BEFORE, LATE
  };

  static final long START_NANOS = 1_700_000_000_000_000_000L;
  static final long START_TICKS = 5_000_000L;
  static final long FREQUENCY = 1_000_000L; // ticks per second: 1 tick = 1000 ns

  static final int T_INT = 5;
  static final int T_LONG = 6;
  static final int T_STRING = 9;
  static final int T_TIMESTAMP = 40;
  static final int T_GHOST = 999;

  private static final int T_FIRST_EVENT = 120;

  /** The start time, in ticks, written for the event at position {@code seq}. */
  static long ticks(int seq) {
    return START_TICKS + 100L * (seq + 1);
  }

  /** {@code ticks} as epoch nanoseconds in this chunk: what a decoded {@code startTime} holds. */
  static long epochNanos(long ticks) {
    return START_NANOS + (ticks - START_TICKS) * (1_000_000_000L / FREQUENCY);
  }

  private static int typeId(String name) {
    for (int i = 0; i < TYPES.length; i++) {
      if (TYPES[i].equals(name)) {
        return T_FIRST_EVENT + i;
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static final String[] TYPES = {
    LATE, ARRAY, POOLED, INT, UNTIMED, UNDECLARED_BEFORE, CORRUPT_BEFORE
  };

  static byte[] bytes() {
    List<byte[]> events = new ArrayList<>();
    for (int seq = 0; seq < EVENTS.length; seq++) {
      String type = EVENTS[seq];
      long ticks = ticks(seq);
      Bytes e = new Bytes().varint(typeId(type));
      switch (type) {
        case LATE:
          e.varint(seq).utf8("label-" + seq).varint(ticks);
          break;
        case ARRAY:
          e.varint(2).varint(ticks).varint(ticks + 1);
          break;
        case POOLED:
          e.varint(7); // a pool index; the chunk has no such pool, and nothing resolves it
          break;
        case INT:
          e.varint(ticks);
          break;
        case UNTIMED:
          e.varint(ticks);
          break;
        case UNDECLARED_BEFORE:
          e.varint(1).varint(ticks); // whatever ghost holds, then the start time
          break;
        case CORRUPT_BEFORE:
          e.u8(0x7f).varint(ticks); // 0x7f: no JFR string encoding uses this id (0..5 are)
          break;
        default:
          throw new AssertionError(type);
      }
      events.add(e.toByteArray());
    }
    return SyntheticRecording.chunk(metadata(), events, START_NANOS, START_TICKS, FREQUENCY, 1000);
  }

  private static Element timestampedStartTime() {
    return elem("field", "name", "startTime", "class", "" + T_LONG)
        .with(elem("annotation", "class", "" + T_TIMESTAMP, "value", "TICKS"));
  }

  private static Element event(String name, Element... fields) {
    return elem("class", "id", "" + typeId(name), "name", name, "superType", "jdk.jfr.Event")
        .with(fields);
  }

  private static Element metadata() {
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    // Not SyntheticRecording.primitiveClass: Java's TypeSkipper unwraps a
                    // simpleType through its first field, and a primitive has none.
                    elem("class", "id", "" + T_INT, "name", "int"),
                    elem("class", "id", "" + T_LONG, "name", "long"),
                    elem("class", "id", "" + T_STRING, "name", "java.lang.String"),
                    elem("class", "id", "" + T_TIMESTAMP, "name", "jdk.jfr.Timestamp"),
                    event(
                        LATE,
                        elem("field", "name", "seq", "class", "" + T_INT),
                        elem("field", "name", "label", "class", "" + T_STRING),
                        timestampedStartTime()),
                    event(
                        ARRAY,
                        elem("field", "name", "startTime", "class", "" + T_LONG, "dimension", "1")),
                    event(
                        POOLED,
                        elem(
                            "field",
                            "name",
                            "startTime",
                            "class",
                            "" + T_LONG,
                            "constantPool",
                            "true")),
                    event(INT, elem("field", "name", "startTime", "class", "" + T_INT)),
                    event(UNTIMED, elem("field", "name", "startTime", "class", "" + T_LONG)),
                    event(
                        UNDECLARED_BEFORE,
                        elem("field", "name", "ghost", "class", "" + T_GHOST),
                        timestampedStartTime()),
                    event(
                        CORRUPT_BEFORE,
                        elem("field", "name", "label", "class", "" + T_STRING),
                        timestampedStartTime())),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private StartTimeShapesFixture() {}
}
