package io.jafar.parser.api;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Control utilities available to handlers during parsing. Provides access to the current stream
 * position.
 */
public interface Control {
  /**
   * A chunk info wrapper. <br>
   * Provides information like chunk start time, chunk duration and size. It also provides the
   * {@linkplain ChunkInfo#convertTicks(long, TimeUnit)} method to easily convert time value in
   * ticks into the desired time unit.
   */
  interface ChunkInfo {
    ChunkInfo NONE = new ChunkInfo() {};

    /**
     * Chunk start time, as recorded
     *
     * @return the chunk start time or 0 epoch millis if info is not available
     */
    default Instant startTime() {
      return Instant.ofEpochMilli(0);
    }

    /**
     * Chunk duration, as recorded
     *
     * @return the chunk duration or 0 millis if info is not available
     */
    default Duration duration() {
      return Duration.ofMillis(0);
    }

    /**
     * Chunk size, as recorded
     *
     * @return the chunk size or {@literal -1} if no information is available
     */
    default long size() {
      return -1;
    }

    /**
     * Convenience method to quickly covert the ticks value into the desired time unit
     *
     * @param ticks the ticks value
     * @param unit the requested time unit
     * @return the ticks converted to the requested time unit or -1 if there is no info available to
     *     perform the conversion
     */
    default long convertTicks(long ticks, TimeUnit unit) {
      return -1;
    }
  }

  /** Represents the current recording stream while the handler executes. */
  interface Stream {
    /**
     * Returns the current byte position in the recording stream. Meaningful only during handler
     * invocation.
     *
     * @return the current position in the stream
     */
    long position();
  }

  /**
   * Retrieves the stream proxy that allows querying the current byte position. The returned object
   * may become invalid outside handler invocation.
   *
   * @return the stream object
   */
  Stream stream();

  /**
   * Get the information about the current chunk
   *
   * @return the information object
   */
  ChunkInfo chunkInfo();
}
