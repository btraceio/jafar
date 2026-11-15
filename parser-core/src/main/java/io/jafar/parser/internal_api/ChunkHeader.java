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

  /**
   * The size of the chunk header in bytes: magic (4), version (2 + 2), seven 64-bit fields (size,
   * constant pool offset, metadata offset, start nanos, duration, start ticks, frequency) and the
   * 32-bit features word.
   */
  public static final int SIZE = 68;

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
    long left = recording.available();
    // The magic is checked first, whenever there are four bytes to check: a file that is not a
    // recording at all should be told so ("Invalid JFR Magic Number"), whatever its length, and
    // only something that starts like a chunk can be a truncated one. The Go parser orders the two
    // checks the same way (checkMagic, then the header length).
    if (left >= Integer.BYTES) {
      int magic = recording.readInt();
      if (magic != MAGIC_BE) {
        throw new IOException("Invalid JFR Magic Number: " + Integer.toHexString(magic));
      }
    }
    if (left < SIZE) {
      // Reading on would run off the end of the data half-way through a field.
      throw new IOException(
          "Truncated chunk "
              + index
              + " at offset "
              + offset
              + ": "
              + left
              + " byte(s) left, "
              + SIZE
              + " needed for a chunk header");
    }
    major = recording.readShort();
    minor = recording.readShort();
    long declaredSize = recording.readLong();
    long declaredCpOffset = recording.readLong();
    long declaredMetaOffset = recording.readLong();
    // The three fields are 64-bit in the file and int here. Narrowing first and validating after
    // would let a corrupted value wrap into a plausible one - or into zero, where the chunk loop
    // never advances.
    if (declaredSize < SIZE || declaredSize > Integer.MAX_VALUE) {
      throw new IOException(
          "Chunk "
              + index
              + " at offset "
              + offset
              + " declares size "
              + declaredSize
              + "; a chunk is at least its "
              + SIZE
              + "-byte header and at most "
              + Integer.MAX_VALUE
              + " bytes");
    }
    size = (int) declaredSize;
    cpOffset = offsetInChunk(index, "constant pool", declaredCpOffset, declaredSize);
    metaOffset = offsetInChunk(index, "metadata", declaredMetaOffset, declaredSize);
    startNanos = recording.readLong();
    duration = recording.readLong();
    startTicks = recording.readLong();
    frequency = recording.readLong();
    compressed = recording.readInt() != 0;
  }

  /**
   * Narrows a chunk-relative offset from the header. Zero is kept: an unfinished chunk has no
   * metadata or checkpoint yet, and whether that is acceptable is for the code that follows the
   * offset to decide. Anything else has to point past the header and inside the chunk.
   */
  private static int offsetInChunk(int index, String what, long value, long chunkSize)
      throws IOException {
    if (value != 0 && (value < SIZE || value >= chunkSize)) {
      throw new IOException(
          "Chunk "
              + index
              + ": "
              + what
              + " offset "
              + value
              + " is outside the chunk (size "
              + chunkSize
              + ")");
    }
    return (int) value;
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
