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
 *
 * <p>It also pins the array class of the int, long, float and double element types and checks that
 * {@code int}, {@code long}, {@code float} and {@code double} elements are stored without boxing.
 */
public class UntypedPrimitiveArrayTest {
  private static final long T_BOOLEAN = 1;
  private static final long T_INT = 5;
  private static final long T_LONG = 6;
  private static final long T_FLOAT = 7;
  private static final long T_DOUBLE = 8;
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
  private static final float[] FLOATS = {
    0f, -1.5f, Float.MAX_VALUE, Float.MIN_VALUE, Float.NaN, Float.NEGATIVE_INFINITY
  };
  private static final double[] DOUBLES = {
    0d, -1.5d, Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY
  };

  private static SyntheticRecording.Element metadata() {
    SyntheticRecording.Element event =
        elem(
                "class",
                "id",
                Long.toString(T_EVENT),
                "name",
                "test.PrimitiveArrays",
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
                elem("field", "name", "floats", "class", Long.toString(T_FLOAT), "dimension", "1"),
                elem(
                    "field", "name", "doubles", "class", Long.toString(T_DOUBLE), "dimension", "1"),
                elem("field", "name", "label", "class", Long.toString(T_STRING)));
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_BOOLEAN, "boolean"),
                    primitiveClass(T_INT, "int"),
                    primitiveClass(T_LONG, "long"),
                    primitiveClass(T_FLOAT, "float"),
                    primitiveClass(T_DOUBLE, "double"),
                    primitiveClass(T_STRING, "java.lang.String"),
                    elem("class", "id", Long.toString(T_TIMESTAMP), "name", "jdk.jfr.Timestamp"),
                    elem("class", "id", Long.toString(T_TIMESPAN), "name", "jdk.jfr.Timespan"),
                    event),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private static byte[] eventBody() {
    return eventBody(INTS, STAMP_TICKS, SPAN_TICKS, LONGS, FLOATS, DOUBLES);
  }

  private static byte[] eventBody(
      int[] ints, int[] stamps, int[] spans, long[] longs, float[] floats, double[] doubles) {
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
    w.varint(floats.length);
    for (float v : floats) w.f32(v);
    w.varint(doubles.length);
    for (double v : doubles) w.f64(v);
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
          if ("test.PrimitiveArrays".equals(type.getName())) {
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
    assertArrayEquals(FLOATS, (float[]) array(event, "floats", float[].class));
    assertArrayEquals(DOUBLES, (double[]) array(event, "doubles", double[].class));
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
   * {@code int}, {@code long}, {@code float} and {@code double} array elements must be stored
   * without boxing. Boxing each element and storing it through {@code Array.set} allocates a
   * wrapper per element ({@code Float} and {@code Double} have no cache at all; {@code Long} caches
   * only -128..127). On JDK 21 that measured 32 bytes per int/long element before {@link
   * ArrayHolder#addLong} existed.
   *
   * <p>The unboxed decode allocates the backing arrays and nothing per element: {@value
   * #UNBOXED_BYTES_PER_ROW} bytes per row of one int, long, float and double element. A wrapper is
   * at least 16 bytes, so boxing even one of the four kinds adds 16 bytes per row; the allowance of
   * {@value #SLACK_BYTES_PER_ROW} bytes per row covers the fixed per-event cost with a wide margin
   * and still catches that.
   *
   * <p>The decode runs on the parser's chunk worker thread, so allocation is sampled there: the
   * handler reads the thread's allocation counter, and the difference between two consecutive
   * events covers decoding one whole event.
   */
  @ParameterizedTest
  @EnumSource(Decoder.class)
  void primitiveArrayElementsAreNotBoxed(Decoder decoder, @TempDir Path dir) throws Exception {
    java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
    assumeTrue(
        base instanceof com.sun.management.ThreadMXBean, "per-thread allocation counter missing");
    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
    assumeTrue(mx.isThreadAllocatedMemorySupported(), "thread allocation counting unsupported");
    mx.setThreadAllocatedMemoryEnabled(true);

    int n = 50_000;
    int[] ints = new int[n];
    long[] longs = new long[n];
    float[] floats = new float[n];
    double[] doubles = new double[n];
    for (int i = 0; i < n; i++) {
      ints[i] = Integer.MAX_VALUE - i; // outside the Long cache, so a box would be a real object
      longs[i] = Long.MAX_VALUE - i;
      floats[i] = i + 0.5f;
      doubles[i] = i + 0.25d;
    }
    int events = 6;
    List<byte[]> bodies = new ArrayList<>();
    for (int e = 0; e < events; e++) {
      bodies.add(eventBody(ints, new int[0], new int[0], longs, floats, doubles));
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
          if (!"test.PrimitiveArrays".equals(type.getName()) || seen[0] >= events) return;
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
    long unboxed = (long) UNBOXED_BYTES_PER_ROW * n;
    long allowed = unboxed + (long) SLACK_BYTES_PER_ROW * n;
    assertTrue(
        best <= allowed,
        String.format(
            "%s: decoding an event allocated %d bytes, %d over the %d bytes of backing arrays"
                + " (%.1f per row of int, long, float and double element); elements are being"
                + " boxed",
            decoder, best, best - unboxed, unboxed, (best - unboxed) / (double) n));
  }

  /** Backing array bytes per row: int (as long) 8 + long 8 + float 4 + double 8. */
  private static final int UNBOXED_BYTES_PER_ROW = 28;

  private static final int SLACK_BYTES_PER_ROW = 4;

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
  void addFloatAndAddDoubleStoreElementsUnboxed() {
    ArrayHolder floats = new ArrayHolder("float", 2);
    floats.addFloat(Float.NaN);
    floats.addFloat(-0f);
    assertArrayEquals(new float[] {Float.NaN, -0f}, (float[]) floats.getArray());

    ArrayHolder doubles = new ArrayHolder("double", 2);
    doubles.addDouble(Double.MIN_VALUE);
    doubles.addDouble(Double.NEGATIVE_INFINITY);
    assertArrayEquals(
        new double[] {Double.MIN_VALUE, Double.NEGATIVE_INFINITY}, (double[]) doubles.getArray());
  }

  @Test
  void addFloatAndAddDoubleRejectOtherElementTypes() {
    assertEquals(
        "addFloat on an array of double",
        assertThrows(IllegalStateException.class, () -> new ArrayHolder("double", 1).addFloat(1f))
            .getMessage());
    assertEquals(
        "addDouble on an array of long",
        assertThrows(IllegalStateException.class, () -> new ArrayHolder("long", 1).addDouble(1d))
            .getMessage());
  }

  @Test
  void addLongRejectsNonLongElementTypes() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> new ArrayHolder("short", 1).addLong(1));
    assertEquals("addLong on an array of short", e.getMessage());
  }

  private static long[] longArray(Map<String, Object> event, String field) {
    return (long[]) array(event, field, long[].class);
  }

  private static Object array(Map<String, Object> event, String field, Class<?> arrayClass) {
    Object v = event.get(field);
    assertNotNull(v, field + " missing");
    ArrayType at = assertInstanceOf(ArrayType.class, v, field);
    assertEquals(arrayClass, at.getType(), field + " array class");
    return at.getArray();
  }
}
