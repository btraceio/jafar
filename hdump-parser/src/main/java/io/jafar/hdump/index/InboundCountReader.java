package io.jafar.hdump.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped reader for inbound.idx with O(1) random access.
 *
 * <p>This reader provides fast access to inbound reference counts using memory-mapped I/O.
 * Inbound counts are used for approximate retained size computation.
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li>Random access: O(1) with direct offset calculation
 *   <li>Memory footprint: Only mapped pages consume RAM (lazy loading)
 *   <li>File size: 8 bytes per object (objectId32:4 + inboundCount:4)
 *   <li>Example: 114M objects = 914 MB on disk
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * InboundCountReader reader = new InboundCountReader(indexDir);
 * int count = reader.getInboundCount(objectId32);
 * reader.close();
 * }</pre>
 */
public final class InboundCountReader implements AutoCloseable {

  private final FileChannel channel;
  private final MappedByteBuffer buffer;
  private final int entryCount;
  private final int formatVersion;

  /**
   * Opens the inbound.idx file for reading.
   *
   * @param indexDir directory containing index files
   * @throws IOException if file cannot be opened or is corrupted
   */
  public InboundCountReader(Path indexDir) throws IOException {
    Path indexFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);

    // Open file channel
    channel = FileChannel.open(indexFile, StandardOpenOption.READ);

    // Memory-map entire file
    buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    buffer.order(ByteOrder.BIG_ENDIAN);

    // Read and validate header
    int magic = buffer.getInt();
    if (magic != IndexFormat.INBOUND_INDEX_MAGIC) {
      throw new IOException(
          String.format(
              "Invalid inbound.idx magic: 0x%08X (expected 0x%08X)",
              magic, IndexFormat.INBOUND_INDEX_MAGIC));
    }

    formatVersion = buffer.getInt();
    if (formatVersion != IndexFormat.FORMAT_VERSION) {
      throw new IOException(
          String.format(
              "Unsupported format version: %d (expected %d)", formatVersion, IndexFormat.FORMAT_VERSION));
    }

    long entryCountLong = buffer.getLong();
    if (entryCountLong > Integer.MAX_VALUE) {
      throw new IOException("Entry count exceeds Integer.MAX_VALUE: " + entryCountLong);
    }
    entryCount = (int) entryCountLong;

    int flags = buffer.getInt(); // Reserved for future use
  }

  /**
   * Gets inbound reference count for an object.
   *
   * <p>Uses direct offset calculation for O(1) access: offset = HEADER_SIZE + (id32 ×
   * ENTRY_SIZE)
   *
   * @param objectId32 32-bit sequential object ID (0 to entryCount-1)
   * @return inbound reference count
   * @throws IllegalArgumentException if objectId32 is out of range
   */
  public int getInboundCount(int objectId32) {
    if (objectId32 < 0 || objectId32 >= entryCount) {
      throw new IllegalArgumentException(
          String.format(
              "Object ID out of range: %d (valid range: 0 to %d)", objectId32, entryCount - 1));
    }

    // Calculate entry offset: header + (id32 × entry size)
    int offset =
        IndexFormat.HEADER_SIZE
            + (objectId32 * IndexFormat.INBOUND_ENTRY_SIZE)
            + IndexFormat.INBOUND_OFFSET_COUNT;

    return buffer.getInt(offset);
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
