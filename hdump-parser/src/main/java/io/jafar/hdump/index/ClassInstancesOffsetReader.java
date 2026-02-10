package io.jafar.hdump.index;

import io.jafar.utils.CustomByteBuffer;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Memory-mapped reader for classinstances-offset.idx with O(1) random access.
 *
 * <p>This reader maps class IDs to their instance list locations in classinstances-data.idx. It
 * enables fast lookup of where a class's instances are stored without scanning all objects.
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li>Random access: O(1) with direct offset calculation
 *   <li>Memory footprint: ~160 KB for 10K classes (entire file fits in L3 cache)
 *   <li>Enables type-filtered queries: 10-60x speedup vs full heap scan
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ClassInstancesOffsetReader reader = new ClassInstancesOffsetReader(indexDir);
 * InstancesLocation loc = reader.getInstancesLocation(classId32);
 * if (loc != null) {
 *   System.out.println("Class has " + loc.instanceCount() + " instances");
 * }
 * reader.close();
 * }</pre>
 */
public final class ClassInstancesOffsetReader implements AutoCloseable {

  // Splice size for memory-mapped segments (1GB, though this file is tiny)
  private static final int SPLICE_SIZE = 1024 * 1024 * 1024;

  private final CustomByteBuffer buffer;
  private final int classCount;
  private final int formatVersion;

  /**
   * Location information for a class's instances in classinstances-data.idx.
   *
   * @param dataFileOffset offset in data file where instance IDs start (in number of int32 values,
   *     not bytes)
   * @param instanceCount number of instances for this class
   */
  public record InstancesLocation(long dataFileOffset, int instanceCount) {}

  /**
   * Opens the classinstances-offset.idx file for reading.
   *
   * @param indexDir directory containing index files
   * @throws IOException if file cannot be opened or is corrupted
   */
  public ClassInstancesOffsetReader(Path indexDir) throws IOException {
    Path indexFile = indexDir.resolve(IndexFormat.CLASSINSTANCES_OFFSET_INDEX_NAME);

    // Create memory-mapped buffer
    buffer = CustomByteBuffer.map(indexFile, SPLICE_SIZE);
    buffer.order(ByteOrder.BIG_ENDIAN);

    // Read and validate header
    int magic = buffer.getInt();
    if (magic != IndexFormat.CLASSINSTANCES_OFFSET_MAGIC) {
      throw new IOException(
          String.format(
              "Invalid classinstances-offset.idx magic: 0x%08X (expected 0x%08X)",
              magic, IndexFormat.CLASSINSTANCES_OFFSET_MAGIC));
    }

    formatVersion = buffer.getInt();
    if (formatVersion != IndexFormat.FORMAT_VERSION) {
      throw new IOException(
          String.format(
              "Unsupported format version: %d (expected %d)", formatVersion, IndexFormat.FORMAT_VERSION));
    }

    long classCountLong = buffer.getLong();
    if (classCountLong > Integer.MAX_VALUE) {
      throw new IOException("Class count exceeds Integer.MAX_VALUE: " + classCountLong);
    }
    classCount = (int) classCountLong;

    int flags = buffer.getInt(); // Reserved for future use
  }

  /**
   * Gets the location of instances for a given class ID.
   *
   * <p>Uses direct offset calculation for O(1) access: offset = HEADER_SIZE + (classId32 ×
   * ENTRY_SIZE)
   *
   * @param classId32 32-bit class ID (0 to classCount-1)
   * @return instance location, or null if class has no instances or invalid ID
   */
  public InstancesLocation getInstancesLocation(int classId32) {
    if (classId32 < 0 || classId32 >= classCount) {
      return null; // Class has no instances or invalid ID
    }

    // Calculate entry offset: header + (classId32 × entry size)
    long entryOffset =
        IndexFormat.HEADER_SIZE
            + ((long) classId32 * IndexFormat.CLASSINSTANCES_OFFSET_ENTRY_SIZE);

    // Read entry fields using absolute-position reads (zero allocations)
    int storedClassId = buffer.getInt(entryOffset + IndexFormat.CLASSINSTANCES_OFFSET_CLASS_ID32);
    long dataFileOffset =
        buffer.getLong(entryOffset + IndexFormat.CLASSINSTANCES_OFFSET_DATA_FILE_OFFSET);
    int instanceCount =
        buffer.getInt(entryOffset + IndexFormat.CLASSINSTANCES_OFFSET_INSTANCE_COUNT);

    // Sanity check
    if (storedClassId != classId32) {
      throw new IllegalStateException(
          String.format(
              "Corrupted index: expected class ID %d at offset %d, found %d",
              classId32, entryOffset, storedClassId));
    }

    // Return null if class has no instances
    if (instanceCount == 0) {
      return null;
    }

    return new InstancesLocation(dataFileOffset, instanceCount);
  }

  /**
   * Returns the total number of classes in the index.
   *
   * @return class count
   */
  public int getClassCount() {
    return classCount;
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
    buffer.close();
  }
}
