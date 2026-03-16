package io.jafar.hdump.index;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.internal.BasicType;
import io.jafar.hdump.internal.HeapTag;
import io.jafar.hdump.internal.HprofReader;
import io.jafar.hdump.internal.HprofTag;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds inbound reference count index on-demand.
 *
 * <p>This index is only built when the first retained-size query is executed, implementing the
 * "pay-as-you-go" principle of the progressive index architecture.
 *
 * <p><strong>Algorithm:</strong>
 *
 * <ol>
 *   <li>Scan objects.idx to get all object IDs
 *   <li>For each object, extract outbound references from heap dump
 *   <li>Increment inbound count for each referenced object
 *   <li>Write inbound counts to inbound.idx (sequential by objectId32)
 * </ol>
 *
 * <p><strong>Performance:</strong>
 *
 * <ul>
 *   <li>Build time: ~60 seconds for 114M objects
 *   <li>Memory peak: ~2.4 GB (Int2IntOpenHashMap)
 *   <li>Disk usage: 914 MB for 114M objects (8 bytes per object)
 * </ul>
 *
 * <p><strong>Persistence:</strong> Once built, the index is persisted for reuse on subsequent
 * opens.
 */
public final class InboundIndexBuilder {

  /**
   * Builds inbound reference count index.
   *
   * @param heapDumpPath path to HPROF file
   * @param indexDir directory containing index files
   * @param addressToId32 mapping from 64-bit addresses to 32-bit IDs
   * @param classesById mapping from 64-bit class IDs to class field information
   * @param progressCallback optional progress callback
   * @throws IOException if building fails
   */
  public static void buildInboundIndex(
      Path heapDumpPath,
      Path indexDir,
      Long2IntOpenHashMap addressToId32,
      Long2ObjectMap<? extends HeapClass> classesById,
      ProgressCallback progressCallback)
      throws IOException {

    int objectCount = addressToId32.size();

    // Build inbound counts by scanning heap dump
    Int2IntOpenHashMap inboundCounts = new Int2IntOpenHashMap(objectCount);
    inboundCounts.defaultReturnValue(0);

    try (HprofReader reader = new HprofReader(heapDumpPath)) {
      reader.reset();

      long fileSize = reader.getFileSize();
      long lastProgressReport = 0;
      final long progressInterval = fileSize / 100; // Report every 1%

      while (reader.hasMoreRecords()) {
        var header = reader.readRecordHeader();
        if (header == null) break;

        if (header.tag() == HprofTag.HEAP_DUMP || header.tag() == HprofTag.HEAP_DUMP_SEGMENT) {
          scanReferencesFromHeapDump(reader, header, addressToId32, classesById, inboundCounts);
        } else {
          reader.skipRecordBody(header);
        }

        // Report progress
        if (progressCallback != null && fileSize > 0) {
          long currentPos = reader.position();
          if (currentPos - lastProgressReport > progressInterval) {
            double progress = (double) currentPos / fileSize;
            progressCallback.onProgress(progress, "Scanning references");
            lastProgressReport = currentPos;
          }
        }
      }
    }

    // Write inbound index to disk
    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Writing inbound index");
    }

