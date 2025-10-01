package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the ByteBuffer-backed CustomByteBuffer path used for small files (no splicing).
 */
public class CustomByteBufferWrapper8Test {

    private static Path smallFile;

    @BeforeAll
    static void setupAll() throws IOException {
        smallFile = Files.createTempFile("jafar-small-", ".bin");
        smallFile.toFile().deleteOnExit();
        // small payload (below splice size used in mapping) to force ByteBufferWrapper
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        Files.write(smallFile, payload);
    }

    @Test
    void mapsToWrapperAndRespectsOrderContract() throws IOException {
        // Use a small splice size threshold to ensure wrapper path
        CustomByteBuffer buf = CustomByteBuffer.map(smallFile, 4096);

        // Order should be set to native by the implementation
        assertEquals(ByteOrder.nativeOrder(), buf.order());

        // isNativeOrder reflects original delegate order vs native
        boolean expectedNativeFlag = (ByteBuffer.allocate(1).order() == ByteOrder.nativeOrder());
        assertEquals(expectedNativeFlag, buf.isNativeOrder());

        // Changing the order should not affect the recorded native flag
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(ByteOrder.BIG_ENDIAN, buf.order());
        assertEquals(expectedNativeFlag, buf.isNativeOrder());

        // sanity: basic read API works
        buf.position(0);
        assertEquals(1, buf.get());
        assertEquals(2, buf.get());
    }
}

