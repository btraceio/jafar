package io.jafar.hdump.index;

import io.jafar.utils.CustomByteBuffer;
import io.jafar.utils.SplicedMappedByteBuffer;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;

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

  // Splice size for memory-mapped segments (1GB)
  private static final int SPLICE_SIZE = 1024 * 1024 * 1024;

  private final CustomByteBuffer buffer;
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
    public final byte elementType; // BasicType constant for primitive arrays (BYTE=8, INT=10, etc.), 0 otherwise

    public ObjectMetadata(
        int objectId32, long fileOffset, int dataSize, int classId, int arrayLength, byte flags, byte elementType) {
      this.objectId32 = objectId32;
      this.fileOffset = fileOffset;
      this.dataSize = dataSize;
      this.classId = classId;
      this.arrayLength = arrayLength;
      this.flags = flags;
      this.elementType = elementType;
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

    // Create spliced memory-mapped buffer (handles files > 2GB automatically)
    buffer = CustomByteBuffer.map(indexFile, SPLICE_SIZE);
    buffer.order(ByteOrder.BIG_ENDIAN);

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
    long offset = IndexFormat.HEADER_SIZE + ((long) objectId32 * IndexFormat.OBJECT_ENTRY_SIZE);

    // Position buffer and read entry fields sequentially (26 bytes total)
    buffer.position(offset);
    int id32 = buffer.getInt();
    long fileOffset = buffer.getLong();
    int dataSize = buffer.getInt();
    int classId = buffer.getInt();
    int arrayLength = buffer.getInt();
    byte flags = buffer.get();
    byte elementType = buffer.get();

    // Sanity check
    if (id32 != objectId32) {
      throw new IllegalStateException(
          String.format(
              "Corrupted index: expected ID %d at offset %d, found %d", objectId32, offset, id32));
    }

    return new ObjectMetadata(id32, fileOffset, dataSize, classId, arrayLength, flags, elementType);
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
    // CustomByteBuffer provides close() for resource cleanup
    buffer.close();
  }
}
