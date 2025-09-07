package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SplicedMappedByteBufferTest {
  private static final int FILE_SIZE = 2048;
  private static final int SLICE_SIZE = 71;
  private static Path mapFile;

  private SplicedMappedByteBuffer instance;

  @BeforeAll
  static void setupAll() throws IOException {
    mapFile = Files.createTempFile("jafar-", ".tmp");
    mapFile.toFile().deleteOnExit();
    byte[] data = new byte[FILE_SIZE];
    ByteBuffer bb = MappedByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    bb.put((byte) 1);
    bb.putShort((short) 2);
    bb.putInt((int) 3);
    bb.putFloat((float) 4.1);
    bb.putDouble((double) 5.2);
    bb.putLong((long) 6);
    bb.put(new byte[] {10, 20, 30});

    bb.position(SLICE_SIZE - 1);
    bb.putShort((short) 1);
    bb.position(SLICE_SIZE * 2 - 2);
    bb.putInt((int) 2);
    bb.position(SLICE_SIZE * 3 - 3);
    bb.putFloat((float) 3.1);
    bb.position(SLICE_SIZE * 4 - 1);
    bb.putDouble((double) 4.2);
    bb.position(SLICE_SIZE * 5 - 1);
    bb.putLong((long) 5);
    bb.position(SLICE_SIZE * 6);
    byte[] subdata = new byte[2 * SLICE_SIZE + 17];
    Arrays.fill(subdata, (byte) 8);
    bb.put(subdata);
    Files.write(mapFile, data);
  }

  @BeforeEach
  void setup() throws IOException {
    instance = new SplicedMappedByteBuffer(mapFile, SLICE_SIZE);
  }

  @Test
  void testPosition() {
    assertEquals(0, instance.position());
    instance.position(SLICE_SIZE + 1);
    assertEquals(SLICE_SIZE + 1, instance.position());

    assertThrows(
        BufferOverflowException.class,
        () -> {
          instance.position(FILE_SIZE + 1);
        });
  }

  @Test
  void testLimit() {
    assertEquals(FILE_SIZE, instance.remaining());
    instance.position(SLICE_SIZE + 1);
    assertEquals(FILE_SIZE - SLICE_SIZE - 1, instance.remaining());
  }

  @Test
  void testSlice() {
    CustomByteBuffer sliced1 = instance.slice();
    assertEquals(0, sliced1.position());
    assertEquals(FILE_SIZE, sliced1.remaining());

    sliced1.position(5);
    // make sure the slice does not affect the master copy
    assertEquals(0, instance.position());

    instance.position(SLICE_SIZE + 3);
    CustomByteBuffer sliced2 = instance.slice(SLICE_SIZE + 3, 2 * SLICE_SIZE);
    assertEquals(0, sliced2.position());
    assertEquals(2 * SLICE_SIZE, sliced2.remaining());

    assertThrows(
        BufferOverflowException.class,
        () -> {
          instance.slice(3 * SLICE_SIZE, FILE_SIZE);
        });
  }

  @Test
  void testSubSlice() {
    CustomByteBuffer sliced1 = instance.slice(SLICE_SIZE - 1, 2 * SLICE_SIZE);
    assertEquals(1, sliced1.getShort());
    CustomByteBuffer sliced2 = sliced1.slice(SLICE_SIZE - 1, SLICE_SIZE);
    assertEquals(2, sliced2.getInt());
  }

  @Test
  void getSimple() {
    assertEquals(1, instance.get());
    assertEquals(2, instance.getShort());
    assertEquals(3, instance.getInt());
    assertEquals(4.1f, instance.getFloat());
    assertEquals(5.2d, instance.getDouble());
    assertEquals(6, instance.getLong());

    byte[] dataBuffer = new byte[3];
    byte[] expected = new byte[] {10, 20, 30};

    instance.get(dataBuffer, 0, 3);
    assertArrayEquals(expected, dataBuffer);
  }

  @Test
  void getAcrossSplices() {
    instance.position(SLICE_SIZE - 1);
    assertEquals(1, instance.getShort());

    instance.position(SLICE_SIZE * 2 - 2);
    assertEquals(2, instance.getInt());

    instance.position(SLICE_SIZE * 3 - 3);
    assertEquals(3.1f, instance.getFloat());

    instance.position(SLICE_SIZE * 4 - 1);
    assertEquals(4.2d, instance.getDouble());

    instance.position(SLICE_SIZE * 5 - 1);
    assertEquals(5, instance.getLong());

    byte[] expected = new byte[2 * SLICE_SIZE + 17];
    Arrays.fill(expected, (byte) 8);

    instance.position(SLICE_SIZE * 6);
    byte[] data = new byte[expected.length];
    instance.get(data, 0, data.length);
    assertArrayEquals(expected, data);
  }
}
