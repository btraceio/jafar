package io.jafar.parser.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regression net for what the nightly Jazzer session found in its first runs on the
 * default branch: a recording that is merely corrupted must be rejected with an {@code IOException}
 * that names the defect, never with whatever the JDK throws when the parser follows a corrupted
 * size, count or offset — and never by not returning at all.
 *
 * <p>The inputs are derived from the committed {@code adv-valid-base} seed (a valid one-chunk,
 * 405-byte recording) rather than committed as crash files, because the corpus directory holds only
 * the generated {@code adv-*} seeds (see {@code test-recordings/build.gradle}).
 *
 * <ul>
 *   <li>The named cases pin the three findings and the chunk-header checks next to them, each with
 *       its message. The nightly's crash input was the first: one byte left after the chunk, which
 *       {@code ChunkHeader} read as a header until {@code BufferUnderflowException}.
 *   <li>The sweep applies every truncation and a fixed set of single-byte corruptions at every
 *       offset and runs each result through {@link EscapeOracle} with an empty allowlist — the same
 *       verdict the fuzz target applies, on an input set that does not depend on a fuzzing session
 *       happening to find it.
 * </ul>
 *
 * <p>Chunk header layout (big-endian): magic at 0, version at 4, then the 64-bit size at 8,
 * constant pool offset at 16 and metadata offset at 24.
 */
public class MalformedRecordingTest {

  private static final int SIZE_FIELD = 8;
  private static final int CP_OFFSET_FIELD = 16;
  private static final int META_OFFSET_FIELD = 24;

  private static final UntypedStrategy[] FUZZ_STRATEGIES = {
    UntypedStrategy.SPARSE_ACCESS, UntypedStrategy.FULL_ITERATION
  };

  /**
   * The nightly's crash input (run 43578193406, sha256 0931b392...): bytes after the last chunk
   * that are too few to be a chunk header.
   */
  @Test
  void trailingBytesShorterThanAChunkHeaderAreRejected() {
    byte[] base = validBase();
    byte[] data = Arrays.copyOf(base, base.length + 1);
    assertRejected(data, "Truncated chunk 2 at offset 405: 1 byte(s) left, 68 needed");
  }

  /**
   * A chunk size of zero used to leave the chunk loop where it was: it sliced an empty chunk,
   * "advanced" by zero bytes and read the same header again, forever, submitting a task each time.
   */
  @Test
  void zeroChunkSizeIsRejectedInsteadOfLoopingForever() {
    byte[] data = validBase();
    putLong(data, SIZE_FIELD, 0);
    assertRejected(data, "Chunk 1 at offset 0 declares size 0");
  }

  /** A size that does not fit the int the parser keeps it in must not be narrowed into one. */
  @Test
  void chunkSizeBeyondIntRangeIsRejected() {
    byte[] data = validBase();
    putLong(data, SIZE_FIELD, 0x201_0000_0000L);
    assertRejected(data, "Chunk 1 at offset 0 declares size 2203318222848");
  }

  /** A recording cut off mid-chunk: the header promises more bytes than the file has. */
  @Test
  void chunkSizeBeyondTheFileIsRejected() {
    byte[] base = validBase();
    byte[] data = Arrays.copyOf(base, base.length - 10);
    assertRejected(data, "declares size 405 but only 395 byte(s) are left");
  }

  @Test
  void constantPoolOffsetOutsideTheChunkIsRejected() {
    byte[] data = validBase();
    putLong(data, CP_OFFSET_FIELD, data.length);
    assertRejected(data, "constant pool offset 405 is outside the chunk (size 405)");
  }

  @Test
  void metadataOffsetInsideTheChunkHeaderIsRejected() {
    byte[] data = validBase();
    putLong(data, META_OFFSET_FIELD, 12);
    assertRejected(data, "metadata offset 12 is outside the chunk (size 405)");
  }

  /** The header of a chunk that was never finished: a payload, but no metadata offset yet. */
  @Test
  void missingMetadataOffsetIsRejected() {
    byte[] data = validBase();
    putLong(data, META_OFFSET_FIELD, 0);
    assertRejected(data, "Chunk 1 has no metadata offset; the chunk is incomplete");
  }

