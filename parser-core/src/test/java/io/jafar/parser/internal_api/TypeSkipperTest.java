package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jafar.parser.impl.TypedParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  private static MetadataField fieldOf(String typeName, int dimension, boolean constantPool) {
    MetadataClass type = mock(MetadataClass.class);
    when(type.getName()).thenReturn(typeName);
    when(type.getFields()).thenReturn(List.of());
    try {
      doAnswer(
              inv -> {
                TypeSkipper.createSkipper(type).skip(inv.<RecordingStream>getArgument(0));
                return null;
              })
          .when(type)
          .skipDirect(any());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    MetadataField field = mock(MetadataField.class);
    when(field.getType()).thenReturn(type);
    when(field.getDimension()).thenReturn(dimension);
    when(field.hasConstantPool()).thenReturn(constantPool);
    return field;
  }

  /**
   * Regression test: primitive classes have no fields, so the skipper had no instructions and
   * consumed nothing. Handlers generated by the annotation processor skip unmapped fields this way,
   * which misaligned the stream at the next field.
   */
  @ParameterizedTest
  @CsvSource({"boolean,1", "byte,1", "char,2", "short,2", "int,2", "long,2", "float,4", "double,8"})
  void skipPrimitiveField(String typeName, int size) throws Exception {
    byte[] payload = new byte[size + 1];
    // Multi-byte varints have the continuation bit set on all but the last byte
    if (size == 2) {
      payload[0] = (byte) 0x83;
      payload[1] = 0x01;
    }
    payload[size] = 0x7E;

    try (RecordingStream s = streamOf(payload)) {
      TypeSkipper.skip(fieldOf(typeName, 0, false), s);
      assertEquals(0x7E, s.read() & 0xFF, "stream must be positioned exactly after " + typeName);
    }
  }

  @Test
  void skipPrimitiveArrayField() throws Exception {
    byte[] payload = {varint(2), (byte) 0x83, 0x01, varint(5), 0x7E};

    try (RecordingStream s = streamOf(payload)) {
      TypeSkipper.skip(fieldOf("long", 1, false), s);
      assertEquals(0x7E, s.read() & 0xFF, "stream must be positioned exactly after array data");
    }
  }

  /** Regression test: arrays of constant pool references hold one varint per element. */
  @Test
  void skipConstantPoolArrayField() throws Exception {
    byte[] payload = {varint(2), (byte) 0x83, 0x01, varint(5), 0x7E};

    try (RecordingStream s = streamOf(payload)) {
      TypeSkipper.skip(fieldOf("jdk.types.StackFrame", 1, true), s);
      assertEquals(0x7E, s.read() & 0xFF, "stream must be positioned exactly after CP references");
    }
  }
}
