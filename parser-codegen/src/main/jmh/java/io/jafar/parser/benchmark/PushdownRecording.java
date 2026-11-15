package io.jafar.parser.benchmark;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/**
 * Generates the multi-chunk recording {@link QueryPushdownBenchmark} parses.
 *
 * <p>The recordings checked into the repository are at most a few megabytes and hold a handful of
 * chunks, so they cannot show what skipping events or whole chunks is worth. This writes one with a
 * known shape instead: {@code chunks} recordings made one after the other with the running JDK and
 * concatenated. A JFR chunk is self-contained (its own header, metadata and constant pools), so the
 * concatenation of JFR files is a JFR file; {@code jfr assemble} builds one the same way.
 *
 * <p>Each segment holds three event types whose counts differ by orders of magnitude, because the
 * value of a type filter depends on how selective the type is:
 *
 * <ul>
 *   <li>{@value #COMMON}: {@link #COMMON_PER_CHUNK} per segment, no stack trace
 *   <li>{@value #SAMPLED}: {@link #SAMPLED_PER_CHUNK} per segment, with a stack trace, so decoding
 *       it resolves constant-pool references the way an execution sample does
 *   <li>{@value #RARE}: {@link #RARE_PER_CHUNK} per segment
 * </ul>
 *
 * <p>The counts are fixed rather than timed, so the file is the same shape on every machine and the
 * benchmark can check that it saw every event.
 *
 * <p>Halfway through, the generator pauses for {@link #HALFWAY_PAUSE_MILLIS}, so that the second
 * half of the chunks is separated in time from the first; see {@link #generate}.
 */
public final class PushdownRecording {
  public static final String COMMON = "bench.Common";
  public static final String SAMPLED = "bench.Sampled";
  public static final String RARE = "bench.Rare";

  public static final int COMMON_PER_CHUNK = 100_000;
  public static final int SAMPLED_PER_CHUNK = 10_000;
  public static final int RARE_PER_CHUNK = 10;

  /**
   * The pause after the first half of the segments: longer than the second of slack that chunk
   * skipping keeps, so that a range starting with the second half skips exactly the first.
   */
  public static final long HALFWAY_PAUSE_MILLIS = 1_500;

  private static final int MAGIC = 0x464C5200; // "FLR\0"
  private static final String[] PAYLOADS = payloads();

  private PushdownRecording() {}

  @Name(COMMON)
  @StackTrace(false)
  static final class Common extends Event {
    long seq;
    int bucket;
    String payload;
  }

  @Name(SAMPLED)
  @StackTrace(true)
  static final class Sampled extends Event {
    long seq;
  }

  @Name(RARE)
  @StackTrace(false)
  static final class Rare extends Event {
    long seq;
    String payload;
  }

