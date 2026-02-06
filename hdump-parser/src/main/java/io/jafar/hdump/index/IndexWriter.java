package io.jafar.hdump.index;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes heap dump indexes during parsing with buffered sequential writes.
 *
 * <p>This writer creates index files with the following guarantees:
 *
 * <ul>
 *   <li>Atomic writes via temp files + rename
 *   <li>Sequential writes optimized for SSD/HDD performance
 *   <li>Buffered I/O to reduce system calls
 *   <li>Version headers for future compatibility
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * IndexWriter writer = new IndexWriter(indexDir);
 * try {
 *     writer.beginObjectsIndex(totalObjects);
 *     for (int i = 0; i < totalObjects; i++) {
 *         writer.writeObjectEntry(id32, fileOffset, dataSize, classId, arrayLength, flags);
 *     }
 *     writer.finishObjectsIndex();
 * } finally {
 *     writer.close();
 * }
 * }</pre>
 */
public final class IndexWriter implements AutoCloseable {

  private final Path indexDir;
  private DataOutputStream currentStream;
  private Path currentTempFile;
  private int entriesWritten;

  /**
   * Creates a new index writer for the specified directory.
   *
   * @param indexDir directory where index files will be written
   * @throws IOException if directory cannot be created
   */
  public IndexWriter(Path indexDir) throws IOException {
    this.indexDir = indexDir;
    Files.createDirectories(indexDir);
  }

  /**
   * Begins writing the objects.idx file.
   *
   * @param expectedEntries expected number of entries (for validation)
   * @throws IOException if file cannot be created
   */
  public void beginObjectsIndex(int expectedEntries) throws IOException {
    Path targetFile = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    currentTempFile = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME + ".tmp");

    // Open buffered stream for sequential writes
    currentStream =
        new DataOutputStream(
            new BufferedOutputStream(
                new FileOutputStream(currentTempFile.toFile()),
                1024 * 1024)); // 1 MB buffer

    // Write header
    currentStream.writeInt(IndexFormat.OBJECTS_INDEX_MAGIC); // magic
    currentStream.writeInt(IndexFormat.FORMAT_VERSION); // version
    currentStream.writeLong(expectedEntries); // entry count
    currentStream.writeInt(0); // flags (reserved)

    entriesWritten = 0;
  }

  /**
   * Writes a single object entry to objects.idx.
   *
   * <p>Entry format: [objectId32:4][fileOffset:8][dataSize:4][classId:4][arrayLength:4][flags:1]
   *
   * @param objectId32 32-bit sequential object ID
   * @param fileOffset position in heap dump file where object data starts
   * @param dataSize size of object data in bytes
   * @param classId 32-bit class ID
   * @param arrayLength array length (-1 if not an array)
   * @param flags bitfield (FLAG_IS_OBJECT_ARRAY, FLAG_IS_PRIMITIVE_ARRAY)
   * @throws IOException if write fails
   */
  public void writeObjectEntry(
      int objectId32, long fileOffset, int dataSize, int classId, int arrayLength, byte flags)
      throws IOException {

    if (currentStream == null) {
      throw new IllegalStateException("beginObjectsIndex() not called");
    }

    currentStream.writeInt(objectId32);
    currentStream.writeLong(fileOffset);
    currentStream.writeInt(dataSize);
    currentStream.writeInt(classId);
    currentStream.writeInt(arrayLength);
    currentStream.writeByte(flags);

    entriesWritten++;
  }

  /**
   * Finishes writing objects.idx and atomically renames to final location.
   *
   * @throws IOException if flush or rename fails
   */
  public void finishObjectsIndex() throws IOException {
    if (currentStream == null) {
      throw new IllegalStateException("beginObjectsIndex() not called");
    }

    // Flush and close
    currentStream.flush();
    currentStream.close();
    currentStream = null;

    // Atomic rename
    Path targetFile = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    Files.move(currentTempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    currentTempFile = null;
  }

  /**
   * Begins writing the inbound.idx file.
   *
   * @param expectedEntries expected number of entries
   * @throws IOException if file cannot be created
   */
  public void beginInboundIndex(int expectedEntries) throws IOException {
    Path targetFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);
    currentTempFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME + ".tmp");

    currentStream =
        new DataOutputStream(
            new BufferedOutputStream(
                new FileOutputStream(currentTempFile.toFile()),
                1024 * 1024));

    // Write header
    currentStream.writeInt(IndexFormat.INBOUND_INDEX_MAGIC);
    currentStream.writeInt(IndexFormat.FORMAT_VERSION);
    currentStream.writeLong(expectedEntries);
    currentStream.writeInt(0); // flags

    entriesWritten = 0;
  }

  /**
   * Writes a single inbound count entry.
   *
   * <p>Entry format: [objectId32:4][inboundCount:4]
   *
   * @param objectId32 32-bit object ID
   * @param inboundCount number of inbound references
   * @throws IOException if write fails
   */
  public void writeInboundEntry(int objectId32, int inboundCount) throws IOException {
    if (currentStream == null) {
      throw new IllegalStateException("beginInboundIndex() not called");
    }

    currentStream.writeInt(objectId32);
    currentStream.writeInt(inboundCount);

    entriesWritten++;
  }

  /**
   * Finishes writing inbound.idx and atomically renames to final location.
   *
   * @throws IOException if flush or rename fails
   */
  public void finishInboundIndex() throws IOException {
    if (currentStream == null) {
      throw new IllegalStateException("beginInboundIndex() not called");
    }

    currentStream.flush();
    currentStream.close();
    currentStream = null;

    Path targetFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);
    Files.move(currentTempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    currentTempFile = null;
  }

  /**
   * Returns the number of entries written to the current index.
   *
   * @return entry count
   */
  public int getEntriesWritten() {
    return entriesWritten;
  }

  @Override
  public void close() throws IOException {
    if (currentStream != null) {
      try {
        currentStream.close();
      } finally {
        currentStream = null;
        // Clean up temp file if exists
        if (currentTempFile != null && Files.exists(currentTempFile)) {
          Files.delete(currentTempFile);
          currentTempFile = null;
        }
      }
    }
  }
}
