package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.utils.ByteArrayByteBuffer;
import io.jafar.utils.CustomByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RecordingStreamReader#readVarint()} on varints of every length from 1 to 9 bytes, written
 * back to back, read through every kind of buffer a reader sits on.
 *
 * <p>Each value is written in its shortest form and padded to every longer length, the way the JDK
 * writes event sizes: continuation bits set over zero groups. After each varint the value, the
 * position and {@link RecordingStreamReader#remaining()} must all be right.
 */
class RecordingStreamReaderVarintTest {

  @TempDir Path tempDir;

  /** A value, and the bytes it was written as. */
  private static final class Encoded {
    final long value;
    final int length;

    Encoded(long value, int length) {
      this.value = value;
      this.length = length;
    }
  }

  /** The shortest length that holds {@code value}: 1 to 9. */
  private static int shortest(long value) {
    for (int length = 1; length < 9; length++) {
      if ((value >>> (7 * length)) == 0) {
        return length;
      }
    }
    return 9;
  }

  /**
   * Writes {@code value} in {@code length} bytes: 7 bits per byte, least significant group first,
   * the high bit set on every byte but the last; a ninth byte holds the top 8 bits whole.
   */
  private static void write(ByteArrayOutputStream out, long value, int length) {
    long rest = value;
    for (int i = 0; i < length - 1; i++) {
      out.write((int) ((rest & 0x7F) | 0x80));
      rest >>>= 7;
    }
    out.write((int) (length == 9 ? rest & 0xFF : rest & 0x7F));
  }

  private static List<Encoded> varints() {
    Random random = new Random(42);
    List<Long> values = new ArrayList<>();
    values.add(0L);
    values.add(-1L);
    values.add(Long.MIN_VALUE);
    values.add(Long.MAX_VALUE);
    for (int length = 1; length <= 9; length++) {
      long min = length == 1 ? 0 : 1L << (7 * (length - 1));
      long max = length == 9 ? -1L : (1L << (7 * length)) - 1;
      values.add(min);
      values.add(max);
      for (int i = 0; i < 20; i++) {
        long v = random.nextLong();
        values.add(length == 9 ? v | min : (v & max) | min);
      }
    }
    List<Encoded> out = new ArrayList<>();
    for (long value : values) {
      for (int length = shortest(value); length <= 9; length++) {
        out.add(new Encoded(value, length));
      }
    }
    return out;
  }

  private static byte[] bytes(List<Encoded> varints) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Encoded e : varints) {
      write(out, e.value, e.length);
    }
    return out.toByteArray();
  }

  private static void assertReadsAll(String what, RecordingStreamReader reader, int size) {
    List<Encoded> varints = varints();
    long remaining = size;
    for (int i = 0; i < varints.size(); i++) {
      Encoded e = varints.get(i);
      assertEquals(e.value, reader.readVarint(), what + ": value of varint " + i);
      remaining -= e.length;
      assertEquals(remaining, reader.remaining(), what + ": remaining after varint " + i);
      assertEquals(size - remaining, reader.position(), what + ": position after varint " + i);
    }
    assertEquals(0, remaining, what + ": every byte read");
  }

  private Path file(byte[] bytes) throws IOException {
    Path file = Files.createTempFile(tempDir, "varints", ".bin");
    Files.write(file, bytes);
    return file;
  }

  @Test
  void bufferedOverAWrappedArray() {
    byte[] bytes = bytes(varints());
    assertReadsAll(
        "wrap", new BufferedRecordingStreamReader(CustomByteBuffer.wrap(bytes)), bytes.length);
  }

  @Test
  void bufferedOverAByteArrayBuffer() {
    byte[] bytes = bytes(varints());
    assertReadsAll(
        "byte array",
        new BufferedRecordingStreamReader(new ByteArrayByteBuffer(bytes)),
        bytes.length);
  }

  @Test
  void mappedFile() throws IOException {
    byte[] bytes = bytes(varints());
    RecordingStreamReader reader =
        new RecordingStreamReader.MappedRecordingStreamReader(file(bytes));
    try {
      assertReadsAll("mapped", reader, bytes.length);
    } finally {
      reader.close();
    }
  }

  @Test
  void mappedFileInSplicesThatSplitVarints() throws IOException {
    // 7-byte splices: varints of every length straddle a splice boundary somewhere.
    byte[] bytes = bytes(varints());
    assertReadsAll(
        "spliced",
        new BufferedRecordingStreamReader(CustomByteBuffer.map(file(bytes), 7)),
        bytes.length);
  }
}
