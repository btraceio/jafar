package io.jafar.hdump.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped reader for objects.idx with O(1) random access.
 *
 * <p>This reader provides fast access to object metadata using memory-mapped I/O. The OS page cache
 * handles caching automatically, so frequently accessed objects stay in RAM.
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li>Random access: O(1) with direct offset calculation
 *   <li>Memory footprint: Only mapped pages consume RAM (lazy loading)
 *   <li>Cache efficiency: OS handles page caching transparently
 *   <li>Typical working set: <100 MB for hot objects
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ObjectIndexReader reader = new ObjectIndexReader(indexDir);
 * ObjectMetadata meta = reader.readObject(objectId32);
 * System.out.println("Object at offset: " + meta.fileOffset);
 * reader.close();
 * }</pre>
 */
public final class ObjectIndexReader implements AutoCloseable {

  private final FileChannel channel;
  private final MappedByteBuffer buffer;
  private final int entryCount;
  private final int formatVersion;

  /**
   * Object metadata from objects.idx.
   */
  public static final class ObjectMetadata {
    public final int objectId32;
    public final long fileOffset;
    public final int dataSize;
    public final int classId;
    public final int arrayLength; // -1 if not an array
    public final byte flags;

    public ObjectMetadata(
        int objectId32, long fileOffset, int dataSize, int classId, int arrayLength, byte flags) {
      this.objectId32 = objectId32;
      this.fileOffset = fileOffset;
      this.dataSize = dataSize;
      this.classId = classId;
      this.arrayLength = arrayLength;
      this.flags = flags;
    }

    public boolean isArray() {
      return arrayLength >= 0;
    }

    public boolean isObjectArray() {
      return (flags & IndexFormat.FLAG_IS_OBJECT_ARRAY) != 0;
    }

    public boolean isPrimitiveArray() {
      return (flags & IndexFormat.FLAG_IS_PRIMITIVE_ARRAY) != 0;
    }
  }

  /**
   * Opens the objects.idx file for reading.
   *
   * @param indexDir directory containing index files
   * @throws IOException if file cannot be opened or is corrupted
   */
  public ObjectIndexReader(Path indexDir) throws IOException {
    Path indexFile = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);

    // Open file channel
    channel = FileChannel.open(indexFile, StandardOpenOption.READ);

    // Memory-map entire file
    buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    buffer.order(ByteOrder.BIG_ENDIAN); // Java default

    // Read and validate header
    int magic = buffer.getInt();
    if (magic != IndexFormat.OBJECTS_INDEX_MAGIC) {
      throw new IOException(
          String.format("Invalid objects.idx magic: 0x%08X (expected 0x%08X)", magic, IndexFormat.OBJECTS_INDEX_MAGIC));
    }

    formatVersion = buffer.getInt();
    if (formatVersion != IndexFormat.FORMAT_VERSION) {
      throw new IOException(
          String.format("Unsupported format version: %d (expected %d)", formatVersion, IndexFormat.FORMAT_VERSION));
    }

    long entryCountLong = buffer.getLong();
    if (entryCountLong > Integer.MAX_VALUE) {
      throw new IOException("Entry count exceeds Integer.MAX_VALUE: " + entryCountLong);
    }
    entryCount = (int) entryCountLong;

    int flags = buffer.getInt(); // Reserved for future use
  }

  /**
   * Reads object metadata by 32-bit object ID.
   *
   * <p>Uses direct offset calculation for O(1) access: offset = HEADER_SIZE + (id32 ×
   * ENTRY_SIZE)
   *
   * @param objectId32 32-bit sequential object ID (0 to entryCount-1)
   * @return object metadata
   * @throws IllegalArgumentException if objectId32 is out of range
   */
  public ObjectMetadata readObject(int objectId32) {
    if (objectId32 < 0 || objectId32 >= entryCount) {
      throw new IllegalArgumentException(
          String.format("Object ID out of range: %d (valid range: 0 to %d)", objectId32, entryCount - 1));
    }

    // Calculate entry offset: header + (id32 × entry size)
    int offset = IndexFormat.HEADER_SIZE + (objectId32 * IndexFormat.OBJECT_ENTRY_SIZE);

    // Read entry fields (33 bytes total)
    int id32 = buffer.getInt(offset);
    long fileOffset = buffer.getLong(offset + 4);
    int dataSize = buffer.getInt(offset + 12);
    int classId = buffer.getInt(offset + 16);
    int arrayLength = buffer.getInt(offset + 20);
    byte flags = buffer.get(offset + 24);

    // Sanity check
    if (id32 != objectId32) {
      throw new IllegalStateException(
          String.format(
              "Corrupted index: expected ID %d at offset %d, found %d", objectId32, offset, id32));
    }

    return new ObjectMetadata(id32, fileOffset, dataSize, classId, arrayLength, flags);
  }

  /**
   * Returns the total number of objects in the index.
   *
   * @return object count
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
    // Note: MappedByteBuffer cannot be explicitly unmapped in standard Java
    // The OS will reclaim the mapping when the buffer is GC'd
    // We can only close the channel
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }
}
