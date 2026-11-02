package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.api.Values;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The generated untyped deserializers ({@link EventStreamGenerated}) must decode array fields into
 * the same values as the generic reader ({@code UntypedJafarParser}): an {@link ArrayType} backed
 * by the same Java array class ({@code long[]} for {@code int} and {@code long}, {@code short[]},
 * {@code char[]}, {@code float[]}, {@code double[]}, {@code Object[]} for strings and complex
 * values) holding the same elements.
 *
 * <p>The recording is hand-encoded with {@link SyntheticRecording}, because the JMC writer cannot
 * write array fields on events. Both event types have more than 10 fields, so they get the eager
 * (HashMap) deserializer under {@link UntypedStrategy#FULL_ITERATION} and the lazy (ArrayPool) one
 * under {@link UntypedStrategy#SPARSE_ACCESS}. {@code test.Wide} also has an inline simple-type
 * field.
 *
 * <p>{@code Timestamp(TICKS)} and {@code Timespan(TICKS)} fields - a long scalar, an int scalar
 * whose nanosecond value overflows int, an int array, and a long inside the nested {@code
 * test.Nested} elements - must come out in nanoseconds, as the generic reader produces them.
 */
public class GeneratedUntypedArrayParityTest {
  private static final long T_CHAR = 3;
  private static final long T_SHORT = 4;
  private static final long T_INT = 5;
  private static final long T_LONG = 6;
  private static final long T_FLOAT = 7;
  private static final long T_DOUBLE = 8;
  private static final long T_STRING = 9;
  private static final long T_NESTED = 30;
  private static final long T_LABEL = 31;
  private static final long T_TIMESTAMP = 40;
  private static final long T_TIMESPAN = 41;
  private static final long T_NARROW = 100;
  private static final long T_WIDE = 101;

  private static final short SCALAR_SHORT = -1000;
  private static final char SCALAR_CHAR = (char) 0xFFFE;
  private static final int[] INTS = {0, -1, 300, Integer.MAX_VALUE, Integer.MIN_VALUE};
  private static final long[] LONGS = {Long.MAX_VALUE, -2L, 1L << 40};
  private static final float[] FLOATS = {0f, -1.5f, Float.MAX_VALUE, Float.NaN};
  private static final double[] DOUBLES = {0d, -1.5d, Double.MIN_VALUE, Double.NEGATIVE_INFINITY};
  private static final short[] SHORTS = {0, -1, 1000, Short.MIN_VALUE, Short.MAX_VALUE};
  private static final char[] CHARS = {0, 'A', (char) 200, (char) 0xFFFF};
  private static final String[] STRINGS = {"a", "", "zażółć"};

  // The recording ticks 1000 times slower than nanoseconds, so normalization is visible.
  private static final long START_NANOS = 1_700_000_000_000_000_000L;
  private static final long START_TICKS = 5_000_000L;
  private static final long FREQUENCY = 1_000_000L;
  private static final long NANOS_PER_TICK = 1_000_000_000L / FREQUENCY;
  private static final int[] STAMP_TICKS = {(int) START_TICKS, (int) START_TICKS + 7};

  private static SyntheticRecording.Element annotated(SyntheticRecording.Element f, long ann) {
    return f.with(elem("annotation", "class", Long.toString(ann), "value", "TICKS"));
  }

  private static SyntheticRecording.Element field(String name, long type, boolean array) {
    return array
        ? elem("field", "name", name, "class", Long.toString(type), "dimension", "1")
        : elem("field", "name", name, "class", Long.toString(type));
  }

  private static SyntheticRecording.Element eventClass(long id, String name, boolean wide) {
    List<SyntheticRecording.Element> fields =
        new ArrayList<>(
            Arrays.asList(
                field("s", T_SHORT, false),
                field("c", T_CHAR, false),
                field("ints", T_INT, true),
                field("longs", T_LONG, true),
                field("floats", T_FLOAT, true),
                field("doubles", T_DOUBLE, true),
                field("shorts", T_SHORT, true),
                field("chars", T_CHAR, true),
                field("strings", T_STRING, true),
                field("items", T_NESTED, true),
                annotated(field("start", T_LONG, false), T_TIMESTAMP),
                annotated(field("dur", T_INT, false), T_TIMESPAN),
                annotated(field("stamps", T_INT, true), T_TIMESTAMP)));
    if (wide) {
      fields.add(field("pad", T_LONG, false));
      // A simple type (a class wrapping one value) inline: a nested map in the generic reader.
      fields.add(field("label", T_LABEL, false));
    }
    return elem("class", "id", Long.toString(id), "name", name, "superType", "jdk.jfr.Event")
        .with(fields.toArray(new SyntheticRecording.Element[0]));
  }

  private static SyntheticRecording.Element metadata() {
    SyntheticRecording.Element nested =
        elem("class", "id", Long.toString(T_NESTED), "name", "test.Nested")
            .with(
                field("a", T_INT, false),
                field("b", T_STRING, false),
                annotated(field("at", T_LONG, false), T_TIMESTAMP));
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_CHAR, "char"),
                    primitiveClass(T_SHORT, "short"),
                    primitiveClass(T_INT, "int"),
                    primitiveClass(T_LONG, "long"),
                    primitiveClass(T_FLOAT, "float"),
                    primitiveClass(T_DOUBLE, "double"),
                    primitiveClass(T_STRING, "java.lang.String"),
                    elem("class", "id", Long.toString(T_TIMESTAMP), "name", "jdk.jfr.Timestamp"),
                    elem("class", "id", Long.toString(T_TIMESPAN), "name", "jdk.jfr.Timespan"),
                    nested,
                    elem(
                            "class",
                            "id",
                            Long.toString(T_LABEL),
                            "name",
                            "test.Label",
                            "simpleType",
                            "true")
                        .with(field("string", T_STRING, false)),
                    eventClass(T_NARROW, "test.Narrow", false),
                    eventClass(T_WIDE, "test.Wide", true)),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private static byte[] event(long typeId, boolean wide) {
    SyntheticRecording.Bytes w = new SyntheticRecording.Bytes();
    w.varint(typeId);
    // int, short and char are written zero extended to their unsigned width.
    w.varint(SCALAR_SHORT & 0xFFFF);
    w.varint(SCALAR_CHAR);
    w.varint(INTS.length);
    for (int v : INTS) w.intVarint(v);
    w.varint(LONGS.length);
    for (long v : LONGS) w.varint(v);
    w.varint(FLOATS.length);
    for (float v : FLOATS) w.f32(v);
    w.varint(DOUBLES.length);
    for (double v : DOUBLES) w.f64(v);
    w.varint(SHORTS.length);
    for (short v : SHORTS) w.varint(v & 0xFFFF);
    w.varint(CHARS.length);
    for (char v : CHARS) w.varint(v);
    w.varint(STRINGS.length);
    for (String v : STRINGS) w.utf8(v);
    w.varint(2); // items: a, b, at (ticks)
    w.intVarint(7).utf8("first").varint(START_TICKS + 1);
    w.intVarint(-8).utf8("second").varint(START_TICKS + 2);
    w.varint(START_TICKS + 5); // start (ticks)
    w.intVarint(Integer.MAX_VALUE); // dur (ticks): in nanoseconds it overflows int
    w.varint(STAMP_TICKS.length);
    for (int v : STAMP_TICKS) w.intVarint(v);
    if (wide) {
      w.varint(123_456_789_000L); // pad
      w.utf8("labelled"); // label.string
    }
    return w.toByteArray();
  }

  private static Path writeRecording(Path dir) throws Exception {
    List<byte[]> events = new ArrayList<>();
    events.add(event(T_NARROW, false));
    events.add(event(T_WIDE, true));
    Path file = dir.resolve("generated-arrays.jfr");
    Files.write(
        file,
        SyntheticRecording.chunk(metadata(), events, START_NANOS, START_TICKS, FREQUENCY, 1000));
    return file;
  }

  /** Decodes every test event, keyed by type name, deep-resolved so recycled maps cannot change. */
  private static Map<String, Map<String, Object>> decodeGeneric(Path file) throws Exception {
    Map<String, Map<String, Object>> out = new HashMap<>();
    try (UntypedJafarParser parser = UntypedJafarParser.open(file)) {
      parser.handle((type, value, ctl) -> out.put(type.getName(), snapshot(value)));
      parser.run();
    }
    return out;
  }

  private static Map<String, Map<String, Object>> decodeGenerated(
      Path file, UntypedStrategy strategy) throws Exception {
    Map<String, Map<String, Object>> out = new HashMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory(strategy))) {
      parser.parse(
          file,
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> value, Control ctl) {
              out.put(type.getName(), snapshot(value));
            }
          });
    }
    return out;
  }

  /** Copies the event, keeping each top-level ArrayType so its array class can be checked. */
  private static Map<String, Object> snapshot(Map<String, Object> value) {
    return new HashMap<>(value);
  }

  @ParameterizedTest
  @EnumSource(
      value = UntypedStrategy.class,
      names = {"FULL_ITERATION", "SPARSE_ACCESS"})
  void generatedArraysMatchTheGenericReader(UntypedStrategy strategy, @TempDir Path dir)
      throws Exception {
    Path file = writeRecording(dir);
    Map<String, Map<String, Object>> generic = decodeGeneric(file);
    Map<String, Map<String, Object>> generated = decodeGenerated(file, strategy);

    for (String typeName : new String[] {"test.Narrow", "test.Wide"}) {
      Map<String, Object> want = generic.get(typeName);
      Map<String, Object> got = generated.get(typeName);
      assertNotNull(want, typeName + " decoded by the generic reader");
      assertNotNull(got, typeName + " decoded by the generated deserializer");
      assertEquals(want.keySet(), got.keySet(), typeName + " field names");
      for (String fieldName : want.keySet()) {
        String where = strategy + " " + typeName + "." + fieldName;
        Object w = want.get(fieldName);
        Object g = got.get(fieldName);
        if (w instanceof ArrayType) {
          ArrayType wa = (ArrayType) w;
          ArrayType ga = assertInstanceOf(ArrayType.class, g, where + " must be an ArrayType");
          assertEquals(wa.getType(), ga.getType(), where + " array class");
          assertTrue(
              Objects.deepEquals(
                  Values.resolvedDeep(single(wa)).get("v"),
                  Values.resolvedDeep(single(ga)).get("v")),
              () -> where + " elements: generic " + wa + ", generated " + ga);
        } else {
          assertEquals(w, g, where);
        }
      }
    }

    // Pin the expected shapes, not only parity.
    Map<String, Object> narrow = generated.get("test.Narrow");
    assertEquals(Short.valueOf(SCALAR_SHORT), narrow.get("s"), strategy + " scalar short");
    assertEquals(Character.valueOf(SCALAR_CHAR), narrow.get("c"), strategy + " scalar char");
    assertEquals(long[].class, ((ArrayType) narrow.get("ints")).getType());
    assertEquals(short[].class, ((ArrayType) narrow.get("shorts")).getType());
    assertEquals(char[].class, ((ArrayType) narrow.get("chars")).getType());
    assertEquals(Object[].class, ((ArrayType) narrow.get("items")).getType());
    assertEquals(
        "labelled",
        ((Map<?, ?>) generated.get("test.Wide").get("label")).get("string"),
        strategy + " simple-type field");
    assertEquals(
        Long.valueOf(START_NANOS + 5 * NANOS_PER_TICK), narrow.get("start"), strategy + " start");
    assertEquals(
        Long.valueOf(Integer.MAX_VALUE * NANOS_PER_TICK), narrow.get("dur"), strategy + " dur");
    assertTrue(
        Arrays.equals(
            new long[] {START_NANOS, START_NANOS + 7 * NANOS_PER_TICK},
            (long[]) ((ArrayType) narrow.get("stamps")).getArray()),
        strategy + " stamps");
    Object items = ((ArrayType) narrow.get("items")).getArray();
    assertEquals(
        Long.valueOf(START_NANOS + NANOS_PER_TICK),
        ((Map<?, ?>) ((Object[]) items)[0]).get("at"),
        strategy + " items[0].at");
  }

  /** Wraps an array in a one-entry map so {@link Values#resolvedDeep} unwraps it fully. */
  private static Map<String, Object> single(Object v) {
    Map<String, Object> m = new HashMap<>();
    m.put("v", v);
    return m;
  }
}
