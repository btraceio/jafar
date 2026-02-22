package io.jafar.parser.internal_api;

import io.jafar.utils.BytePacking;
import java.io.IOException;
import java.nio.ByteOrder;

/**
 * Represents the header of a JFR chunk.
 *
 * <p>A chunk header contains metadata about a JFR recording chunk, including version information,
 * timing data, and offsets to various sections.
 */
public final class ChunkHeader {
  /** Magic number for big-endian JFR chunks (FLR\0). */
  public static final int MAGIC_BE = BytePacking.pack(ByteOrder.BIG_ENDIAN, 'F', 'L', 'R', '\0');

  /** The order/index of this chunk. */
  public final int order;

  /** The byte offset of this chunk in the recording. */
  public final long offset;

  /** The major version number of the JFR format. */
  public final short major;

  /** The minor version number of the JFR format. */
  public final short minor;

  /** The total size of this chunk in bytes. */
  public final int size;

  /** The offset to the constant pool section within this chunk. */
  public final int cpOffset;

  /** The offset to the metadata section within this chunk. */
  public final int metaOffset;

  /** The start time of this chunk in nanoseconds. */
  public final long startNanos;

  /** The duration of this chunk in nanoseconds. */
  public final long duration;

  /** The start time of this chunk in ticks. */
  public final long startTicks;

  /** The frequency of ticks for this chunk. */
  public final long frequency;

  /** Whether this chunk is compressed. */
  public final boolean compressed;

  /**
   * Constructs a new ChunkHeader from the recording stream.
   *
   * @param recording the recording stream to read from
   * @param index the index/order of this chunk
   * @throws IOException if an I/O error occurs during construction
   */
  ChunkHeader(RecordingStream recording, int index) throws IOException {
    order = index;
    offset = recording.position();
    int magic = recording.readInt();
    if (magic != MAGIC_BE) {
      throw new IOException("Invalid JFR Magic Number: " + Integer.toHexString(magic));
    }
    major = recording.readShort();
    minor = recording.readShort();
    size = (int) recording.readLong();
    cpOffset = (int) recording.readLong();
    metaOffset = (int) recording.readLong();
    startNanos = recording.readLong();
    duration = recording.readLong();
    startTicks = recording.readLong();
    frequency = recording.readLong();
    compressed = recording.readInt() != 0;
  }

  @Override
  public String toString() {
    return "ChunkHeader{"
        + "major="
        + major
        + ", minor="
        + minor
        + ", size="
        + size
        + ", offset="
        + offset
        + ", cpOffset="
        + cpOffset
        + ", metaOffset="
        + metaOffset
        + ", startNanos="
        + startNanos
        + ", duration="
        + duration
        + ", startTicks="
        + startTicks
        + ", frequency="
        + frequency
        + ", compressed="
        + compressed
        + '}';
  }
}