  /**
   * Writes the recording to {@code out}, replacing it.
   *
   * @param out the file to write
   * @param chunks how many segments to record and concatenate
   * @return the number of chunks in the file, read back from its chunk headers
   */
  public static int generate(Path out, int chunks) throws IOException, InterruptedException {
    if (chunks < 1) {
      throw new IllegalArgumentException("chunks must be at least 1: " + chunks);
    }
    Path segment = Files.createTempFile(out.toAbsolutePath().getParent(), "segment", ".jfr");
    try (OutputStream os =
        Files.newOutputStream(
            out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      long seq = 0;
      for (int i = 0; i < chunks; i++) {
        try (Recording recording = new Recording()) {
          recording.enable(Common.class);
          recording.enable(Sampled.class);
          recording.enable(Rare.class);
          recording.start();
          seq = emit(seq);
          recording.stop();
          recording.dump(segment);
        }
        Files.copy(segment, os);
        if (i == chunks / 2 - 1) {
          // The segments are recorded back to back, a fraction of a second each. Chunk skipping
          // by start time keeps a second of slack after a chunk's end, so without a pause a
          // range starting in the middle would still reach back over several segments.
          Thread.sleep(HALFWAY_PAUSE_MILLIS);
        }
      }
    } finally {
      Files.deleteIfExists(segment);
    }
    return countChunks(out);
  }

  private static long emit(long seq) {
    int rareEvery = COMMON_PER_CHUNK / RARE_PER_CHUNK;
    int sampledEvery = COMMON_PER_CHUNK / SAMPLED_PER_CHUNK;
    for (int i = 0; i < COMMON_PER_CHUNK; i++) {
      Common common = new Common();
      common.seq = seq++;
      common.bucket = i & 0xff;
      common.payload = PAYLOADS[i & (PAYLOADS.length - 1)];
      common.commit();
      if (i % sampledEvery == 0) {
        sampleAtDepth(1 + (i / sampledEvery) % 24, seq++);
      }
      if (i % rareEvery == 0) {
        Rare rare = new Rare();
        rare.seq = seq++;
        rare.payload = PAYLOADS[(i / rareEvery) & (PAYLOADS.length - 1)];
        rare.commit();
      }
    }
    return seq;
  }

  /** Commits a {@link Sampled} event from {@code depth} frames down, to vary its stack trace. */
  private static void sampleAtDepth(int depth, long seq) {
    if (depth > 0) {
      sampleAtDepth(depth - 1, seq);
      return;
    }
    Sampled sampled = new Sampled();
    sampled.seq = seq;
    sampled.commit();
  }

  /**
   * Counts the chunks of a recording by walking its chunk headers: each starts with the magic
   * number, two version shorts and the chunk's size, which is the offset of the next one.
   */
  public static int countChunks(Path recording) throws IOException {
    int count = 0;
    long size = Files.size(recording);
    long pos = 0;
    try (InputStream is = Files.newInputStream(recording);
        DataInputStream in = new DataInputStream(is)) {
      while (pos < size) {
        if (in.readInt() != MAGIC) {
          throw new IOException("No chunk magic at offset " + pos + " of " + recording);
        }
        in.readInt(); // major and minor version
        long chunkSize = in.readLong();
        if (chunkSize < 16) {
          throw new IOException("Chunk at offset " + pos + " declares size " + chunkSize);
        }
        long toSkip = chunkSize - 16;
        while (toSkip > 0) {
          long skipped = in.skip(toSkip);
          if (skipped <= 0) {
            throw new IOException("Truncated chunk at offset " + pos + " of " + recording);
          }
          toSkip -= skipped;
        }
        pos += chunkSize;
        count++;
      }
    }
    return count;
  }

  /**
   * The start time of each chunk, in epoch nanoseconds, from the chunk headers: after the magic
   * number, the version and the three 64-bit size and offset fields.
   */
  public static long[] chunkStartNanos(Path recording) throws IOException {
    long[] starts = new long[countChunks(recording)];
    long size = Files.size(recording);
    long pos = 0;
    int index = 0;
    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(recording)) {
      java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(40);
      while (pos < size) {
        header.clear();
        if (channel.read(header, pos) < 40) {
          throw new IOException("Truncated chunk header at offset " + pos + " of " + recording);
        }
        starts[index++] = header.getLong(32);
        pos += header.getLong(8);
      }
    }
    return starts;
  }

  private static String[] payloads() {
    String[] values = new String[64];
    for (int i = 0; i < values.length; i++) {
      values[i] = "payload-" + i + "-" + Integer.toHexString(i * 0x9E3779B1);
    }
    return values;
  }

  /**
   * Writes the recording to a file, to time shell queries against the same data by hand.
   *
   * <p>Usage: {@code PushdownRecording <out.jfr> [chunks]}
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: PushdownRecording <out.jfr> [chunks]");
      System.exit(2);
    }
    Path out = Path.of(args[0]);
    int requested = args.length == 2 ? Integer.parseInt(args[1]) : 32;
    int written = generate(out, requested);
    System.out.println(
        out
            + ": "
            + Files.size(out)
            + " bytes, "
            + written
            + " chunks ("
            + requested
            + " segments)");
  }
}
