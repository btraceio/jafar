package io.jafar.hdump.index;

/**
 * Binary format constants for heap dump index files.
 *
 * <p>The index-based architecture uses seven main index files:
 *
 * <ul>
 *   <li><strong>objects.idx</strong>: Object metadata (location, size, class, array length)
 *   <li><strong>refs.idx</strong>: Outbound references (variable-length per object)
 *   <li><strong>inbound.idx</strong>: Inbound reference counts (for retained size computation)
 *   <li><strong>retained.idx</strong>: Retained sizes (pre-computed approximate retained sizes)
 *   <li><strong>classes.idx</strong>: Class metadata (name, fields)
 *   <li><strong>classmap.idx</strong>: Class ID mapping (32-bit ID to 64-bit address)
 *   <li><strong>gcroots.idx</strong>: GC root metadata (type, object ID, thread, frame)
 * </ul>
 *
 * <p><strong>Design Philosophy:</strong>
 *
 * <ul>
 *   <li>Sequential writes during index building (optimal for SSD/HDD)
 *   <li>Fixed-size records for objects.idx and inbound.idx (direct offset calculation)
 *   <li>Variable-length records for refs.idx (compact storage)
 *   <li>32-bit object IDs (maps 64-bit addresses to sequential IDs, 50% space savings)
 *   <li>Memory-mapped access for reading (OS page cache optimization)
 * </ul>
 *
 * <p><strong>File Format Versions:</strong>
 *
 * <ul>
 *   <li>Version 1: Initial implementation with basic metadata and references
 * </ul>
 */
public final class IndexFormat {

  private IndexFormat() {}

  // === Version Information ===

  /** Current index format version. Increment when making breaking changes. */
  public static final int FORMAT_VERSION = 1;

  // === Magic Numbers (File Identification) ===

  /** Magic number for objects.idx file (ASCII: "JOBJ") */
  public static final int OBJECTS_INDEX_MAGIC = 0x4A4F424A;

  /** Magic number for refs.idx file (ASCII: "JREF") */
  public static final int REFS_INDEX_MAGIC = 0x4A524546;

  /** Magic number for inbound.idx file (ASCII: "JINB") */
  public static final int INBOUND_INDEX_MAGIC = 0x4A494E42;

  /** Magic number for retained.idx file (ASCII: "JRET") */
  public static final int RETAINED_INDEX_MAGIC = 0x4A524554;

  /** Magic number for classes.idx file (ASCII: "JCLS") */
  public static final int CLASSES_INDEX_MAGIC = 0x4A434C53;

  // === File Header Format ===

  /**
   * Header format (20 bytes):
   *
   * <pre>
   * [magic:4][version:4][entryCount:8][flags:4]
   * </pre>
   */
  public static final int HEADER_SIZE = 20;

  public static final int HEADER_OFFSET_MAGIC = 0;
  public static final int HEADER_OFFSET_VERSION = 4;
  public static final int HEADER_OFFSET_ENTRY_COUNT = 8;
  public static final int HEADER_OFFSET_FLAGS = 16;

  // === objects.idx Format ===

  /**
   * Object entry format (26 bytes fixed):
   *
   * <pre>
   * [objectId32:4][fileOffset:8][dataSize:4][classId:4][arrayLength:4][flags:1][elementType:1]
   * Total: 20-byte header + (26 bytes × object count)
   * Example: 114M objects = 20 + (114,000,000 × 26) = 2.96 GB
   * </pre>
   *
   * <p>Fields:
   *
   * <ul>
   *   <li><strong>objectId32</strong>: 32-bit sequential object ID (0 to N-1)
   *   <li><strong>fileOffset</strong>: Position in heap dump file where object data starts
   *   <li><strong>dataSize</strong>: Size of object data in bytes
   *   <li><strong>classId</strong>: 32-bit class ID (index into classes.idx)
   *   <li><strong>arrayLength</strong>: Array length (-1 if not an array)
   *   <li><strong>flags</strong>: Bitfield (bit 0: isObjectArray, bit 1: isPrimitiveArray)
   *   <li><strong>elementType</strong>: BasicType constant for primitive arrays (BYTE=8, INT=10, etc.), 0 otherwise
   * </ul>
   */
  public static final int OBJECT_ENTRY_SIZE = 26;

