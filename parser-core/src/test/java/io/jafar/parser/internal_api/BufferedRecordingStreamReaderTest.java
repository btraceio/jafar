package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class BufferedRecordingStreamReaderTest {

  @Test
  void readsPrimitivesFromByteArrayInNativeOrder() {
    ByteBuffer src = ByteBuffer.allocate(15).order(ByteOrder.nativeOrder());
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
}