    writeInboundIndex(indexDir, objectCount, inboundCounts);
  }

  private static void scanReferencesFromHeapDump(
      HprofReader reader,
      HprofReader.RecordHeader header,
      Long2IntOpenHashMap addressToId32,
      Long2ObjectMap<? extends HeapClass> classesById,
      Int2IntOpenHashMap inboundCounts) {

    long endPos = header.bodyPosition() + header.length();

    while (reader.position() < endPos) {
      int subTag = reader.readU1();

      switch (subTag) {
        case HeapTag.INSTANCE_DUMP -> {
          reader.readId(); // object ID (not needed)
          reader.readI4(); // stack trace
          long classAddress = reader.readId();
          int dataSize = reader.readI4();

          // Extract outbound references from instance data
          HeapClass heapClass = classesById.get(classAddress);
          if (heapClass != null) {
            // Get all instance fields including inherited
            List<? extends HeapField> fields = heapClass.getAllInstanceFields();

            // Read each field value and extract references
            for (HeapField field : fields) {
              if (field.getType() == BasicType.OBJECT) {
                // This is an object reference field
                long refAddress = reader.readId();
                if (refAddress != 0) {
                  int refId32 = addressToId32.get(refAddress);
                  if (refId32 != -1) {
                    inboundCounts.addTo(refId32, 1);
                  }
                }
              } else {
                // Skip primitive field value
                reader.readValue(field.getType());
              }
            }
          } else {
            // Class not found, skip the data
            reader.skip(dataSize);
          }
        }
        case HeapTag.OBJ_ARRAY_DUMP -> {
          reader.readId(); // object ID (not needed)
          reader.readI4(); // stack trace
          int length = reader.readI4();
          reader.readId(); // array class ID

          // Extract outbound references from object array
          for (int i = 0; i < length; i++) {
            long refAddress = reader.readId();
            if (refAddress != 0) {
              int refId32 = addressToId32.get(refAddress);
              if (refId32 != -1) {
                inboundCounts.addTo(refId32, 1);
              }
            }
          }
        }
        case HeapTag.PRIM_ARRAY_DUMP -> {
          reader.readId(); // object ID
          reader.readI4(); // stack trace
          int length = reader.readI4();
          int elemType = reader.readU1();
          int elemSize = BasicType.sizeOf(elemType, reader.getIdSize());
          reader.skip(length * elemSize);
          // Primitive arrays have no outbound references
        }
        case HeapTag.CLASS_DUMP -> skipClassDump(reader);
        default -> skipGcRoot(reader, subTag);
      }
    }
  }

  private static void skipClassDump(HprofReader reader) {
    reader.readId(); // class ID
    reader.readI4(); // stack trace
    reader.readId(); // super class
    reader.readId(); // class loader
    reader.readId(); // signers
    reader.readId(); // protection domain
    reader.readId(); // reserved
    reader.readId(); // reserved
    reader.readI4(); // instance size

    // Skip constant pool
    int cpSize = reader.readU2();
    for (int i = 0; i < cpSize; i++) {
      reader.readU2();
      int type = reader.readU1();
      reader.readValue(type);
    }

    // Skip static fields
    int staticCount = reader.readU2();
    for (int i = 0; i < staticCount; i++) {
      reader.readId();
      int type = reader.readU1();
      reader.readValue(type);
    }

    // Skip instance fields
    int fieldCount = reader.readU2();
    for (int i = 0; i < fieldCount; i++) {
      reader.readId();
      reader.readU1();
    }
  }

  private static void skipGcRoot(HprofReader reader, int subTag) {
    switch (subTag) {
      case HeapTag.ROOT_UNKNOWN, HeapTag.ROOT_STICKY_CLASS, HeapTag.ROOT_MONITOR_USED ->
          reader.readId();
      case HeapTag.ROOT_JNI_GLOBAL -> {
        reader.readId();
        reader.readId();
      }
      case HeapTag.ROOT_JNI_LOCAL, HeapTag.ROOT_NATIVE_STACK, HeapTag.ROOT_THREAD_BLOCK -> {
        reader.readId();
        reader.readI4();
      }
      case HeapTag.ROOT_JAVA_FRAME -> {
        reader.readId();
        reader.readI4();
        reader.readI4();
      }
      case HeapTag.ROOT_THREAD_OBJ -> {
        reader.readId();
        reader.readI4();
        reader.readI4();
      }
      default -> {
        // Unknown tag
      }
    }
  }

  private static void writeInboundIndex(
      Path indexDir, int objectCount, Int2IntOpenHashMap inboundCounts) throws IOException {

    Path indexFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);
    Path tempFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME + ".tmp");

    // CRITICAL: Use BufferedOutputStream for 114M writes
    try (DataOutputStream out =
        new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(tempFile), 1024 * 1024))) {

      // Write header
      out.writeInt(IndexFormat.INBOUND_INDEX_MAGIC);
      out.writeInt(IndexFormat.FORMAT_VERSION);
      out.writeLong(objectCount);
      out.writeInt(0); // flags (reserved)

      // Write entries (sequential by objectId32)
      for (int id32 = 0; id32 < objectCount; id32++) {
        out.writeInt(id32); // objectId32
        out.writeInt(inboundCounts.get(id32)); // inboundCount (0 if not present)
      }
    }

    // Atomic rename
    Files.move(tempFile, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  /** Callback interface for progress updates during index building. */
  @FunctionalInterface
  public interface ProgressCallback {
    void onProgress(double progress, String message);
  }
}
