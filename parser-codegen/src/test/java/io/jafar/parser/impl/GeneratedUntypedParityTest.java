package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.RecordingStreamReader;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.UntypedCodeGenerator;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.testsupport.JfrCorpus;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * The generated untyped path ({@link EventStreamGenerated}) must deliver what the generic one
 * ({@link EventStream}) delivers: the same events, per type, with the same values in the same
 * shapes.
 *
 * <p>Uses {@code tck-test.jfr}, which is checked in (no {@code get_resources.sh} download) and has
 * two chunks written by different producers that reuse 50 type ids for different types — the case
 * that broke a deserializer cache keyed by type id.
 *
 * <p>Runs over the whole JFR golden corpus ({@link JfrCorpus}): the committed {@code tck-test.jfr}
 * plus the deterministic generated recordings {@code test-ap.jfr} and {@code test-jfr.jfr}. Unlike
 * the golden tests, no {@code .generated} marker check runs here — both paths read the same file,
 * so this is an internal consistency comparison of the two decoders over identical bytes and is
 * meaningful for any recording, real or generated (D1 guards goldens, not parity).
 *
 * <p>Each event is rendered canonically — map keys sorted, every leaf tagged with its Java class,
 * arrays with their component type, constant-pool references resolved two levels deep — and the
 * per-type multisets of renderings are compared. Chunks are parsed on parallel threads, so event
 * order is not compared and the collectors are thread-safe.
 */
public class GeneratedUntypedParityTest {
  private static final Path TCK_RECORDING =
      Paths.get("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");

  /**
   * Vacuity guard per corpus fixture: the generic path must see at least this many event types, or
   * the recording does not exercise the paths under test. Measured values; tck's 70 is a floor kept
   * from the single-fixture era, the generated fixtures are small and pinned exactly.
   */
  private static final Map<JfrCorpus, Integer> MIN_EVENT_TYPES =
      Collections.unmodifiableMap(
          new EnumMap<>(JfrCorpus.class) {
            {
              put(JfrCorpus.TCK_TEST, 70);
              put(JfrCorpus.TEST_AP, 4);
              put(JfrCorpus.TEST_JFR, 3);
            }
          });

  static Stream<Arguments> corpusTimesStrategy() {
    return Arrays.stream(JfrCorpus.values())
        .flatMap(c -> Arrays.stream(UntypedStrategy.values()).map(s -> Arguments.of(c, s)));
  }

  /**
   * Whole-corpus parity (AC-6.7): every {@link JfrCorpus} fixture × every {@link UntypedStrategy}.
   * No marker check — see the class Javadoc.
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("corpusTimesStrategy")
  void generatedPathDeliversTheSameEventsAsTheGenericPath(
      JfrCorpus corpus, UntypedStrategy strategy) throws Exception {
    // Guard against a vacuous pass: the recording must actually exercise the path.
    assertParity(corpus.path(), strategy, MIN_EVENT_TYPES.get(corpus));
  }

  /**
   * Scalar shapes {@code tck-test.jfr} does not contain: {@code char}, and negative {@code short}
   * and {@code int} (varint sign handling).
   */
  @ParameterizedTest(name = "shapes {0}")
  @EnumSource(UntypedStrategy.class)
  void generatedPathMatchesOnCharsAndNegatives(UntypedStrategy strategy, @TempDir Path dir)
      throws Exception {
    assertParity(writeShapesRecording(dir.resolve("shapes.jfr")), strategy, 1);
  }

  /**
   * Arrays: none of the events in {@code tck-test.jfr} has an array field, and the JMC writer
   * (8.2.1) cannot write one on an event ({@code Chunk.writeEvent} calls {@code getValue()} on
   * every field). So this decodes one hand-encoded payload of {@code test.ArrayShapes} — whose
   * metadata the writer does produce — with both decoders and compares the values and the number of
   * bytes each consumed. Arrays of structs are not covered: the writer gives every custom type a
   * constant pool, so it cannot describe an inline struct array.
   */
  @ParameterizedTest(name = "arrays {0}")
  @EnumSource(UntypedStrategy.class)
  void generatedDeserializerDecodesArraysLikeTheGenericReader(
      UntypedStrategy strategy, @TempDir Path dir) throws Exception {
    AtomicReference<ParserContext> chunkContext = new AtomicReference<>();
    AtomicReference<MetadataClass> arrayShapes = new AtomicReference<>();
    parse(
        writeShapesRecording(dir.resolve("shapes.jfr")),
        strategy,
        new ChunkParserListener() {
          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            for (MetadataClass c : metadata.getClasses()) {
              if (c.getName().equals("test.ArrayShapes")) {
                chunkContext.set(context);
                arrayShapes.set(c);
              }
            }
            return true;
          }
        });
    MetadataClass type = arrayShapes.get();
    assertNotNull(type, "test.ArrayShapes missing from the recording's metadata");

