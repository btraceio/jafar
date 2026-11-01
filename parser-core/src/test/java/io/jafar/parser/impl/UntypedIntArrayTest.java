package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    return eventBody(INTS, STAMP_TICKS, SPAN_TICKS, LONGS);
  }

  private static byte[] eventBody(int[] ints, int[] stamps, int[] spans, long[] longs) {
    SyntheticRecording.Bytes w = new SyntheticRecording.Bytes();
    w.varint(T_EVENT);
    w.intVarint(-7); // count
    w.varint(ints.length);
    for (int v : ints) w.intVarint(v);
    w.varint(stamps.length);
    for (int v : stamps) w.intVarint(v);
    w.varint(spans.length);
    for (int v : spans) w.intVarint(v);
    w.varint(longs.length);
    for (long v : longs) w.varint(v);
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

  /** The untyped decode paths; all of them store array elements through {@link ArrayHolder}. */
  enum Decoder {
    /** {@code UntypedJafarParser} → {@link EventStream} → {@link MapValueBuilder}: production. */
    PARSER {
      @Override
      void decode(Path file, UntypedJafarParser.EventHandler handler) throws Exception {
        try (UntypedJafarParser parser = UntypedJafarParser.open(file)) {
          parser.handle(handler);
          parser.run();
        }
      }
    },
    /** {@link EventStreamLazy} → {@link LazyMapValueBuilder}. */
    LAZY {
      @Override
      void decode(Path file, UntypedJafarParser.EventHandler handler) throws Exception {
        try (StreamingChunkParser parser =
            new StreamingChunkParser(new UntypedParserContextFactory())) {
          parser.parse(
              file,
              new EventStreamLazy(null) {
                @Override
                protected void onEventValue(
                    MetadataClass type, Map<String, Object> value, Control ctl) {
                  handler.handle(type, value, ctl);
                }
              });
        }
      }
    },
    /** {@link EventStreamBaseline} → {@link MapValueBuilderBaseline}. */
    BASELINE {
      @Override
      void decode(Path file, UntypedJafarParser.EventHandler handler) throws Exception {
        try (StreamingChunkParser parser =
            new StreamingChunkParser(new UntypedParserContextFactory())) {
          parser.parse(
              file,
              new EventStreamBaseline(null) {
                @Override
                protected void onEventValue(
                    MetadataClass type, Map<String, Object> value, Control ctl) {
                  handler.handle(type, value, ctl);
                }
              });
        }
      }
    };

    abstract void decode(Path file, UntypedJafarParser.EventHandler handler) throws Exception;
  }

  @ParameterizedTest
  @EnumSource(Decoder.class)
  void intArrayFieldDecodesAsLongArray(Decoder decoder, @TempDir Path dir) throws Exception {
    Path file = writeRecording(dir);
    List<Map<String, Object>> seen = new ArrayList<>();
    decoder.decode(
        file,
        (type, value, ctl) -> {
          if ("test.IntArrays".equals(type.getName())) {
            seen.add(new HashMap<>(value));
          }
        });
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

  /**
   * {@code int} and {@code long} array elements must be stored without boxing. Boxing each element
   * and storing it through {@code Array.set} allocates a {@code Long} per element outside the
   * {@code Long} cache: decoding measured 32 bytes per element on JDK 21 before {@link
   * ArrayHolder#addLong} existed, against 8.0 after (the {@code long[]} slot and nothing else).
   * More than {@value #MAX_BYTES_PER_ELEMENT} bytes per element means elements are being boxed.
   *
   * <p>The decode runs on the parser's chunk worker thread, so allocation is sampled there: the
   * handler reads the thread's allocation counter, and the difference between two consecutive
   * events covers decoding one whole event. The arrays are large enough that the fixed per-event
   * cost (event map, builder bookkeeping) is noise against the per-element cost.
   */
  @ParameterizedTest
  @EnumSource(Decoder.class)
  void intAndLongArrayElementsAreNotBoxed(Decoder decoder, @TempDir Path dir) throws Exception {
    java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
    assumeTrue(
        base instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter missing");
    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
    assumeTrue(mx.isThreadAllocatedMemorySupported(), "thread allocation counting unsupported");
    mx.setThreadAllocatedMemoryEnabled(true);

    int n = 50_000;
    int[] ints = new int[n];
    long[] longs = new long[n];
    for (int i = 0; i < n; i++) {
      ints[i] = Integer.MAX_VALUE - i; // outside the Long cache, so a box would be a real object
      longs[i] = Long.MAX_VALUE - i;
    }
    int events = 6;
    List<byte[]> bodies = new ArrayList<>();
    for (int e = 0; e < events; e++) {
      bodies.add(eventBody(ints, new int[0], new int[0], longs));
    }
    Path file = dir.resolve("big-arrays.jfr");
    Files.write(
        file,
        SyntheticRecording.chunk(metadata(), bodies, START_NANOS, START_TICKS, FREQUENCY, 1000));

    long[] allocated = new long[events];
    long[] threads = new long[events];
    int[] seen = {0};
    decoder.decode(
        file,
        (type, value, ctl) -> {
          if (!"test.IntArrays".equals(type.getName()) || seen[0] >= events) return;
          long tid = Thread.currentThread().getId();
          allocated[seen[0]] = mx.getThreadAllocatedBytes(tid);
          threads[seen[0]] = tid;
          seen[0]++;
        });
    assertEquals(events, seen[0], "all events decoded");

    // The first event warms up; take the cheapest of the remaining decodes.
    long best = Long.MAX_VALUE;
    for (int e = 2; e < events; e++) {
      assertEquals(threads[1], threads[e], "one chunk is decoded on one thread");
      best = Math.min(best, allocated[e] - allocated[e - 1]);
    }
    double perElement = best / (2.0 * n);
    assertTrue(
        perElement <= MAX_BYTES_PER_ELEMENT,
        String.format(
            "%s: decoding an event allocated %d bytes, %.1f per int/long array element; elements"
                + " are being boxed",
            decoder, best, perElement));
  }

  private static final int MAX_BYTES_PER_ELEMENT = 12;

  @Test
  void addLongStoresIntAndLongElementsUnboxed() {
    ArrayHolder ints = new ArrayHolder("int", 2);
    ints.addLong(Integer.MAX_VALUE + 1L);
    ints.addLong(-1L);
    assertArrayEquals(new long[] {Integer.MAX_VALUE + 1L, -1L}, (long[]) ints.getArray());

    ArrayHolder longs = new ArrayHolder("long", 1);
    longs.addLong(Long.MIN_VALUE);
    assertArrayEquals(new long[] {Long.MIN_VALUE}, (long[]) longs.getArray());
  }

  @Test
  void addLongRejectsNonLongElementTypes() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> new ArrayHolder("short", 1).addLong(1));
    assertEquals("addLong on an array of short", e.getMessage());
  }

  private static long[] longArray(Map<String, Object> event, String field) {
    Object v = event.get(field);
    assertNotNull(v, field + " missing");
    ArrayType at = assertInstanceOf(ArrayType.class, v, field);
    assertEquals(long[].class, at.getType(), field + " array class");
    return (long[]) at.getArray();
  }
}
