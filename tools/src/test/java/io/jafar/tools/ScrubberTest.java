package io.jafar.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScrubberTest {

  @ParameterizedTest
  @CsvSource({
    "0, 1",
    "1, 1",
    "127, 1",
    "128, 2",
    "16383, 2",
    "16384, 3",
    "2097151, 3",
    "2097152, 4",
    "268435455, 4",
    "268435456, 5",
    "2147483647, 5" // Integer.MAX_VALUE
  })
  void varintSize(int value, int expectedSize) {
    assertThat(Scrubber.varintSize(value)).isEqualTo(expectedSize);
  }

  // Values at varint encoding boundaries have gaps where no solution exists.
  // E.g. at the 2→3 byte boundary: payloadLen=16383 → total 16385, payloadLen=16384 → total 16387,
  // so totalLen=16386 is unreachable. Similarly totalLen=129 at the 1→2 byte boundary.
  @ParameterizedTest
  @CsvSource({"1", "2", "10", "127", "128", "130", "200", "1000", "16384", "16387"})
  void computeFittingPayloadLengthRoundTrips(int totalLen) {
    int payloadLen = Scrubber.computeFittingPayloadLength(totalLen);
    assertThat(Scrubber.varintSize(payloadLen) + payloadLen).isEqualTo(totalLen);
  }

  @Test
  void computeFittingPayloadLengthRejectsZero() {
    assertThatThrownBy(() -> Scrubber.computeFittingPayloadLength(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writeVarintSingleByte() throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(8);
    Scrubber.writeVarint(buf, 0);
    buf.flip();
    assertThat(buf.remaining()).isEqualTo(1);
    assertThat(buf.get()).isEqualTo((byte) 0);
  }

  @Test
  void writeVarintMaxSingleByte() throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(8);
    Scrubber.writeVarint(buf, 127);
    buf.flip();
    assertThat(buf.remaining()).isEqualTo(1);
    assertThat(buf.get()).isEqualTo((byte) 0x7F);
  }

  @Test
  void writeVarintTwoBytes() throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(8);
    Scrubber.writeVarint(buf, 128);
    buf.flip();
    assertThat(buf.remaining()).isEqualTo(2);
    assertThat(buf.get()).isEqualTo((byte) 0x80);
    assertThat(buf.get()).isEqualTo((byte) 0x01);
  }

  @Test
  void writeVarintLargerValue() throws IOException {
    // 300 = 0b100101100 → low 7 bits: 0b0101100 (0x2C), next: 0b10 (0x02)
    ByteBuffer buf = ByteBuffer.allocate(8);
    Scrubber.writeVarint(buf, 300);
    buf.flip();
    assertThat(buf.remaining()).isEqualTo(2);
    assertThat(buf.get()).isEqualTo((byte) 0xAC); // 0x2C | 0x80
    assertThat(buf.get()).isEqualTo((byte) 0x02);
  }

  @Test
  void copyRegionCopiesCorrectBytes(@TempDir Path tempDir) throws IOException {
    byte[] data = new byte[256];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }
    Path src = tempDir.resolve("src.bin");
    Path dst = tempDir.resolve("dst.bin");
    Files.write(src, data);

    ByteBuffer buf = ByteBuffer.allocateDirect(32);
    try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
        FileChannel out =
            FileChannel.open(
                dst,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
      // Copy bytes [64, 192)
      Scrubber.copyRegion(in, out, 64, 128, buf);
    }

    byte[] result = Files.readAllBytes(dst);
    assertThat(result).hasSize(128);
    for (int i = 0; i < 128; i++) {
      assertThat(result[i]).isEqualTo((byte) (64 + i));
    }
  }

  @Test
  void copyRegionHandlesZeroLength(@TempDir Path tempDir) throws IOException {
    byte[] data = {1, 2, 3};
    Path src = tempDir.resolve("src.bin");
    Path dst = tempDir.resolve("dst.bin");
    Files.write(src, data);

    ByteBuffer buf = ByteBuffer.allocateDirect(32);
    try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
        FileChannel out =
            FileChannel.open(
                dst,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
      Scrubber.copyRegion(in, out, 0, 0, buf);
    }

    assertThat(Files.readAllBytes(dst)).isEmpty();
  }
}
