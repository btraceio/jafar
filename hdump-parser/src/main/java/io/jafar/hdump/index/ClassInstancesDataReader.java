package io.jafar.hdump.index;

import io.jafar.utils.CustomByteBuffer;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.stream.IntStream;

/**
 * Memory-mapped reader for classinstances-data.idx containing sequential instance IDs.
 *
 * <p>This reader provides access to lists of object IDs grouped by class. Each class's instances
 * are stored contiguously as 32-bit int values. Access locations are obtained from
 * ClassInstancesOffsetReader.
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li>Sequential access: Optimal for streaming class instances
 *   <li>Memory footprint: Only accessed pages loaded (~5-50 MB for typical queries)
 *   <li>File size: ~456 MB for 114M objects (4 bytes per object)
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ClassInstancesOffsetReader offsetReader = new ClassInstancesOffsetReader(indexDir);
 * ClassInstancesDataReader dataReader = new ClassInstancesDataReader(indexDir);
 *
 * InstancesLocation loc = offsetReader.getInstancesLocation(classId32);
 * if (loc != null) {
 *   IntStream instanceIds = dataReader.readInstanceIds(loc.dataFileOffset(), loc.instanceCount());
 *   instanceIds.forEach(id -> System.out.println("Instance: " + id));
 * }
 * }</pre>
 */
public final class ClassInstancesDataReader implements AutoCloseable {

  // Splice size for memory-mapped segments (1GB)
  private static final int SPLICE_SIZE = 1024 * 1024 * 1024;

  private final CustomByteBuffer buffer;
  private final long totalInstances;
  private final int formatVersion;

  /**
   * Opens the classinstances-data.idx file for reading.
   *
   * @param indexDir directory containing index files
   * @throws IOException if file cannot be opened or is corrupted
   */
  public ClassInstancesDataReader(Path indexDir) throws IOException {
    Path indexFile = indexDir.resolve(IndexFormat.CLASSINSTANCES_DATA_INDEX_NAME);

    // Create memory-mapped buffer
    buffer = CustomByteBuffer.map(indexFile, SPLICE_SIZE);
    buffer.order(ByteOrder.BIG_ENDIAN);

    // Read and validate header
    int magic = buffer.getInt();
    if (magic != IndexFormat.CLASSINSTANCES_DATA_MAGIC) {
      throw new IOException(
          String.format(
              "Invalid classinstances-data.idx magic: 0x%08X (expected 0x%08X)",
              magic, IndexFormat.CLASSINSTANCES_DATA_MAGIC));
    }

    formatVersion = buffer.getInt();
    if (formatVersion != IndexFormat.FORMAT_VERSION) {
      throw new IOException(
          String.format(
              "Unsupported format version: %d (expected %d)", formatVersion, IndexFormat.FORMAT_VERSION));
    }

    totalInstances = buffer.getLong();
    int flags = buffer.getInt(); // Reserved for future use
  }

  /**
   * Reads a sequential list of instance IDs starting at the given offset.
   *
   * <p>The offset is specified in number of int32 values (not bytes) from the start of the data
   * section. For example, offset 0 reads the first instance ID, offset 1000 reads the 1001st
   * instance ID.
   *
   * @param offset position in the data file (in number of int32 values, not bytes)
   * @param count number of instance IDs to read
   * @return stream of object IDs (32-bit sequential IDs)
   */
  public IntStream readInstanceIds(long offset, int count) {
    if (count < 0) {
      throw new IllegalArgumentException("Count must be non-negative: " + count);
    }

    if (count == 0) {
      return IntStream.empty();
    }

    // Convert offset from int32 count to byte offset
    // absoluteOffset = HEADER_SIZE + (offset × 4 bytes per int32)
    long absoluteOffset = IndexFormat.HEADER_SIZE + (offset * 4L);

    // Validate bounds
    long endOffset = absoluteOffset + ((long) count * 4);
    if (endOffset > buffer.limit()) {
      throw new IllegalArgumentException(
          String.format(
              "Read would exceed file bounds: offset=%d count=%d endOffset=%d limit=%d",
              offset, count, endOffset, buffer.limit()));
    }

    // Create stream that reads int32 values sequentially
    return IntStream.range(0, count)
        .map(i -> buffer.getInt(absoluteOffset + (i * 4L)));
  }

  /**
   * Returns the total number of instance IDs in the index.
   *
   * @return total instance count
   */
  public long getTotalInstances() {
    return totalInstances;
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
