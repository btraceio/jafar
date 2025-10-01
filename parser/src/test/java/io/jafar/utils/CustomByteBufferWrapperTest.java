package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CustomByteBufferWrapperTest {

  private static Path smallFile;

  @BeforeAll
  static void setupAll() throws IOException {
    smallFile = Files.createTempFile("jafar-small-", ".bin");
    smallFile.toFile().deleteOnExit();
    byte[] payload = new byte[] {10, 20, 30, 40, 50};
    Files.write(smallFile, payload);
  }

  @Test
  void mapsSmallFileToWrapperAndHonorsNativeFlag() throws IOException {
    CustomByteBuffer buf = CustomByteBuffer.map(smallFile, 4096);

    // order should default to native
    assertEquals(ByteOrder.nativeOrder(), buf.order());
    boolean expectedNativeFlag = (ByteBuffer.allocate(1).order() == ByteOrder.nativeOrder());
    assertEquals(expectedNativeFlag, buf.isNativeOrder());

    // switching order doesn't change native flag
    buf.order(ByteOrder.BIG_ENDIAN);
    assertEquals(ByteOrder.BIG_ENDIAN, buf.order());
    assertEquals(expectedNativeFlag, buf.isNativeOrder());

    // basic reads
    buf.position(0);
    assertEquals(10, buf.get());
    assertEquals(20, buf.get());
  }
}

