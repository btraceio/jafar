package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tick normalization is decided per chunk, from that chunk's own metadata.
 *
 * <p>Both chunks declare id 100 as {@code test.T {a: long}}; only chunk 2 annotates {@code a} with
 * {@code @Timestamp("TICKS")}. {@code MetadataClass.equals} ignores annotations (and field names),
 * so the two classes compare equal. A normalization table cached by that equality hands one chunk
 * the other's decision: either chunk 1's raw value is converted, or chunk 2's timestamp is not.
 * Which one depends on which chunk is decoded first, but one of them is always wrong.
 */
public class TemporalAnnotationPerChunkTest {
  private static final long T_LONG = 6;
  private static final long T_STRING = 9;
  private static final long START_TICKS = 1_000_000L;
  private static final long CHUNK1_NANOS = 1_700_000_000_000_000_000L;
  private static final long CHUNK2_NANOS = 1_700_000_100_000_000_000L;
  private static final long OFFSET = 5;

  private static Path writeRecording(Path dir) throws Exception {
    SyntheticRecording.Element plainField =
        elem("field", "name", "a", "class", Long.toString(T_LONG));
    SyntheticRecording.Element timestampField =
        elem("field", "name", "a", "class", Long.toString(T_LONG))
            .with(elem("annotation", "class", "200", "value", "TICKS"));
    SyntheticRecording.Element timestampAnnotation =
        elem(
                "class",
                "id",
                "200",
                "name",
                "jdk.jfr.Timestamp",
                "superType",
                "java.lang.annotation.Annotation")
            .with(elem("field", "name", "value", "class", Long.toString(T_STRING)));

    byte[] event =
        new SyntheticRecording.Bytes().varint(100).varint(START_TICKS + OFFSET).toByteArray();
    byte[] first =
        SyntheticRecording.chunk(
            metadata(eventClass(plainField)),
            Collections.singletonList(event),
            CHUNK1_NANOS,
            START_TICKS,
            1_000_000_000L,
            1_000_000L);
    byte[] second =
        SyntheticRecording.chunk(
            metadata(eventClass(timestampField), timestampAnnotation),
            Collections.singletonList(event),
            CHUNK2_NANOS,
            START_TICKS,
            1_000_000_000L,
            1_000_000L);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    out.write(second);
    return Files.write(dir.resolve("annotation-per-chunk.jfr"), out.toByteArray());
  }

  private static SyntheticRecording.Element eventClass(SyntheticRecording.Element field) {
    return elem("class", "id", "100", "name", "test.T", "superType", "jdk.jfr.Event").with(field);
  }

  private static SyntheticRecording.Element metadata(SyntheticRecording.Element... classes) {
    SyntheticRecording.Element md =
        elem("metadata")
            .with(primitiveClass(T_LONG, "long"), primitiveClass(T_STRING, "java.lang.String"));
    md.with(classes);
    return elem("root").with(md);
  }

  /** Chunk start (epoch nanos) to the value of {@code a} delivered for that chunk's event. */
  private static Map<Long, Long> expected() {
    Map<Long, Long> m = new TreeMap<>();
    m.put(CHUNK1_NANOS, START_TICKS + OFFSET); // not annotated: raw
    m.put(CHUNK2_NANOS, CHUNK2_NANOS + OFFSET); // @Timestamp(TICKS): epoch nanos
    return m;
  }

  private static Map<Long, Long> parse(Path file, UntypedStrategy strategy, boolean generated)
      throws Exception {
    Map<Long, Long> seen = new ConcurrentHashMap<>();
    BiConsumer<Map<String, Object>, Control> sink =
        (value, ctl) ->
            seen.put(
                ctl.chunkInfo().startTime().getEpochSecond() * 1_000_000_000L
                    + ctl.chunkInfo().startTime().getNano(),
                (Long) value.get("a"));
    ChunkParserListener listener =
        generated
            ? new EventStreamGenerated(null) {
              @Override
              protected void onEventValue(
                  MetadataClass type, Map<String, Object> value, Control ctl) {
                sink.accept(value, ctl);
              }
            }
            : new EventStream(null) {
              @Override
              protected void onEventValue(
                  MetadataClass type, Map<String, Object> value, Control ctl) {
                sink.accept(value, ctl);
              }
            };
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory(strategy))) {
      parser.parse(file, listener);
    }
    return new TreeMap<>(seen);
  }

  @Test
  void genericPathNormalizesEachChunkByItsOwnAnnotations(@TempDir Path dir) throws Exception {
    assertEquals(expected(), parse(writeRecording(dir), UntypedStrategy.SPARSE_ACCESS, false));
  }

  @ParameterizedTest(name = "generated {0}")
  @EnumSource(
      value = UntypedStrategy.class,
      names = {"SPARSE_ACCESS", "FULL_ITERATION"})
  void generatedPathNormalizesEachChunkByItsOwnAnnotations(
      UntypedStrategy strategy, @TempDir Path dir) throws Exception {
    // Generated first, in a fresh JVM state for this class: nothing has warmed the generic path.
    assertEquals(expected(), parse(writeRecording(dir), strategy, true));
  }
}
