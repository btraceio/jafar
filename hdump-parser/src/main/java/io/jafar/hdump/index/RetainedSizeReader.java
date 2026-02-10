package io.jafar.hdump.index;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped reader for retained.idx with O(1) random access.
 *
 * <p>This reader provides fast access to pre-computed approximate retained sizes using
 * memory-mapped I/O. Retained sizes are computed once and persisted to avoid recomputation.
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li>Random access: O(1) with direct offset calculation
 *   <li>Memory footprint: Only mapped pages consume RAM (lazy loading)
 *   <li>File size: 12 bytes per object (objectId32:4 + retainedSize:8)
 *   <li>Example: 114M objects = 1.37 GB on disk
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * RetainedSizeReader reader = new RetainedSizeReader(indexDir);
 * long retainedSize = reader.getRetainedSize(objectId32);
 * reader.close();
 * }</pre>
 */
public final class RetainedSizeReader implements AutoCloseable {

  private final FileChannel channel;
  private final MappedByteBuffer buffer;
  private final int entryCount;
  private final int formatVersion;

  /**
   * Opens the retained.idx file for reading.
   *
   * @param indexDir directory containing index files
   * @throws IOException if file cannot be opened or is corrupted
   */
  public RetainedSizeReader(Path indexDir) throws IOException {
    Path indexFile = indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME);

    // Open file channel
    channel = FileChannel.open(indexFile, StandardOpenOption.READ);

    // Memory-map entire file
    buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    buffer.order(ByteOrder.BIG_ENDIAN);

    // Read and validate header
    int magic = buffer.getInt();
    if (magic != IndexFormat.RETAINED_INDEX_MAGIC) {
      throw new IOException(
          String.format(
              "Invalid retained.idx magic: 0x%08X (expected 0x%08X)",
              magic, IndexFormat.RETAINED_INDEX_MAGIC));
    }

    formatVersion = buffer.getInt();
    if (formatVersion != IndexFormat.FORMAT_VERSION) {
      throw new IOException(
          String.format(
              "Unsupported format version: %d (expected %d)",
              formatVersion, IndexFormat.FORMAT_VERSION));
    }

    long entryCountLong = buffer.getLong();
    if (entryCountLong > Integer.MAX_VALUE) {
      throw new IOException("Entry count exceeds Integer.MAX_VALUE: " + entryCountLong);
    }
    entryCount = (int) entryCountLong;

    int flags = buffer.getInt(); // Reserved for future use
  }

  /**
   * Gets retained size for an object.
   *
   * <p>Uses direct offset calculation for O(1) access: offset = HEADER_SIZE + (id32 ×
   * ENTRY_SIZE)
   *
   * @param objectId32 32-bit sequential object ID (0 to entryCount-1)
   * @return approximate retained size in bytes
   * @throws IllegalArgumentException if objectId32 is out of range
   */
  public long getRetainedSize(int objectId32) {
    if (objectId32 < 0 || objectId32 >= entryCount) {
      throw new IllegalArgumentException(
          String.format(
              "Object ID out of range: %d (valid range: 0 to %d)", objectId32, entryCount - 1));
    }

    // Calculate entry offset: header + (id32 × entry size) + size field offset
    int offset =
        IndexFormat.HEADER_SIZE
            + (objectId32 * IndexFormat.RETAINED_ENTRY_SIZE)
            + IndexFormat.RETAINED_OFFSET_SIZE;

    return buffer.getLong(offset);
  }

  /**
   * Returns the total number of entries in the index.
   *
   * @return entry count
   */
  public int getEntryCount() {
    return entryCount;
  }

  /**
   * Returns the format version of the index.
   *
   * @return format version
   */
  public int getFormatVersion() {
    return formatVersion;
  }

  @Override
  public void close() throws IOException {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }
}
