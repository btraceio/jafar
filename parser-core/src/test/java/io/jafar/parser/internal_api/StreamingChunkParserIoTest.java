package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class StreamingChunkParserIoTest {

  @Test
  void testParseValidStream() throws Exception {
    // JFR chunk header is 68 bytes total, all multi-byte fields in big-endian order
    ByteBuffer buf = ByteBuffer.allocate(68);
    buf.order(ByteOrder.BIG_ENDIAN);

    // Magic: raw bytes 'F','L','R','\0' = 0x46 0x4C 0x52 0x00
    buf.put((byte) 0x46).put((byte) 0x4C).put((byte) 0x52).put((byte) 0x00);
    buf.putShort((short) 1); // major
    buf.putShort((short) 0); // minor
    buf.putLong(68L); // size: header-only, no events
    buf.putLong(0L); // cpOffset
    buf.putLong(0L); // metaOffset
    buf.putLong(0L); // startNanos
    buf.putLong(0L); // duration
    buf.putLong(0L); // startTicks
    buf.putLong(0L); // frequency
    buf.putInt(0); // compressed

    ByteArrayInputStream bais = new ByteArrayInputStream(buf.array());
    TestListener listener = new TestListener();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(bais, listener);
    }

    assertEquals(1, listener.onRecordingStartCount);
    assertEquals(1, listener.onChunkStartCount);
  }

  @Test
  void testParseInvalidMagic() throws Exception {
    byte[] data = new byte[64];
    ByteBuffer buf = ByteBuffer.wrap(data);
    buf.order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(0xDEADBEEF);
    buf.put(new byte[60]);

    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    TestListener listener = new TestListener();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      assertThrows(IOException.class, () -> parser.parse(bais, listener));
    }
  }

  @Test
  void testParseEmptyStream() throws Exception {
    ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
    TestListener listener = new TestListener();

    // Should gracefully handle empty stream (0 chunks)
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(bais, listener);
    }

    assertEquals(1, listener.onRecordingStartCount);
    assertEquals(0, listener.onChunkStartCount);
  }

  private static class TestListener implements ChunkParserListener {
    int onRecordingStartCount = 0;
    int onChunkStartCount = 0;

    @Override
    public void onRecordingStart(ParserContext context) {
      onRecordingStartCount++;
    }

    @Override
    public boolean onChunkStart(ParserContext context, int chunkCounter, ChunkHeader header) {
      onChunkStartCount++;
      return true;
    }

    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent event) {
      return true;
    }

    @Override
    public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
      return true;
    }

    @Override
    public boolean onEvent(
        ParserContext context, long eventType, long position, long size, long payloadSize) {
      return true;
    }

    @Override
    public boolean onChunkEnd(ParserContext context, int chunkCounter, boolean aborted) {
      return true;
    }
  }
}
