package io.jafar.jfr2pprof.convert;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The time a recording covers, read from its chunk headers.
 *
 * <p>The converter asks the parser for the mapped event types only, so a chunk that holds none of
 * them never reaches its handler; the span therefore cannot be gathered from the events, the way
 * {@code Control.ChunkInfo} would give it. It is read here instead, from the fixed part of each
 * chunk header (big-endian, as the format writes it): the magic {@code FLR\0} at offset 0, the
 * chunk size at 8, the start in epoch nanoseconds at 32 and the duration in nanoseconds at 40. The
 * parser reads the same two fields into {@code Control.ChunkInfo#startTime()} and {@code
 * Control.ChunkInfo#duration()}.
 *
 * @param startEpochNanos the earliest chunk start, in nanoseconds since the epoch; 0 for a file
 *     with no chunk header
 * @param durationNanos the sum of the chunk durations, in nanoseconds
 */
record RecordingSpan(long startEpochNanos, long durationNanos) {

  private static final int MAGIC = 0x464C5200; // "FLR\0"

  /** Bytes of the header read per chunk: up to and including the duration field. */
  private static final int HEADER_PREFIX = 48;

  /**
   * Reads the span of the recording at {@code path}.
   *
   * <p>A chunk whose size does not fit the rest of the file (a chunk still being written, or a
   * truncated file) is counted, and the walk stops after it: there is no next header to find.
   *
   * @throws IOException if the file cannot be read, or a chunk does not start with the magic
   */
  static RecordingSpan read(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = channel.size();
      ByteBuffer header = ByteBuffer.allocate(HEADER_PREFIX);
      long start = Long.MAX_VALUE;
      long duration = 0;
      long pos = 0;
      while (size - pos >= HEADER_PREFIX) {
        header.clear();
        while (header.hasRemaining()) {
          if (channel.read(header, pos + header.position()) < 0) {
            throw new EOFException("Truncated chunk header at offset " + pos + " of " + path);
          }
        }
        int magic = header.getInt(0);
        if (magic != MAGIC) {
          throw new IOException(
              "No JFR chunk magic at offset "
                  + pos
                  + " of "
                  + path
                  + ": "
                  + Integer.toHexString(magic));
        }
        start = Math.min(start, header.getLong(32));
        duration += Math.max(0, header.getLong(40));
        long chunkSize = header.getLong(8);
        if (chunkSize < HEADER_PREFIX || chunkSize > size - pos) {
          break;
        }
        pos += chunkSize;
      }
      return new RecordingSpan(start == Long.MAX_VALUE ? 0 : start, duration);
    }
  }
}
