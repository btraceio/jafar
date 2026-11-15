package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A checkpoint event's size must match the constant pools it holds.
 *
 * <p>Constant pool entries are not length-prefixed: the parser finds each entry by skipping the one
 * before it. A string whose length varint is wrong still skips "successfully", so without a check
 * of the checkpoint's end the rest of the pool is indexed at the wrong offsets and nothing fails.
 * The JDK's {@code jdk.jfr.internal.consumer.ChunkParser} throws {@code "Size of check point event
 * doesn't match content"} for this, and the Go parser bounds every entry by the checkpoint end.
 */
public class CheckpointFramingTest {
  private static final long T_BOOLEAN = 1;
  private static final long T_BYTE = 2;
  private static final long T_STRING = 9;
  private static final long T_EVENT = 100;
  private static final long T_CHUNK_HEADER = 210;

  /** Byte offset of the constant pool offset field in the chunk header. */
  private static final int HEADER_CP_OFFSET = 16;

  @TempDir Path dir;

  private static SyntheticRecording.Element metadata() {
    SyntheticRecording.Element event =
        elem(
                "class",
                "id",
                Long.toString(T_EVENT),
                "name",
                "test.Labelled",
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
                elem("field", "name", "label", "class", Long.toString(T_STRING)));
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_BOOLEAN, "boolean"),
                    primitiveClass(T_BYTE, "byte"),
                    // Not a simpleType: the JVM declares java.lang.String as a plain class.
                    elem("class", "id", Long.toString(T_STRING), "name", "java.lang.String"),
                    // As the JDK declares it: a simple type whose only field is an array.
                    elem(
                            "class",
                            "id",
                            Long.toString(T_CHUNK_HEADER),
                            "name",
                            "jdk.types.ChunkHeader",
                            "simpleType",
                            "true")
                        .with(
                            elem(
                                "field",
                                "name",
                                "payload",
                                "class",
                                Long.toString(T_BYTE),
                                "dimension",
                                "1")),
                    event),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  /** A UTF-8 string entry whose length varint is {@code delta} away from the payload length. */
  private static byte[] misframedString(String s, int delta) {
    byte[] payload = s.getBytes(StandardCharsets.UTF_8);
    return new SyntheticRecording.Bytes()
        .u8(3) // UTF-8 string encoding tag
        .varint(payload.length + delta)
        .raw(payload)
        .toByteArray();
  }

  /** An event whose string field refers to the string constant pool, as the JVM writes it. */
  private static byte[] labelled(long poolIndex) {
    return new SyntheticRecording.Bytes()
        .varint(T_EVENT)
        .u8(2) // string encoding: constant pool reference
        .varint(poolIndex)
        .toByteArray();
  }

  private byte[] chunk(SyntheticRecording.Pool... pools) {
    return SyntheticRecording.chunk(
        metadata(),
        SyntheticRecording.Pool.section(pools),
        List.of(labelled(1), labelled(2)),
        1_700_000_000_000_000_000L,
        0,
        1_000_000_000L,
        1000);
  }

  private Path write(byte[] chunk) throws IOException {
    Path file = dir.resolve("checkpoint-framing.jfr");
    Files.write(file, chunk);
    return file;
  }

  private static List<Object> labels(Path file) throws Exception {
    List<Object> labels = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(file)) {
      p.handle((type, value, ctl) -> labels.add(Values.resolvedShallow(value).get("label")));
      p.run();
    }
    return labels;
  }

  /** Positive control: the same recording, framed correctly, parses and resolves both labels. */
  @Test
  void wellFramedCheckpointResolvesItsPool() throws Exception {
    Path file =
        write(chunk(new SyntheticRecording.Pool(T_STRING).string(1, "alpha").string(2, "beta")));
    assertEquals(List.of("alpha", "beta"), labels(file));
  }

  /**
   * A simple type whose single field is an array is pooled as that array, length included. The JVM
   * writes a {@code jdk.types.ChunkHeader} pool (payload: the 68-byte chunk header) into the last
   * checkpoint of every chunk; the skipper used to unwrap the simple type to {@code byte} and skip
   * one byte of it, which only the checkpoint size check notices, since nothing follows that pool.
   */
  @Test
  void simpleTypeWithArrayFieldIsSkippedWhole() throws Exception {
    byte[] payload = {'F', 'L', 'R', 0, 0, 2, 0, 1};
    Path file =
        write(
            chunk(
                new SyntheticRecording.Pool(T_STRING).string(1, "alpha").string(2, "beta"),
                new SyntheticRecording.Pool(T_CHUNK_HEADER)
                    .entry(
                        1,
                        new SyntheticRecording.Bytes()
                            .varint(payload.length)
                            .raw(payload)
                            .toByteArray())));
    assertEquals(List.of("alpha", "beta"), labels(file));
  }

  enum Misframing {
    /** The last entry's length is one too large: its skip ends one byte past the checkpoint. */
    LAST_ENTRY_TOO_LONG {
      @Override
      SyntheticRecording.Pool pool() {
        return new SyntheticRecording.Pool(T_STRING)
            .string(1, "alpha")
            .entry(2, misframedString("beta", +1));
      }
    },
    /** The last entry's length is one too small: the checkpoint's last byte is never read. */
    LAST_ENTRY_TOO_SHORT {
      @Override
      SyntheticRecording.Pool pool() {
        return new SyntheticRecording.Pool(T_STRING)
            .string(1, "alpha")
            .entry(2, misframedString("beta", -1));
      }
    },
    /**
     * The first entry's length is one too small. Every following read still decodes: the left-over
     * {@code 'a'} is read as index 97, entry 2's index byte ({@code 0x02}) as the encoding tag of a
     * string pool reference, and entry 2's own tag as that reference. The pool ends up holding an
     * index 97 at a garbage offset and no index 2, and the checkpoint's last five bytes are unread.
     * Without the size check this surfaced only when an event referred to index 2, as that event's
     * decode failure ({@code String constant 2 not found}), which a decode error handler can skip.
     */
    FIRST_ENTRY_TOO_SHORT {
      @Override
      SyntheticRecording.Pool pool() {
        return new SyntheticRecording.Pool(T_STRING)
            .entry(1, misframedString("alpha", -1))
            .string(2, "beta");
      }
    };

    abstract SyntheticRecording.Pool pool();
  }

  @ParameterizedTest
  @EnumSource(Misframing.class)
  void stringLengthOffByOneFailsTheParse(Misframing misframing) throws Exception {
    byte[] chunk = chunk(misframing.pool());
    long checkpointOffset = ByteBuffer.wrap(chunk).getLong(HEADER_CP_OFFSET);
    Path file = write(chunk);

    IOException thrown = assertThrows(IOException.class, () -> labels(file));

    String chain = EventDecodeFailureTest.chain(thrown);
    assertTrue(chain.contains("Checkpoint at " + checkpointOffset + ": "), chain);
  }
}
