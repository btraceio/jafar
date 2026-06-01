package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.utils.ByteArrayByteBuffer;
import io.jafar.utils.CustomByteBuffer;
import org.junit.jupiter.api.Test;

class BufferedRecordingStreamReaderTest {

  @Test
  void testBasicRead() {
    byte[] data = new byte[8];
    for (int i = 0; i < 8; i++) data[i] = (byte) i;

    CustomByteBuffer buffer = new ByteArrayByteBuffer(data);
    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(buffer);

    assertEquals(0, reader.position());
    assertEquals(0x0706050403020100L, reader.readLong());
  }

  @Test
  void testGetShort() {
    byte[] data = new byte[2];
    data[0] = 1;
    data[1] = 2;

    CustomByteBuffer buffer = new ByteArrayByteBuffer(data);
    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(buffer);

    assertEquals(0x0201, reader.readShort());
  }

  @Test
  void testGetInt() {
    byte[] data = new byte[4];
    data[0] = 0x1;
    data[1] = 0x2;
    data[2] = 0x3;
    data[3] = 0x4;

    CustomByteBuffer buffer = new ByteArrayByteBuffer(data);
    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(buffer);
    assertEquals(0x04030201, reader.readInt());
  }

  @Test
  void testSliceIndependence() {
    byte[] data = new byte[10];
    CustomByteBuffer buffer = new ByteArrayByteBuffer(data);
    BufferedRecordingStreamReader reader = new BufferedRecordingStreamReader(buffer);

    RecordingStreamReader slice = reader.slice(2, 5);
    assertEquals(2, slice.position());

    reader.position(5);
    assertEquals(2, slice.position()); // Slice should be independent
  }
}
