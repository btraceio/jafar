package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test: an event field of type {@code int} with dimension 1 used to abort the untyped
 * parse with {@code IllegalArgumentException: argument type mismatch} from {@code
 * java.lang.reflect.Array.set}, because {@link ArrayHolder} allocated an {@code int[]} while the
 * value builders deliver every {@code int} as a (possibly tick-normalized) {@code long}.
 *
 * <p>{@code int} arrays now decode to {@code long[]}, matching scalar {@code int} fields (which
 * surface as {@link Long}) and the Go parser (which decodes {@code int} elements as {@code int64}).
 * {@code @Timestamp}/{@code @Timespan} normalization can produce values outside the {@code int}
 * range, so narrowing to {@code int[]} would have been lossy.
 */
public class UntypedIntArrayTest {
  private static final long T_BOOLEAN = 1;
  private static final long T_INT = 5;
  private static final long T_LONG = 6;
  private static final long T_STRING = 9;
  private static final long T_TIMESTAMP = 40;
  private static final long T_TIMESPAN = 41;
  private static final long T_EVENT = 100;

  // The recording ticks 1000 times slower than nanoseconds, so normalization is visible.
  private static final long FREQUENCY = 1_000_000L;
  private static final long START_TICKS = 5_000_000L;
  private static final long START_NANOS = 1_700_000_000_000_000_000L;
  private static final long NANOS_PER_TICK = 1_000_000_000L / FREQUENCY;

  private static final int[] INTS = {0, 1, -1, 42, Integer.MAX_VALUE, Integer.MIN_VALUE};
  private static final int[] STAMP_TICKS = {(int) START_TICKS, (int) START_TICKS + 7};
  private static final int[] SPAN_TICKS = {3, Integer.MAX_VALUE};
  private static final long[] LONGS = {Long.MAX_VALUE, -2L};

  private static SyntheticRecording.Element metadata() {
    SyntheticRecording.Element event =
        elem(
                "class",
                "id",
                Long.toString(T_EVENT),
                "name",
                "test.IntArrays",
                "superType",
                "jdk.jfr.Event")
            .with(
                elem(
                    "setting",
                    "name",
                    "enabled",
                    "class",
                    Long.toString(T_BOOLEAN),
                    "defaultValue",
                    "true"),
                elem("field", "name", "count", "class", Long.toString(T_INT)),
                elem("field", "name", "ints", "class", Long.toString(T_INT), "dimension", "1"),
                elem("field", "name", "stamps", "class", Long.toString(T_INT), "dimension", "1")
                    .with(
                        elem("annotation", "class", Long.toString(T_TIMESTAMP), "value", "TICKS")),
                elem("field", "name", "spans", "class", Long.toString(T_INT), "dimension", "1")
                    .with(elem("annotation", "class", Long.toString(T_TIMESPAN), "value", "TICKS")),
                elem("field", "name", "longs", "class", Long.toString(T_LONG), "dimension", "1"),
                elem("field", "name", "label", "class", Long.toString(T_STRING)));
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_BOOLEAN, "boolean"),
                    primitiveClass(T_INT, "int"),
                    primitiveClass(T_LONG, "long"),
                    primitiveClass(T_STRING, "java.lang.String"),
                    elem("class", "id", Long.toString(T_TIMESTAMP), "name", "jdk.jfr.Timestamp"),
                    elem("class", "id", Long.toString(T_TIMESPAN), "name", "jdk.jfr.Timespan"),
                    event),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private static byte[] eventBody() {
    SyntheticRecording.Bytes w = new SyntheticRecording.Bytes();
    w.varint(T_EVENT);
    w.intVarint(-7); // count
    w.varint(INTS.length);
    for (int v : INTS) w.intVarint(v);
    w.varint(STAMP_TICKS.length);
    for (int v : STAMP_TICKS) w.intVarint(v);
    w.varint(SPAN_TICKS.length);
    for (int v : SPAN_TICKS) w.intVarint(v);
    w.varint(LONGS.length);
    for (long v : LONGS) w.varint(v);
    w.utf8("after-arrays"); // a field after the arrays proves the stream stayed aligned
    return w.toByteArray();
  }

  private static Path writeRecording(Path dir) throws Exception {
    Path file = dir.resolve("int-arrays.jfr");
    List<byte[]> events = new ArrayList<>();
    events.add(eventBody());
    Files.write(
        file,
        SyntheticRecording.chunk(metadata(), events, START_NANOS, START_TICKS, FREQUENCY, 1000));
    return file;
  }

  @Test
  void intArrayFieldDecodesAsLongArray(@TempDir Path dir) throws Exception {
    Path file = writeRecording(dir);
    List<Map<String, Object>> seen = new ArrayList<>();
    try (UntypedJafarParser parser = UntypedJafarParser.open(file)) {
      parser.handle(
          (type, value, ctl) -> {
            if ("test.IntArrays".equals(type.getName())) {
              seen.add(new HashMap<>(value));
            }
          });
      parser.run();
    }
    assertEquals(1, seen.size(), "exactly one test.IntArrays event");
    Map<String, Object> event = seen.get(0);

    // Scalar int surfaces as Long; array elements must use the same value model.
    assertEquals(Long.valueOf(-7), event.get("count"));

    long[] expectedInts = Arrays.stream(INTS).asLongStream().toArray();
    assertArrayEquals(expectedInts, longArray(event, "ints"));
    assertEquals(Long.valueOf(Integer.MIN_VALUE), Values.get(event, "ints", 5));

    // @Timestamp(TICKS) elements become epoch nanos — far outside the int range.
    long[] stamps = longArray(event, "stamps");
    assertArrayEquals(new long[] {START_NANOS, START_NANOS + 7 * NANOS_PER_TICK}, stamps);

    // @Timespan(TICKS) elements become nanosecond durations; MAX_VALUE ticks overflows int.
    long[] spans = longArray(event, "spans");
    assertArrayEquals(
        new long[] {3 * NANOS_PER_TICK, Integer.MAX_VALUE * NANOS_PER_TICK}, spans, "spans");

    assertArrayEquals(LONGS, longArray(event, "longs"));
    assertEquals("after-arrays", event.get("label"));
  }

  /**
   * {@link ArrayHolder} is shared by every untyped value builder ({@link MapValueBuilder}, {@link
   * LazyMapValueBuilder}, {@link MapValueBuilderBaseline}), each of which delivers {@code int}
   * elements as boxed {@link Long}.
   */
  @Test
  void arrayHolderAcceptsLongForIntElements() {
    ArrayHolder holder = new ArrayHolder("int", 2);
    holder.add(Long.valueOf(Integer.MAX_VALUE + 1L));
    holder.add(Long.valueOf(-1L));
    assertEquals(long[].class, holder.getType());
    assertArrayEquals(new long[] {Integer.MAX_VALUE + 1L, -1L}, (long[]) holder.getArray());
    assertEquals("[2147483648, -1]", holder.toString());
  }

  private static long[] longArray(Map<String, Object> event, String field) {
    Object v = event.get(field);
    assertNotNull(v, field + " missing");
    ArrayType at = assertInstanceOf(ArrayType.class, v, field);
    assertEquals(long[].class, at.getType(), field + " array class");
    return (long[]) at.getArray();
  }
}