  /**
   * The generic case behind the specific checks: a read that runs off the end of the chunk. Here
   * the metadata offset points two bytes before the chunk's end, so the metadata event's header is
   * cut off. {@code RecordingStream} reports that as an {@code EOFException} naming the read and
   * the position, where the backing buffer's own {@code BufferUnderflowException} named neither.
   */
  @Test
  void readingPastTheEndOfTheChunkIsReportedAsEndOfData() {
    byte[] data = validBase();
    putLong(data, META_OFFSET_FIELD, data.length - 2);
    Throwable root =
        assertRejected(
            data, "Unexpected end of recording data: read of a varint at position 405 of 405");
    assertEquals(java.io.EOFException.class, root.getClass(), "root cause class");
  }

  /**
   * Two checkpoints whose offset deltas point at each other. The chain reader used to follow the
   * deltas for as long as they were non-zero, so this never returned.
   *
   * <p>The recording is the valid base with two copies of its own checkpoint appended to the chunk
   * (they are events of type 1, which the event loop skips): the first with a delta forward to the
   * second, the second with a delta back to the first, and the header's constant pool offset
   * pointing at the first. The detection only starts recording positions once the chain reverses
   * direction - a chain that never does cannot loop - so this is the shape that exercises it.
   */
  @Test
  void cyclicCheckpointChainIsRejectedInsteadOfLoopingForever() {
    byte[] base = validBase();
    int cp = (int) getLong(base, CP_OFFSET_FIELD);
    // checkpoint event: size, type (1), start, duration, delta - all varints - then the rest
    int[] cursor = {cp};
    long size = readVarint(base, cursor);
    int afterSize = cursor[0];
    assertEquals(1, readVarint(base, cursor), "the event at the constant pool offset");
    readVarint(base, cursor); // start
    readVarint(base, cursor); // duration
    int deltaAt = cursor[0];
    assertEquals(0, readVarint(base, cursor), "the base has a single checkpoint");
    byte[] head = Arrays.copyOfRange(base, afterSize, deltaAt);
    byte[] tail = Arrays.copyOfRange(base, cursor[0], cp + (int) size);

    // Both copies carry a 9-byte delta so that they are the same length whatever its sign.
    byte[] first = checkpoint(head, 0, tail);
    first = checkpoint(head, first.length, tail);
    byte[] second = checkpoint(head, -first.length, tail);
    assertEquals(first.length, second.length, "the two checkpoints must be the same length");

    byte[] data = Arrays.copyOf(base, base.length + first.length + second.length);
    System.arraycopy(first, 0, data, base.length, first.length);
    System.arraycopy(second, 0, data, base.length + first.length, second.length);
    putLong(data, SIZE_FIELD, data.length);
    putLong(data, CP_OFFSET_FIELD, base.length);

    assertRejected(data, "cyclic checkpoint chain");
  }

  /** A checkpoint event: its size, then {@code head}, a 9-byte delta varint and {@code tail}. */
  private static byte[] checkpoint(byte[] head, long delta, byte[] tail) {
    int body = head.length + 9 + tail.length;
    // The size counts the size field itself; it is below 2^14 here, so it takes one or two bytes.
    int sizeBytes = body + 1 < 0x80 ? 1 : 2;
    int size = body + sizeBytes;
    byte[] event = new byte[size];
    int at = 0;
    if (sizeBytes == 1) {
      event[at++] = (byte) size;
    } else {
      event[at++] = (byte) ((size & 0x7F) | 0x80);
      event[at++] = (byte) (size >>> 7);
    }
    System.arraycopy(head, 0, event, at, head.length);
    at += head.length;
    for (int i = 0; i < 8; i++) {
      event[at++] = (byte) (((delta >>> (7 * i)) & 0x7F) | 0x80);
    }
    event[at++] = (byte) (delta >>> 56);
    System.arraycopy(tail, 0, event, at, tail.length);
    return event;
  }