  public static final int OBJECT_OFFSET_ID32 = 0;
  public static final int OBJECT_OFFSET_FILE_OFFSET = 4;
  public static final int OBJECT_OFFSET_DATA_SIZE = 12;
  public static final int OBJECT_OFFSET_CLASS_ID = 16;
  public static final int OBJECT_OFFSET_ARRAY_LENGTH = 20;
  public static final int OBJECT_OFFSET_FLAGS = 24;
  public static final int OBJECT_OFFSET_ELEMENT_TYPE = 25;

  // Object flags
  public static final byte FLAG_IS_OBJECT_ARRAY = 0x01;
  public static final byte FLAG_IS_PRIMITIVE_ARRAY = 0x02;

  // === refs.idx Format ===

  /**
   * Reference entry format (variable length):
   *
   * <pre>
   * [objectId32:4][refCount:4][refId32_1:4][refId32_2:4]...[refId32_N:4]
   * </pre>
   *
   * <p>Variable-length encoding saves space for objects with few references. Binary search on
   * objectId32 enables fast lookup.
   *
   * <p>Entry size: 8 + (4 × refCount) bytes
   */
  public static final int REF_ENTRY_HEADER_SIZE = 8;

  public static final int REF_OFFSET_OBJECT_ID32 = 0;
  public static final int REF_OFFSET_REF_COUNT = 4;
  public static final int REF_OFFSET_REFS_START = 8;

  // === inbound.idx Format ===

  /**
   * Inbound count entry format (8 bytes fixed):
   *
   * <pre>
   * [objectId32:4][inboundCount:4]
   * </pre>
   *
   * <p>Sequential by objectId32 for direct offset calculation.
   */
  public static final int INBOUND_ENTRY_SIZE = 8;

  public static final int INBOUND_OFFSET_OBJECT_ID32 = 0;
  public static final int INBOUND_OFFSET_COUNT = 4;

  // === retained.idx Format ===

  /**
   * Retained size entry format (12 bytes fixed):
   *
   * <pre>
   * [objectId32:4][retainedSize:8]
   * </pre>
   *
   * <p>Sequential by objectId32 for direct offset calculation. Stores pre-computed approximate
   * retained sizes to avoid recomputation. Computed once during first query that needs retained
   * sizes, then persisted for instant access.
   *
   * <p>Example: 114M objects = 20 + (114,000,000 × 12) = 1.37 GB
   */
  public static final int RETAINED_ENTRY_SIZE = 12;

  public static final int RETAINED_OFFSET_OBJECT_ID32 = 0;
  public static final int RETAINED_OFFSET_SIZE = 4;

  // === classes.idx Format ===

  /**
   * Class entry format (variable length):
   *
   * <pre>
   * [classId:4][nameLength:2][name:...][fieldCount:2][fields:...]
   * </pre>
   *
   * <p>Field format:
   *
   * <pre>
   * [fieldNameLength:2][fieldName:...][fieldType:1]
   * </pre>
   *
   * <p>Sequential by classId for direct offset calculation. This index is small (~20 MB for 10K
   * classes) and typically loaded fully into memory.
   */
  public static final int CLASS_ENTRY_HEADER_SIZE = 6;

  public static final int CLASS_OFFSET_CLASS_ID = 0;
  public static final int CLASS_OFFSET_NAME_LENGTH = 4;
  public static final int CLASS_OFFSET_NAME_START = 6;

  public static final int FIELD_OFFSET_NAME_LENGTH = 0;
  public static final int FIELD_OFFSET_TYPE = 2; // Relative to field start

