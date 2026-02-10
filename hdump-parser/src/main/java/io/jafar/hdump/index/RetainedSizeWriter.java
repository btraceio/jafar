package io.jafar.hdump.index;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writer for retained.idx file that stores computed approximate retained sizes.
 *
 * <p>This writer supports streaming computation - retained sizes can be written incrementally as
 * they're computed, avoiding the need to cache all objects in memory. The file is written to a
 * temporary location and atomically renamed on close for crash-safety.
 *
 * <p><strong>Usage Pattern:</strong>
 *
 * <pre>{@code
 * try (RetainedSizeWriter writer = new RetainedSizeWriter(indexDir, objectCount)) {
 *   for (HeapObject obj : streamingIterator) {
 *     long retainedSize = computeApproximateRetainedSize(obj);
 *     writer.writeEntry(obj.getId32(), retainedSize);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>File Format:</strong>
 *
 * <pre>
 * Header (20 bytes):
 *   [magic:4][version:4][entryCount:8][flags:4]
 *
 * Entries (12 bytes each, sequential by objectId32):
 *   [objectId32:4][retainedSize:8]
 * </pre>
 */
public final class RetainedSizeWriter implements AutoCloseable {

  private final Path indexFile;
  private final Path tempFile;
  private final DataOutputStream out;
  private final int expectedCount;
  private int entriesWritten = 0;

  /**
   * Creates a retained size writer.
   *
   * @param indexDir directory containing index files
   * @param objectCount total number of objects (for validation)
   * @throws IOException if file cannot be created
   */
  public RetainedSizeWriter(Path indexDir, int objectCount) throws IOException {
    this.indexFile = indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME);
    this.tempFile = indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME + ".tmp");
    this.expectedCount = objectCount;
    // CRITICAL: Use BufferedOutputStream for 114M writes (without buffering = 8 minutes, with = seconds)
    this.out = new DataOutputStream(
        new BufferedOutputStream(Files.newOutputStream(tempFile), 1024 * 1024)); // 1MB buffer

    // Write header
    out.writeInt(IndexFormat.RETAINED_INDEX_MAGIC);
    out.writeInt(IndexFormat.FORMAT_VERSION);
    out.writeLong(objectCount);
    out.writeInt(0); // flags (reserved)
  }

  /**
   * Writes a retained size entry.
   *
   * <p><strong>IMPORTANT:</strong> Entries must be written in sequential order by objectId32 (0, 1,
   * 2, ..., N-1) for direct offset calculation during reads.
   *
   * @param objectId32 32-bit sequential object ID
   * @param retainedSize approximate retained size in bytes
   * @throws IOException if write fails
   * @throws IllegalArgumentException if entries written out of order
   */
  public void writeEntry(int objectId32, long retainedSize) throws IOException {
    if (objectId32 != entriesWritten) {
      throw new IllegalArgumentException(
          String.format(
              "Retained size entries must be written sequentially: expected %d, got %d",
              entriesWritten, objectId32));
    }

    out.writeInt(objectId32);
    out.writeLong(retainedSize);
    entriesWritten++;
  }

  /**
   * Closes the writer and atomically commits the index file.
   *
   * @throws IOException if close or rename fails
   * @throws IllegalStateException if not all entries were written
   */
  @Override
  public void close() throws IOException {
    if (entriesWritten != expectedCount) {
      throw new IllegalStateException(
          String.format(
              "Incomplete retained size index: expected %d entries, wrote %d",
              expectedCount, entriesWritten));
    }

    out.close();

    // Atomic rename to final location
    Files.move(tempFile, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }
}
