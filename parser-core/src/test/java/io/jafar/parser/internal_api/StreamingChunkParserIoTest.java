package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
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

  /** Real Datadog profiler recording: 67,657 events in 2 chunks. */
  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /**
   * Event counts and header offsets by chunk index, and the context's chunk index, from a parse.
   */
  private static final class ChunkCounts implements ChunkParserListener {
    final Map<Integer, LongAdder> events = new ConcurrentHashMap<>();
    final Map<Integer, Long> offsets = new ConcurrentHashMap<>();
    final Set<String> mismatchedIndexes = ConcurrentHashMap.newKeySet();

    @Override
    public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
      if (context.getChunkIndex() != chunkIndex || header.order != chunkIndex) {
        mismatchedIndexes.add(
            chunkIndex + ": context " + context.getChunkIndex() + ", header " + header.order);
      }
      events.put(chunkIndex, new LongAdder());
      offsets.put(chunkIndex, header.offset);
      return true;
    }

    @Override
    public boolean onEvent(
        ParserContext context, long typeId, long position, long size, long payloadSize) {
      events.get(context.getChunkIndex()).increment();
      return true;
    }

    Map<Integer, Long> counts() {
      Map<Integer, Long> out = new TreeMap<>();
      events.forEach((chunk, count) -> out.put(chunk, count.sum()));
      return out;
    }
  }

  private static ChunkCounts parse(InputStream in) throws Exception {
    ChunkCounts counts = new ChunkCounts();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(in, counts);
    }
    return counts;
  }

  /**
   * Every chunk of a recording read from a stream, each with the events the file-mapped parse reads
   * from it and at the same offset. The stream path used to read the first chunk only (40,058 of
   * tck-test.jfr's 67,657 events) and number every chunk 1.
   */
  @Test
  void streamReadsEveryChunkLikeThePath() throws Exception {
    ChunkCounts path = new ChunkCounts();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(TCK, path);
    }
    assertEquals(2, path.counts().size(), "tck-test.jfr has 2 chunks");

    ChunkCounts stream = parse(new ByteArrayInputStream(Files.readAllBytes(TCK)));

    assertEquals(path.counts(), stream.counts());
    assertEquals(new TreeMap<>(path.offsets), new TreeMap<>(stream.offsets));
    assertEquals(Set.of(), path.mismatchedIndexes);
    assertEquals(Set.of(), stream.mismatchedIndexes);
  }

  /**
   * {@code available()} is only an estimate: a pipe or socket returns 0 while it waits for data.
   * The stream used to be taken as empty when it did, and nothing was parsed.
   */
  @Test
  void streamThatReportsNothingAvailableIsStillRead() throws Exception {
    InputStream unavailable =
        new FilterInputStream(new ByteArrayInputStream(Files.readAllBytes(TCK))) {
          @Override
          public int available() {
            return 0;
          }
        };
    assertEquals(67_657L, parse(unavailable).counts().values().stream().mapToLong(c -> c).sum());
  }

  /**
   * A header that declares a chunk of almost 2 GiB on a stream that ends right after it fails with
   * an {@link IOException}. The whole chunk used to be allocated before any of it was read, which
   * for this size throws {@link OutOfMemoryError} ("Requested array size exceeds VM limit") on any
   * heap.
   */
  @Test
  void hugeDeclaredChunkOnAShortStreamFailsWithoutAllocatingIt() throws Exception {
    byte[] bytes = Files.readAllBytes(TCK);
    ByteBuffer header = ByteBuffer.wrap(Arrays.copyOf(bytes, 68 + 1000));
    header.putLong(8, Integer.MAX_VALUE); // big-endian chunk size, after magic and version
    IOException e =
        assertThrows(IOException.class, () -> parse(new ByteArrayInputStream(header.array())));
    assertTrue(e.getMessage().contains("chunk 1"), e.getMessage());
  }

  /** A stream that ends inside a chunk fails the parse rather than ending the recording there. */
  @Test
  void streamThatEndsInsideAChunkFails() throws Exception {
    byte[] bytes = Files.readAllBytes(TCK);
    long firstChunkSize = ByteBuffer.wrap(bytes).getLong(8); // big-endian, after magic and version
    byte[] cut = Arrays.copyOf(bytes, (int) firstChunkSize + 1000);
    IOException e = assertThrows(IOException.class, () -> parse(new ByteArrayInputStream(cut)));
    assertTrue(e.getMessage().contains("chunk 2"), e.getMessage());
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
