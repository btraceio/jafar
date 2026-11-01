package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * JFR type ids are scoped to a chunk: a recording whose chunks come from different producers can
 * give the same id to different types. The generated deserializers are shared across the chunks of
 * a parse, so they must be keyed by the type's structure, not its id; keyed by id, the second chunk
 * decodes its events with the first chunk's deserializer.
 *
 * <p>Chunk 1 declares id 100 as {@code test.A {x: long}}; chunk 2 declares id 100 as {@code test.B
 * {s: String}} and id 101 as the same {@code test.A}.
 */
public class GeneratedDeserializerCacheTest {
  private static final long T_LONG = 6;
  private static final long T_STRING = 9;

  private static SyntheticRecording.Element eventClass(long id, String name, String field, long t) {
    return elem("class", "id", Long.toString(id), "name", name, "superType", "jdk.jfr.Event")
        .with(elem("field", "name", field, "class", Long.toString(t)));
  }

  private static SyntheticRecording.Element metadata(SyntheticRecording.Element... events) {
    SyntheticRecording.Element md =
        elem("metadata")
            .with(primitiveClass(T_LONG, "long"), primitiveClass(T_STRING, "java.lang.String"));
    md.with(events);
    return elem("root").with(md);
  }

  private static byte[] chunk(SyntheticRecording.Element metadata, byte[]... events) {
    java.util.List<byte[]> list = new java.util.ArrayList<>();
    Collections.addAll(list, events);
    return SyntheticRecording.chunk(
        metadata, list, 1_700_000_000_000_000_000L, 5_000_000L, 1_000_000L, 1000);
  }

  private static Path writeRecording(Path dir) throws Exception {
    byte[] first =
        chunk(
            metadata(eventClass(100, "test.A", "x", T_LONG)),
            new SyntheticRecording.Bytes().varint(100).varint(41).toByteArray());
    byte[] second =
        chunk(
            metadata(
                eventClass(100, "test.B", "s", T_STRING), eventClass(101, "test.A", "x", T_LONG)),
            new SyntheticRecording.Bytes().varint(100).utf8("bee").toByteArray(),
            new SyntheticRecording.Bytes().varint(101).varint(42).toByteArray());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    out.write(second);
    Path file = dir.resolve("two-chunks.jfr");
    Files.write(file, out.toByteArray());
    return file;
  }

  @ParameterizedTest
  @EnumSource(
      value = UntypedStrategy.class,
      names = {"FULL_ITERATION", "SPARSE_ACCESS"})
  void chunksReusingATypeIdDecodeWithTheirOwnTypes(UntypedStrategy strategy, @TempDir Path dir)
      throws Exception {
    Path file = writeRecording(dir);
    Map<String, Map<String, Object>> seen = new ConcurrentHashMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory(strategy))) {
      parser.parse(
          file,
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> value, Control ctl) {
              seen.put(type.getName() + "#" + value, new HashMap<>(value));
            }
          });
    }
    assertEquals(
        new java.util.TreeSet<>(
            java.util.Arrays.asList("test.A#{x=41}", "test.A#{x=42}", "test.B#{s=bee}")),
        new java.util.TreeSet<>(seen.keySet()),
        strategy + ": every event decoded with its own chunk's type");
  }
}