  private static long readVarint(byte[] data, int[] cursor) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      byte b = data[cursor[0]++];
      value |= (b & 0x7FL) << (7 * i);
      if (b >= 0) {
        return value;
      }
    }
    return value | ((data[cursor[0]++] & 0xFFL) << 56);
  }

  private static long getLong(byte[] data, int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (data[offset + i] & 0xFF);
    }
    return value;
  }

  /**
   * Every truncation of the valid recording, and a fixed set of corruptions of every single byte,
   * gets the fuzz oracle's verdict: parsed, or rejected with a legal cause chain, within the hang
   * bound. Without the checks this class pins, this fails on the first truncation (a {@code
   * BufferUnderflowException} root).
   */
  @Test
  void everyTruncationAndSingleByteCorruptionGetsALegalVerdict() {
    byte[] base = validBase();
    int checked = 0;
    for (int length = 0; length < base.length; length++) {
      verdict(Arrays.copyOf(base, length), "truncated to " + length + " byte(s)");
      checked++;
    }
    // 0x00/0xFF/0x7F/0x80 are the values that turn a varint or a length into its extremes;
    // flipping the low bit moves an id, an index or a count by one.
    int[] replacements = {0x00, 0xFF, 0x7F, 0x80};
    for (int offset = 0; offset < base.length; offset++) {
      for (int replacement : replacements) {
        if ((base[offset] & 0xFF) == replacement) {
          continue;
        }
        byte[] data = base.clone();
        data[offset] = (byte) replacement;
        verdict(data, String.format("byte %d set to 0x%02X", offset, replacement));
        checked++;
      }
      byte[] data = base.clone();
      data[offset] ^= 1;
      verdict(data, "low bit of byte " + offset + " flipped");
      checked++;
    }
    // Guards the sweep itself: a base that shrank to nothing would "pass" by checking nothing.
    assertTrue(checked > 1500, "the sweep must cover the whole recording; checked " + checked);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static void verdict(byte[] data, String what) {
    try {
      EscapeOracle.check(
          data,
          UntypedParserFuzzTest.parseBothStrategies(data),
          Collections.emptyMap(),
          EscapeOracle.timeoutMs());
    } catch (AssertionError e) {
      throw new AssertionError("adv-valid-base " + what + ": " + e.getMessage(), e);
    }
  }

  /**
   * The parse must fail, under both fuzz strategies and within the hang bound, with an {@code
   * IOException} whose message contains {@code fragment} and whose cause chain ends in an {@code
   * IOException}.
   *
   * @return the root cause delivered under the last strategy
   */
  private static Throwable assertRejected(byte[] data, String fragment) {
    Throwable lastRoot = null;
    for (UntypedStrategy strategy : FUZZ_STRATEGIES) {
      Throwable delivered =
          Assertions.assertTimeoutPreemptively(
              Duration.ofMillis(EscapeOracle.timeoutMs()),
              () -> parse(data, strategy),
              strategy + ": the parse must terminate");
      assertNotNull(delivered, strategy + ": the recording must be rejected");
      assertEquals(IOException.class, delivered.getClass(), strategy + ": delivered class");
      assertTrue(
          delivered.getMessage().contains(fragment),
          strategy + ": message fragment '" + fragment + "'; got '" + delivered.getMessage() + "'");
      Throwable root = delivered;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      assertTrue(
          root instanceof IOException,
          strategy + ": the root cause must be an IOException (D4); got " + root);
      lastRoot = root;
    }
    return lastRoot;
  }

  private static Throwable parse(byte[] data, UntypedStrategy strategy) throws IOException {
    Path recording = Files.createTempFile("malformed", ".jfr");
    try {
      Files.write(recording, data);
      try (UntypedJafarParser parser =
          ParsingContext.create().newUntypedParser(recording, strategy)) {
        parser.handle((type, event, ctl) -> {});
        parser.run();
      }
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      Files.deleteIfExists(recording);
    }
  }

  private static void putLong(byte[] data, int offset, long value) {
    for (int i = 0; i < 8; i++) {
      data[offset + i] = (byte) (value >>> (8 * (7 - i)));
    }
  }

  private static byte[] validBase() {
    String name = "UntypedParserFuzzTestInputs/parseUntyped/adv-valid-base";
    try (InputStream in = EscapeOracle.class.getResourceAsStream(name)) {
      if (in == null) {
        fail("seed " + name + " is missing from the test classpath");
      }
      byte[] data = in.readAllBytes();
      // The offsets and messages above are measured on this exact recording.
      assertEquals(405, data.length, "adv-valid-base changed size; re-measure this test");
      return data;
    } catch (IOException e) {
      throw new AssertionError("failed to read " + name, e);
    }
  }
}