  // === Index File Naming ===

  /** Base directory for index files, relative to heap dump file. */
  public static final String INDEX_DIR_SUFFIX = ".idx";

  /** Objects index filename. */
  public static final String OBJECTS_INDEX_NAME = "objects.idx";

  /** References index filename. */
  public static final String REFS_INDEX_NAME = "refs.idx";

  /** Inbound counts index filename. */
  public static final String INBOUND_INDEX_NAME = "inbound.idx";

  /** Retained sizes index filename. */
  public static final String RETAINED_INDEX_NAME = "retained.idx";

  /** Classes index filename. */
  public static final String CLASSES_INDEX_NAME = "classes.idx";

  /** Class mapping index filename. */
  public static final String CLASSMAP_INDEX_NAME = "classmap.idx";

  /** GC roots index filename. */
  public static final String GCROOTS_INDEX_NAME = "gcroots.idx";

  // === classmap.idx Format ===

  /** Magic number for classmap.idx file (ASCII: "JCMP") */
  public static final int CLASSMAP_INDEX_MAGIC = 0x4A434D50;

  /**
   * Class mapping entry format (12 bytes fixed):
   *
   * <pre>
   * [classId32:4][classAddress64:8]
   * </pre>
   *
   * <p>Sequential by classId32 for direct offset calculation. Maps 32-bit sequential class IDs
   * back to original 64-bit class addresses from the heap dump.
   */
  public static final int CLASSMAP_ENTRY_SIZE = 12;

  public static final int CLASSMAP_OFFSET_CLASS_ID32 = 0;
  public static final int CLASSMAP_OFFSET_CLASS_ADDRESS64 = 4;

  // === gcroots.idx Format ===

  /** Magic number for gcroots.idx file (ASCII: "JGCR") */
  public static final int GCROOTS_INDEX_MAGIC = 0x4A474352;

  /**
   * GC root entry format (13 bytes fixed):
   *
   * <pre>
   * [type:1][objectId32:4][threadSerial:4][frameNumber:4]
   * </pre>
   *
   * <p>Fields:
   *
   * <ul>
   *   <li><strong>type</strong>: GcRoot.Type ordinal (0-based enum index)
   *   <li><strong>objectId32</strong>: 32-bit object ID (mapped via addressToId32)
   *   <li><strong>threadSerial</strong>: Thread serial number (-1 if not applicable)
   *   <li><strong>frameNumber</strong>: Stack frame number (-1 if not applicable)
   * </ul>
   *
   * <p>Sequential storage for efficient scan. Small index (~13 bytes per root, typically &lt;10K
   * roots per heap).
   */
  public static final int GCROOT_ENTRY_SIZE = 13;

  public static final int GCROOT_OFFSET_TYPE = 0;
  public static final int GCROOT_OFFSET_OBJECT_ID32 = 1;
  public static final int GCROOT_OFFSET_THREAD_SERIAL = 5;
  public static final int GCROOT_OFFSET_FRAME_NUMBER = 9;

  // === Magic Numbers (continued) ===

  /** Magic number for inbound.idx file (ASCII: "JINB") - defined above but repeated for clarity */
  // public static final int INBOUND_INDEX_MAGIC = 0x4A494E42; // Already defined above

  // === Memory Mapping Configuration ===

  /**
   * Page size for memory-mapped access (4 MB).
   *
   * <p>Larger pages reduce overhead for sequential scans. Tuned for modern SSDs with 4KB physical
   * sectors.
   */
  public static final int MMAP_PAGE_SIZE = 4 * 1024 * 1024;

  /**
   * Maximum number of cached pages per index file.
   *
   * <p>With 4 MB pages, 256 pages = 1 GB max cache per index. Uses SoftReferences so GC can
   * reclaim under memory pressure.
   */
  public static final int MAX_CACHED_PAGES = 256;
}
