package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ByteArrayByteBufferTest {

  @Test
  void testLimit() {
    byte[] data = new byte[10];
    ByteArrayByteBuffer buf = new ByteArrayByteBuffer(data);
    assertEquals(10, buf.limit());
  }

  @Test
  void testNativeOrder() {
    byte[] data = new byte[10];
    ByteArrayByteBuffer buf = new ByteArrayByteBuffer(data);
    // Default is LittleEndiAn
    ByteOrder nativeOrder = ByteOrder.nativeOrder();
    assertEquals(
        nativeOrder, buf.order()); // Defaults to native on LE, but let's be explicit or match it
    // Actually, let's just set it to BigEndiAn to verify isNativeOrder works
    buf.order(ByteOrder.BIG_ENDIAN);
    assertFalse(buf.isNativeOrder());
  }

  @Test
  void testSetOrder() {
    byte[] data = new byte[10];
    ByteArrayByteBuffer buf = new ByteArrayByteBuffer(data);
    buf.order(ByteOrder.BIG_ENDIAN);
    assertEquals(ByteOrder.BIG_ENDIAN, buf.order());
  }

  @Test
  void testPositionAndLimit() {
    byte[] data = new byte[10];
    ByteArrayByteBuffer buf = new ByteArrayByteBuffer(data);
    assertDoesNotThrow(() -> buf.position(5));
    assertEquals(5, buf.position());

    // Test boundary - position can be equal to limit
    assertDoesNotThrow(() -> buf.position(10));
    assertThrows(IllegalArgumentException.class, () -> buf.position(11));
  }

  @Test
  void testLimitIsRespected() {
    byte[] data = new byte[10];
    ByteArrayByteBuffer buf = new ByteArrayByteBuffer(data, 0, 5, ByteOrder.LITTLE_ENDIAN);
    assertThrows(IllegalArgumentException.class, () -> buf.position(6));

    buf.position(4);
    assertDoesNotThrow(() -> buf.get()); // position becomes 5
    assertThrows(IllegalArgumentException.class, () -> buf.get()); // limit hit
  }

  @Test
  void testGetShort() {
    byte[] data = new byte[2];
    data[0] = 1;
    data[1] = 2;

    ByteArrayByteBuffer bufLE = new ByteArrayByteBuffer(data, 0, 2, ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x0201, bufLE.getShort());

    ByteArrayByteBuffer bufBE = new ByteArrayByteBuffer(data, 0, 2, ByteOrder.BIG_ENDIAN);
    assertEquals(0x0102, bufBE.getShort());
  }

  @Test
  void testGetInt() {
    byte[] data = new byte[4];
    data[0] = 0x1;
    data[1] = 0x2;
    data[2] = 0x3;
    data[3] = 0x4;

    ByteArrayByteBuffer bufLE = new ByteArrayByteBuffer(data, 0, 4, ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x04030201, bufLE.getInt());

    ByteArrayByteBuffer bufBE = new ByteArrayByteBuffer(data, 0, 4, ByteOrder.BIG_ENDIAN);
    assertEquals(0x01020304, bufBE.getInt());
  }

  @Test
  void testGetLong() {
    byte[] data = new byte[8];
    for (int i = 0; i < 8; i++) data[i] = (byte) i;

    ByteArrayByteBuffer bufLE = new ByteArrayByteBuffer(data, 0, 8, ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x0706050403020100L, bufLE.getLong());

    ByteArrayByteBuffer bufBE = new ByteArrayByteBuffer(data, 0, 8, ByteOrder.BIG_ENDIAN);
    assertEquals(0x0001020304050607L, bufBE.getLong());
  }
}
