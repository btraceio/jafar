package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.impl.TypedParserContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class TypeSkipperTest {

  private static RecordingStream streamOf(byte... bytes) throws IOException {
    Path tmp = Files.createTempFile("ts-", ".bin");
    tmp.toFile().deleteOnExit();
    Files.write(tmp, bytes);
    return new RecordingStream(RecordingStreamReader.mapped(tmp), new TypedParserContext());
  }

  /**
   * Builds a TypeSkipper directly from a raw instruction array. Instructions are package-private
   * constants; use reflection-free numeric literals: ARRAY=1, BYTE=2, FLOAT=3, DOUBLE=4, STRING=5,
   * VARINT=6, CP_ENTRY=7
   */
  private static TypeSkipper skipperOf(int... instructions) {
    return new TypeSkipper(instructions);
  }

  // Encode a small non-negative int as a single-byte varint (value < 128).
  private static byte varint(int v) {
    return (byte) (v & 0x7F);
  }

  /** Encodes a JFR UTF-8 string with tag=3 (UTF-8 byte array). */
  private static byte[] utf8Bytes(String s) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] payload = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.write((byte) 3); // UTF-8 tag
    out.write(varint(payload.length));
    out.write(payload);
    return out.toByteArray();
  }

  /**
   * Regression test: array of elements with two instructions (VARINT + STRING).
   *
   * <p>Before the fix, the inner loop's {@code instruction} variable was set once before the loop
   * and never updated, so only VARINT was used for every step — STRING fields were consumed as
   * varints, misaligning the stream.
   */
  @Test
  void skipArrayOfVarintAndString() throws Exception {
    // Build a payload: [cnt=2] [varint=7] [string="hi"] [varint=42] [string="bye"] [sentinel=0x7E]
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(varint(2)); // array count = 2
    // element 0
    out.write(varint(7));
    out.write(utf8Bytes("hi"));
    // element 1
    out.write(varint(42));
    out.write(utf8Bytes("bye"));
    // sentinel byte to verify exact consumption
    out.write(0x7E);

    // Instructions: ARRAY | element-instruction-count=2 | VARINT | STRING
    // ARRAY=1, element-count-field=2 instructions per element, VARINT=6, STRING=5
    TypeSkipper skipper = skipperOf(1, 2, 6, 5);

    try (RecordingStream s = streamOf(out.toByteArray())) {
      skipper.skip(s);
      // If the array loop bug is present the stream will be misaligned and sentinel won't be 0x7E
      assertEquals(0x7E, s.read() & 0xFF, "stream must be positioned exactly after array data");
    }
  }

  /** Single-instruction arrays (VARINT only) must still work correctly. */
  @Test
  void skipArrayOfVarint() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(varint(3)); // count = 3
    out.write(varint(1));
    out.write(varint(2));
    out.write(varint(3));
    out.write(0x7E); // sentinel

    // Instructions: ARRAY | 1 | VARINT
    TypeSkipper skipper = skipperOf(1, 1, 6);

    try (RecordingStream s = streamOf(out.toByteArray())) {
      skipper.skip(s);
      assertEquals(0x7E, s.read() & 0xFF, "stream must be positioned exactly after array data");
    }
  }

  /** Empty arrays (count=0) must advance past all element instructions without reading any. */
  @Test
  void skipEmptyArray() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(varint(0)); // count = 0
    out.write(0x7E); // sentinel

    TypeSkipper skipper = skipperOf(1, 2, 6, 5); // ARRAY | 2 | VARINT | STRING

    try (RecordingStream s = streamOf(out.toByteArray())) {
      skipper.skip(s);
      assertEquals(0x7E, s.read() & 0xFF, "empty array must consume only the count varint");
    }
  }
}