    Path payload = Files.write(dir.resolve("payload.bin"), encodePayload(type));

    RecordingStream genericStream =
        new RecordingStream(RecordingStreamReader.mapped(payload), chunkContext.get());
    MapValueBuilder builder = new MapValueBuilder(chunkContext.get());
    builder.reset();
    builder.onComplexValueStart(null, null, type);
    new GenericValueReader(builder).readValue(genericStream, type);
    builder.onComplexValueEnd(null, null, type);

    RecordingStream generatedStream =
        new RecordingStream(RecordingStreamReader.mapped(payload), chunkContext.get());
    Map<String, Object> generated =
        UntypedCodeGenerator.generate(type, strategy)
            .deserialize(generatedStream, chunkContext.get());

    String expected = render(builder.getRoot(), Integer.MAX_VALUE);
    assertTrue(expected.contains("strings=array<java.lang.Object>[java.lang.String:"), expected);
    assertTrue(expected.contains("array<char>"), expected);
    // int elements are stored in a long[], as MapValueBuilder stores them.
    assertTrue(expected.contains("ints=array<long>"), expected);
    assertEquals(expected, render(generated, Integer.MAX_VALUE));
    assertEquals(genericStream.position(), generatedStream.position(), "bytes consumed");
  }

  /**
   * A failing generated deserializer must fail the parse and say which event type failed — not be
   * swallowed. It used to be caught, and the {@code false} returned to the chunk parser skipped the
   * rest of the chunk: 15 of 78 event types reached the handler for {@code tck-test.jfr}.
   */
  @Test
  void deserializerFailureFailsTheParseNamingTheEventType() {
    String victim = "jdk.JavaThreadStatistics";
    ChunkParserListener planter =
        new ChunkParserListener() {
          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            for (MetadataClass c : metadata.getClasses()) {
              if (c.getName().equals(victim)) {
                context
                    .get(UntypedDeserializerCache.class)
                    .put(
                        UntypedCodeGenerator.cacheKey(c, UntypedStrategy.SPARSE_ACCESS),
                        (stream, ctx) -> {
                          throw new IllegalStateException("planted failure");
                        });
              }
            }
            return true;
          }
        };
    AtomicLong delivered = new AtomicLong();
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                parse(
                    TCK_RECORDING,
                    UntypedStrategy.SPARSE_ACCESS,
                    new EventStreamGenerated(planter) {
                      @Override
                      protected void onEventValue(
                          MetadataClass type, Map<String, Object> value, Control c) {
                        delivered.incrementAndGet();
                      }
                    }));
    // A failure while reading an event is an EventDecodingException, as on the generic path; only
    // a failure to generate a deserializer is a GeneratedDeserializationException.
    Throwable t = e;
    while (t != null && !(t instanceof EventDecodingException)) {
      t = t.getCause();
    }
    assertNotNull(t, "no EventDecodingException in the cause chain of " + e);
    assertEquals(victim, ((EventDecodingException) t).getTypeName(), t.getMessage());
    assertTrue(t.getMessage().contains(victim), t.getMessage());
    assertEquals("planted failure", t.getCause().getMessage());
  }

  private static void assertParity(Path recording, UntypedStrategy strategy, int minTypes)
      throws Exception {
    Map<String, Map<String, Long>> generic = collect(recording, null, v -> render(v, 0));
    Map<String, Map<String, Long>> generated = collect(recording, strategy, v -> render(v, 0));

    assertTrue(
        generic.size() >= minTypes, "generic path saw only " + generic.size() + " event types");

    List<String> problems = new ArrayList<>();
    for (String type : union(generic.keySet(), generated.keySet())) {
      Map<String, Long> g = generic.getOrDefault(type, Collections.emptyMap());
      Map<String, Long> x = generated.getOrDefault(type, Collections.emptyMap());
      long gCount = total(g);
      long xCount = total(x);
      if (gCount != xCount) {
        problems.add(type + ": generic " + gCount + " events, generated " + xCount);
      } else if (!g.equals(x)) {
        problems.add(
            type
                + ": same count, different values; e.g. generic "
                + firstMissing(g, x)
                + " vs generated "
                + firstMissing(x, g));
      }
    }
    assertEquals(
        Collections.emptyList(),
        problems,
        strategy + ": " + problems.size() + " of " + generic.size() + " event types differ");
  }

  private static Path writeShapesRecording(Path target) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(out)) {
      Type shapes =
          recording.registerEventType(
              "test.Shapes",
              b ->
                  b.addField("c", Types.Builtin.CHAR)
                      .addField("s", Types.Builtin.SHORT)
                      .addField("i", Types.Builtin.INT));
      // Metadata only: the writer cannot write array fields on an event (see above).
      recording.registerEventType(
          "test.ArrayShapes",
          b ->
              b.addField("bytes", Types.Builtin.BYTE, TypedFieldBuilder::asArray)
                  .addField("chars", Types.Builtin.CHAR, TypedFieldBuilder::asArray)
                  .addField("shorts", Types.Builtin.SHORT, TypedFieldBuilder::asArray)
                  .addField("ints", Types.Builtin.INT, TypedFieldBuilder::asArray)
                  .addField("longs", Types.Builtin.LONG, TypedFieldBuilder::asArray)
                  .addField("floats", Types.Builtin.FLOAT, TypedFieldBuilder::asArray)
                  .addField("doubles", Types.Builtin.DOUBLE, TypedFieldBuilder::asArray)
                  .addField("booleans", Types.Builtin.BOOLEAN, TypedFieldBuilder::asArray)
                  .addField("strings", Types.Builtin.STRING, TypedFieldBuilder::asArray));
      for (int n = 0; n < 5; n++) {
        final int k = n;
        recording.writeEvent(
            shapes.asValue(
                v ->
                    v.putField("c", (char) ('\u00e9' + k))
                        .putField("s", (short) (-300 - k))
                        .putField("i", -70000 - k)));
      }
    }
    Files.write(target, out.toByteArray());
    return target;
  }

  /**
   * A generic-path map is an ordinary {@link java.util.HashMap} and keeps its values after the
   * callback returns. A lazy generated map once read from a pool reused for the next event, so a
   * retained map showed another event's values (3808 distinct {@code startTime}s read in the
   * callback, 9 when read after the parse).
   */
  @Test
  void retainedEventMapsKeepTheirValues() throws Exception {
    Function<Map<String, Object>, String> shallow = v -> render(v, Integer.MAX_VALUE);
    Map<String, Map<String, Long>> readInCallback =
        collect(TCK_RECORDING, UntypedStrategy.SPARSE_ACCESS, shallow);

    List<Object[]> retained = Collections.synchronizedList(new ArrayList<>());
    parseGenerated(
        TCK_RECORDING, UntypedStrategy.SPARSE_ACCESS, (t, v) -> retained.add(new Object[] {t, v}));
    Map<String, Map<String, Long>> readAfterParse = new TreeMap<>();
    for (Object[] r : retained) {
      @SuppressWarnings("unchecked")
      Map<String, Object> v = (Map<String, Object>) r[1];
      readAfterParse
          .computeIfAbsent((String) r[0], k -> new TreeMap<>())
          .merge(shallow.apply(v), 1L, Long::sum);
    }
    assertEquals(readInCallback, readAfterParse);
  }

  // --- collection ---------------------------------------------------------------------------

  private interface Sink {
    void accept(String type, Map<String, Object> value);
  }

  /** Parses with the generic path ({@code strategy == null}) or the generated one. */
  private static Map<String, Map<String, Long>> collect(
      Path recording, UntypedStrategy strategy, Function<Map<String, Object>, String> renderer)
      throws Exception {
    ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> out =
        new ConcurrentHashMap<>();
    Sink sink =
        (type, value) ->
            out.computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(renderer.apply(value), k -> new AtomicLong())
                .incrementAndGet();
    if (strategy == null) {
      parseGeneric(recording, sink);
    } else {
      parseGenerated(recording, strategy, sink);
    }
    Map<String, Map<String, Long>> result = new TreeMap<>();
    out.forEach(
        (type, renders) -> {
          Map<String, Long> m = new TreeMap<>();
          renders.forEach((r, n) -> m.put(r, n.get()));
          result.put(type, m);
        });
    return result;
  }

  private static void parseGeneric(Path recording, Sink sink) throws Exception {
    parse(
        recording,
        UntypedStrategy.SPARSE_ACCESS,
        new EventStream(null) {
          @Override
          protected void onEventValue(MetadataClass type, Map<String, Object> value, Control c) {
            sink.accept(type.getName(), value);
          }
        });
  }

  private static void parseGenerated(Path recording, UntypedStrategy strategy, Sink sink)
      throws Exception {
    parse(
        recording,
        strategy,
        new EventStreamGenerated(null) {
          @Override
          protected void onEventValue(MetadataClass type, Map<String, Object> value, Control c) {
            sink.accept(type.getName(), value);
          }
        });
  }

  private static void parse(Path recording, UntypedStrategy strategy, ChunkParserListener listener)
      throws Exception {
    assertTrue(Files.isRegularFile(recording), "missing recording " + recording.toAbsolutePath());
    ParsingContextImpl ctx = (ParsingContextImpl) ParsingContext.create();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(ctx.untypedContextFactory(strategy))) {
      parser.parse(recording, listener);
    }
  }

  /** Encodes one value for every field of {@code type}, as the JFR wire format lays it out. */
  private static byte[] encodePayload(MetadataClass type) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (MetadataField field : type.getFields()) {
      if (field.getDimension() == 1) {
        varint(out, 3);
        for (int i = 0; i < 3; i++) {
          encodeOne(out, field, i);
        }
      } else {
        encodeOne(out, field, 1);
      }
    }
    return out.toByteArray();
  }

  private static void encodeOne(ByteArrayOutputStream out, MetadataField field, int i) {
    if (field.hasConstantPool()) {
      varint(out, 0); // no such entry: both paths produce an accessor resolving to nothing
      return;
    }
    long[] ints = {Long.MIN_VALUE, -300, 0x4e2d};
    switch (field.getType().getName()) {
      case "byte":
        out.write(i == 0 ? 0xff : i);
        break;
      case "boolean":
        out.write(i % 2);
        break;
      case "short":
      case "char":
      case "int":
      case "long":
        varint(out, ints[i]);
        break;
      case "float":
        writeBigEndian(out, Float.floatToIntBits(-0.5f * i), 4);
        break;
      case "double":
        writeBigEndian(out, Double.doubleToLongBits(i == 0 ? Double.MAX_VALUE : i), 8);
        break;
      case "java.lang.String":
        {
          byte[] utf8 = (i == 2 ? "\u017e" : "s" + i).getBytes(StandardCharsets.UTF_8);
          out.write(3); // UTF-8 encoded string
          varint(out, utf8.length);
          out.write(utf8, 0, utf8.length);
          break;
        }
      default:
        throw new IllegalArgumentException("not encodable here: " + field.getType().getName());
    }
  }

  /** JFR varint: 7 bits per byte, little-endian groups, the 9th byte carries 8 bits. */
  private static void varint(ByteArrayOutputStream out, long v) {
    for (int i = 0; i < 8; i++) {
      if ((v & ~0x7FL) == 0) {
        out.write((int) v);
        return;
      }
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  private static void writeBigEndian(ByteArrayOutputStream out, long bits, int bytes) {
    for (int b = bytes - 1; b >= 0; b--) {
      out.write((int) (bits >>> (8 * b)));
    }
  }

  // --- rendering ----------------------------------------------------------------------------

  /**
   * Canonical rendering. Constant-pool references are resolved while {@code cpDepth <= 2}; pass
   * {@code Integer.MAX_VALUE} to leave them unresolved (outside a parse, there is no pool).
   */
  static String render(Object v, int cpDepth) {
    if (v == null) {
      return "null";
    }
    if (v instanceof ComplexType) {
      return cpDepth >= 2 ? "cp" : "cp" + render(((ComplexType) v).getValue(), cpDepth + 1);
    }
    if (v instanceof ArrayType) {
      Object a = ((ArrayType) v).getArray();
      StringBuilder sb =
          new StringBuilder("array<")
              .append(a.getClass().getComponentType().getName())
              .append(">[");
      for (int i = 0; i < Array.getLength(a); i++) {
        sb.append(render(Array.get(a, i), cpDepth)).append(',');
      }
      return sb.append(']').toString();
    }
    if (v instanceof Map) {
      StringBuilder sb = new StringBuilder("{");
      new TreeMap<>((Map<?, ?>) v)
          .forEach((k, x) -> sb.append(k).append('=').append(render(x, cpDepth)).append(';'));
      return sb.append('}').toString();
    }
    // Anything else, Object[] included, is tagged with its class: a shape the generic path does
    // not produce shows up as a difference rather than being normalized away.
    return v.getClass().getName() + ":" + v;
  }

  private static long total(Map<String, Long> m) {
    long n = 0;
    for (long c : m.values()) {
      n += c;
    }
    return n;
  }

  private static TreeSet<String> union(Iterable<String> a, Iterable<String> b) {
    TreeSet<String> s = new TreeSet<>();
    a.forEach(s::add);
    b.forEach(s::add);
    return s;
  }

  private static String firstMissing(Map<String, Long> from, Map<String, Long> other) {
    for (Map.Entry<String, Long> e : from.entrySet()) {
      if (!e.getValue().equals(other.get(e.getKey()))) {
        String r = e.getKey();
        return r.length() > 300 ? r.substring(0, 300) + "..." : r;
      }
    }
    return "<none>";
  }
}
