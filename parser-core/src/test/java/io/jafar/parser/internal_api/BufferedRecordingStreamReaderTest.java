package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class BufferedRecordingStreamReaderTest {

  @Test
  void readsPrimitivesFromBigEndianPayload() {
    // JFR data is big-endian on disk; the reader must interpret payload bytes the same way
    // MappedRecordingStreamReader does (via FileChannel.map(), which is BE-default).
    ByteBuffer src = ByteBuffer.allocate(15).order(ByteOrder.BIG_ENDIAN);
    src.put((byte) 0x42);
    src.putShort((short) 0x1234);
    src.putInt(0xDEADBEEF);
    src.putLong(0x0102030405060708L);
    byte[] payload = src.array();

    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(payload);

    assertEquals(15L, reader.length());
    assertEquals(15L, reader.remaining());
    assertEquals(0L, reader.position());

    assertEquals((byte) 0x42, reader.read());
    assertEquals((short) 0x1234, reader.readShort());
    assertEquals(0xDEADBEEF, reader.readInt());
    assertEquals(0x0102030405060708L, reader.readLong());
    assertEquals(0L, reader.remaining());
  }

  @Test
  void readsVarintsAcrossBoundaryWidths() {
    // Encodes: 0x7F (1-byte), 0x80 (2-byte), 0x3FFF (2-byte), 0x4000 (3-byte), 0xFFFFFFFFL (5-byte)
    byte[] payload =
        new byte[] {
          0x7F,
          (byte) 0x80,
          0x01,
          (byte) 0xFF,
          0x7F,
          (byte) 0x80,
          (byte) 0x80,
          0x01,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          0x0F
        };

    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(payload);

    assertEquals(0x7FL, reader.readVarint());
    assertEquals(0x80L, reader.readVarint());
    assertEquals(0x3FFFL, reader.readVarint());
    assertEquals(0x4000L, reader.readVarint());
    assertEquals(0xFFFFFFFFL, reader.readVarint());
    assertEquals(0L, reader.remaining());
  }

  @Test
  void sliceReturnsBufferedReaderWithIndependentPosition() {
    byte[] payload = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    BufferedRecordingStreamReader root = new BufferedRecordingStreamReader(payload);

    RecordingStreamReader slice = root.slice(3, 4);

    // slice type identity
    assertInstanceOf(BufferedRecordingStreamReader.class, slice);

    // slice sees its own length and starts at position 0
    assertEquals(4L, slice.length());
    assertEquals(4L, slice.remaining());
    assertEquals(0L, slice.position());

    // slice contents start at byte 3 of the parent
    assertEquals((byte) 3, slice.read());
    assertEquals((byte) 4, slice.read());

    // parent position untouched
    assertEquals(0L, root.position());
  }
}
