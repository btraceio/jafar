package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapDumpParser;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.index.InboundCountReader;
import io.jafar.hdump.index.InboundIndexBuilder;
import io.jafar.hdump.index.IndexFormat;
import io.jafar.hdump.index.IndexWriter;
import io.jafar.hdump.index.ObjectIndexReader;
import io.jafar.hdump.internal.BasicType;
import io.jafar.hdump.internal.HeapTag;
import io.jafar.hdump.internal.HprofReader;
import io.jafar.hdump.internal.HprofReader.RecordHeader;
import io.jafar.hdump.internal.HprofTag;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of HeapDump interface. Parses HPROF files and provides access to heap data. */
public final class HeapDumpImpl implements HeapDump {

  private static final Logger LOG = LoggerFactory.getLogger(HeapDumpImpl.class);

  private final Path path;
  private final HprofReader reader;
  private final ParserOptions options;

  // String table: ID -> String
  private final Long2ObjectMap<String> strings = new Long2ObjectOpenHashMap<>();

  // Class table: ID -> HeapClassImpl
  private final Long2ObjectMap<HeapClassImpl> classesById = new Long2ObjectOpenHashMap<>();
  private final Map<String, HeapClassImpl> classesByName = new HashMap<>();

  // Object cache: ID -> HeapObjectImpl (lazily populated)
  // In-memory mode: unbounded fastutil map (fast, but can OOM on large heaps)
  // Indexed mode: bounded LRU cache (prevents OOM, ~40 bytes per cached object)
  private final Long2ObjectMap<HeapObjectImpl> objectsByIdUnbounded = new Long2ObjectOpenHashMap<>();
  private LruCache<Long, HeapObjectImpl> objectsByIdLru; // null in in-memory mode

  // Index-based parsing fields - INDEXED MODE ONLY
  private ObjectIndexReader objectIndexReader;  // null in in-memory mode
  private InboundCountReader inboundCountReader; // null until first retained-size query
  private io.jafar.hdump.index.RetainedSizeReader retainedSizeReader; // null until retained sizes computed
  private Long2IntOpenHashMap addressToId32;    // 64-bit address -> 32-bit ID mapping
  private it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap classId32ToAddress; // 32-bit class ID -> 64-bit address (INDEXED mode)
  private it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap id32ToAddress; // 32-bit object ID -> 64-bit address (INDEXED mode, for GC roots)
  private LongOpenHashSet classAddresses; // Addresses that are class objects (INDEXED mode)
  private Path indexDir;                        // directory containing index files
  private volatile boolean inboundIndexBuilt = false;

  // GC roots
  private final List<GcRootImpl> gcRoots = new ArrayList<>();

  // Statistics
  private int objectCount = 0;
  private long totalHeapSize = 0;
  private volatile boolean dominatorsComputed = false;
  private volatile boolean fullDominatorTreeComputed = false;

  // Dominator children map: dominator ID -> list of dominated object IDs
  // Built during full dominator tree computation for O(1) lookup
  private Map<Long, List<Long>> dominatorChildrenMap;

  /** Temporary storage for GC root data during Pass 2 (indexed mode). */
  private static class GcRootData {
    final GcRoot.Type type;
    final long objectAddress;
    final int threadSerial;
    final int frameNumber;

    GcRootData(GcRoot.Type type, long objectAddress, int threadSerial, int frameNumber) {
      this.type = type;
      this.objectAddress = objectAddress;
      this.threadSerial = threadSerial;
      this.frameNumber = frameNumber;
    }
  }

  /** Temporary storage for object index entries during Pass 2. */
  private static class ObjectEntry {
    final int objectId32;
    final long fileOffset;
    final int dataSize;
    final int classId;
    final int arrayLength;
    final byte flags;
    final byte elementType;

    ObjectEntry(int objectId32, long fileOffset, int dataSize, int classId,
                int arrayLength, byte flags, byte elementType) {
      this.objectId32 = objectId32;
      this.fileOffset = fileOffset;
      this.dataSize = dataSize;
      this.classId = classId;
      this.arrayLength = arrayLength;
      this.flags = flags;
      this.elementType = elementType;
    }
  }

  private HeapDumpImpl(Path path, HprofReader reader, ParserOptions options) {
    this.path = path;
    this.reader = reader;
    this.options = options;
  }

  /**
   * Parses a heap dump file.
   *
   * @param path path to the HPROF file
   * @param options parser options
   * @param progressCallback optional progress callback
   * @return parsed heap dump
   * @throws IOException if parsing fails
   */
  public static HeapDump parse(
      Path path, ParserOptions options, HeapDumpParser.ProgressCallback progressCallback)
      throws IOException {
    HprofReader reader = new HprofReader(path);
    HeapDumpImpl dump = new HeapDumpImpl(path, reader, options);

    if (options.parsingMode() == HeapDumpParser.ParsingMode.INDEXED) {
      dump.parseTwoPass(progressCallback);
    } else {
      dump.parseRecords(progressCallback);
    }

    if (options.computeDominators()) {
      dump.computeDominators();
    }
    return dump;
  }

  private void parseRecords(HeapDumpParser.ProgressCallback progressCallback) throws IOException {
    reader.reset();
    LOG.debug("Parsing heap dump: {}", path);

    long fileSize = reader.getFileSize();
    long lastProgressReport = 0;
    final long progressInterval = fileSize / 100; // Report every 1%

    while (reader.hasMoreRecords()) {
      RecordHeader header = reader.readRecordHeader();
      if (header == null) break;

      switch (header.tag()) {
        case HprofTag.UTF8 -> parseUtf8(header);
        case HprofTag.LOAD_CLASS -> parseLoadClass(header);
        case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT -> parseHeapDump(header);
        default -> reader.skipRecordBody(header);
      }

      // Report progress periodically
      if (progressCallback != null && fileSize > 0) {
        long currentPos = reader.position();
        if (currentPos - lastProgressReport > progressInterval) {
          double progress = (double) currentPos / fileSize;
          progressCallback.onProgress(progress, "Parsing");
          lastProgressReport = currentPos;
        }
      }
    }

    // Final progress update
    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Complete");
    }

    LOG.debug(
        "Parsed {} classes, {} objects, {} GC roots",
        classesById.size(),
        objectCount,
        gcRoots.size());
  }

  /**
   * Two-pass parsing for index-based mode (large heaps).
   *
   * <p>Pass 1: Collect object addresses and build address-to-ID mapping
   * <p>Pass 2: Build indexes with object metadata
   *
   * <p>Optimization: If valid indexes already exist (including objectmap.idx), skip BOTH passes.
   *
   * <p>Memory usage: ~3.5 GB for 114M objects (vs 25 GB for in-memory)
   * <p>Parse time: ~90 seconds for 114M objects first time, ~2-5 seconds with existing indexes
   */
  private void parseTwoPass(HeapDumpParser.ProgressCallback progressCallback) throws IOException {
    // Create index directory alongside heap dump
    indexDir = path.getParent().resolve(path.getFileName().toString() + ".idx");
    Files.createDirectories(indexDir);

    // Check if valid indexes already exist
    boolean hasValidIndexes = checkValidIndexes();

    // Initialize bounded LRU cache for object instances (prevents OOM on large heaps)
    objectsByIdLru = new LruCache<>(options.objectCacheSize());
    LOG.debug("Initialized object cache with max size: {} (~{}MB)",
        options.objectCacheSize(),
        (options.objectCacheSize() * 40L) / (1024 * 1024));

    if (hasValidIndexes) {
      // Fast path: Load from existing indexes, skip both Pass 1 and Pass 2
      LOG.info("Found valid index files in {}, skipping heap scan", indexDir);

      if (progressCallback != null) {
        progressCallback.onProgress(0.0, "Loading object address mappings");
      }

      // Load address mappings from objectmap.idx (replaces Pass 1)
      loadObjectAddressMappings();

      if (progressCallback != null) {
        progressCallback.onProgress(0.25, "Loading class metadata");
      }

      // Parse class metadata (needed for queries)
      parseAllClasses(progressCallback);

      if (progressCallback != null) {
        progressCallback.onProgress(0.50, "Loading reference indices");
      }

      // Load inbound index if available (avoids rebuild on first retained size query)
      Path inboundIndexPath = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);
      if (Files.exists(inboundIndexPath)) {
        try {
          inboundCountReader = new InboundCountReader(indexDir);
          inboundIndexBuilt = true;
          LOG.debug("Loaded pre-built inbound index from {}", inboundIndexPath);
        } catch (java.io.IOException e) {
          LOG.warn("Failed to load inbound.idx, will rebuild on first query: {}", e.getMessage());
          inboundCountReader = null;
          inboundIndexBuilt = false;
        }
      }

      if (progressCallback != null) {
        progressCallback.onProgress(0.75, "Loading retained sizes");
      }

      // Load retained sizes if available (avoids recomputation on first query)
      Path retainedIndexPath = indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME);
      if (Files.exists(retainedIndexPath)) {
        try {
          retainedSizeReader = new io.jafar.hdump.index.RetainedSizeReader(indexDir);
          dominatorsComputed = true; // Mark as computed to skip recomputation
          LOG.debug("Loaded pre-computed retained sizes from {}", retainedIndexPath);
        } catch (java.io.IOException e) {
          LOG.warn("Failed to load retained.idx, will recompute on first query: {}", e.getMessage());
          retainedSizeReader = null;
          dominatorsComputed = false;
        }
      }

      if (progressCallback != null) {
        progressCallback.onProgress(1.0, "Index loading complete");
      }

      LOG.debug("Loaded indexes: {} address mappings, {} classes",
          id32ToAddress.size(), classesById.size());
    } else {
      // Slow path: Full two-pass parsing
      LOG.debug("Two-pass parsing: building indexes in {}", indexDir);

      // Initialize class address tracking
      classAddresses = new LongOpenHashSet();

      // Pass 1: Collect addresses (28% of parse time - fast scan, no parsing)
      if (progressCallback != null) {
        progressCallback.onProgress(0.0, "Pass 1/2: Collecting addresses");
      }

      LongArrayList objectAddresses = new LongArrayList();
      collectObjectAddresses(objectAddresses, progressCallback);

      // Sort and create address-to-ID mapping
      objectAddresses.sort(null);
      addressToId32 = new Long2IntOpenHashMap(objectAddresses.size());
      addressToId32.defaultReturnValue(-1);
      id32ToAddress = new Int2LongOpenHashMap(objectAddresses.size());
      id32ToAddress.defaultReturnValue(-1L);
      for (int i = 0; i < objectAddresses.size(); i++) {
        long address = objectAddresses.getLong(i);
        addressToId32.put(address, i);
        id32ToAddress.put(i, address);
      }

      // objectCount excludes class objects (classes are accessed via getClasses(), not getObjects())
      objectCount = objectAddresses.size() - classAddresses.size();
      LOG.debug("Pass 1 complete: collected {} object addresses ({} classes, {} objects)",
          objectAddresses.size(), classAddresses.size(), objectCount);

      // Parse class metadata (28-30% - classes needed for Pass 2 reference extraction)
      if (progressCallback != null) {
        progressCallback.onProgress(0.28, "Pass 1/2: Parsing class metadata");
      }
      parseAllClasses(progressCallback);

      if (progressCallback != null) {
        progressCallback.onProgress(0.3, "Pass 1/2: Complete");
      }

      // Pass 2: Build indexes (70% of parse time)
      if (progressCallback != null) {
        progressCallback.onProgress(0.3, "Pass 2/2: Building indexes");
      }

      buildIndexes(progressCallback);

      LOG.debug("Pass 2 complete: indexes built");

      if (progressCallback != null) {
        progressCallback.onProgress(1.0, "Pass 2/2: Complete");
      }
    }

    // Open index reader for lazy loading
    objectIndexReader = new ObjectIndexReader(indexDir);

    // Load class ID mapping for reverse lookup
    loadClassIdMapping();

    // Load GC roots from index
    loadGcRoots();

    LOG.debug(
        "Parsed {} classes, {} objects, {} GC roots (indexed mode)",
        classesById.size(),
        objectCount,
        gcRoots.size());
  }

  /**
   * Check if valid index files already exist.
   * Validates magic numbers and versions for required index files.
   *
   * @return true if all required indexes exist and are valid, false otherwise
   */
  private boolean checkValidIndexes() {
    try {
      // Check if index directory exists
      if (!Files.exists(indexDir) || !Files.isDirectory(indexDir)) {
        return false;
      }

      // Required index files that must exist and be valid
      Path objectsIndex = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
      Path objectmapIndex = indexDir.resolve(IndexFormat.OBJECTMAP_INDEX_NAME);
      Path classmapIndex = indexDir.resolve(IndexFormat.CLASSMAP_INDEX_NAME);
      Path gcrootsIndex = indexDir.resolve(IndexFormat.GCROOTS_INDEX_NAME);

      // Check all required files exist
      if (!Files.exists(objectsIndex) || !Files.exists(objectmapIndex) ||
          !Files.exists(classmapIndex) || !Files.exists(gcrootsIndex)) {
        return false;
      }

      // Validate objects.idx
      if (!validateIndexFile(objectsIndex, IndexFormat.OBJECTS_INDEX_MAGIC)) {
        LOG.debug("Invalid objects.idx, will rebuild indexes");
        return false;
      }

      // Validate objectmap.idx
      if (!validateIndexFile(objectmapIndex, IndexFormat.OBJECTMAP_INDEX_MAGIC)) {
        LOG.debug("Invalid objectmap.idx, will rebuild indexes");
        return false;
      }

      // Validate classmap.idx
      if (!validateIndexFile(classmapIndex, IndexFormat.CLASSMAP_INDEX_MAGIC)) {
        LOG.debug("Invalid classmap.idx, will rebuild indexes");
        return false;
      }

      // Validate gcroots.idx
      if (!validateIndexFile(gcrootsIndex, IndexFormat.GCROOTS_INDEX_MAGIC)) {
        LOG.debug("Invalid gcroots.idx, will rebuild indexes");
        return false;
      }

      return true;
    } catch (Exception e) {
      LOG.debug("Error checking index validity: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Validate an index file's header (magic number and version).
   *
   * @param indexFile path to index file
   * @param expectedMagic expected magic number
   * @return true if file is valid, false otherwise
   */
  private boolean validateIndexFile(Path indexFile, int expectedMagic) {
    try (var channel = java.nio.channels.FileChannel.open(indexFile, java.nio.file.StandardOpenOption.READ)) {
      if (channel.size() < IndexFormat.HEADER_SIZE) {
        return false; // File too small to contain header
      }

      var buffer = channel.map(
          java.nio.channels.FileChannel.MapMode.READ_ONLY,
          0,
          IndexFormat.HEADER_SIZE);

      // Check magic number
      int magic = buffer.getInt();
      if (magic != expectedMagic) {
        return false;
      }

      // Check version
      int version = buffer.getInt();
      if (version != IndexFormat.FORMAT_VERSION) {
        LOG.debug("Index file {} has version {}, expected {}",
            indexFile.getFileName(), version, IndexFormat.FORMAT_VERSION);
        return false;
      }

      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Load object address mappings from objectmap.idx.
   * This allows skipping Pass 1 (address collection) when reopening a heap dump.
   *
   * @throws IOException if file cannot be read
   */
  private void loadObjectAddressMappings() throws IOException {
    Path objectmapFile = indexDir.resolve(IndexFormat.OBJECTMAP_INDEX_NAME);

    try (var channel = java.nio.channels.FileChannel.open(objectmapFile, java.nio.file.StandardOpenOption.READ)) {
      var buffer = channel.map(
          java.nio.channels.FileChannel.MapMode.READ_ONLY,
          0,
          channel.size());

      // Read header
      int magic = buffer.getInt();
      if (magic != IndexFormat.OBJECTMAP_INDEX_MAGIC) {
        throw new IOException("Invalid objectmap.idx magic number: " + Integer.toHexString(magic));
      }

      int version = buffer.getInt();
      if (version != IndexFormat.FORMAT_VERSION) {
        throw new IOException("Unsupported objectmap.idx version: " + version);
      }

      long entryCount = buffer.getLong();
      buffer.getInt(); // skip flags

      // Initialize address mapping with correct size
      addressToId32 = new Long2IntOpenHashMap((int) entryCount);
      addressToId32.defaultReturnValue(-1);
      id32ToAddress = new Int2LongOpenHashMap((int) entryCount);
      id32ToAddress.defaultReturnValue(-1L);

      // Load mappings
      for (int i = 0; i < entryCount; i++) {
        int objectId32 = buffer.getInt();
        long objectAddress64 = buffer.getLong();

        addressToId32.put(objectAddress64, objectId32);
        id32ToAddress.put(objectId32, objectAddress64);
      }

      // Count classes vs objects (classes tracked separately)
      classAddresses = new LongOpenHashSet();
      // Class addresses will be loaded from classmap.idx
      objectCount = (int) entryCount; // Will be adjusted after loading classmap

      LOG.debug("Loaded {} address mappings from objectmap.idx", entryCount);
    }
  }

  /** Pass 1: Collect all object addresses in single scan. */
  private void collectObjectAddresses(LongArrayList objectAddresses,
                                     HeapDumpParser.ProgressCallback progressCallback)
      throws IOException {
    reader.reset();

    long fileSize = Files.size(path);
    long lastProgressReport = 0;
    long progressInterval = fileSize / 100; // Report every 1% of file

    while (reader.hasMoreRecords()) {
      RecordHeader header = reader.readRecordHeader();
      if (header == null) break;

      switch (header.tag()) {
        case HprofTag.UTF8 -> parseUtf8(header);
        case HprofTag.LOAD_CLASS -> parseLoadClass(header);
        case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT ->
            collectAddressesFromHeapDump(header, objectAddresses);
        default -> reader.skipRecordBody(header);
      }

      // Report progress periodically (Pass 1 is 0-28% of total)
      if (progressCallback != null && fileSize > 0) {
        long currentPos = reader.position();
        if (currentPos - lastProgressReport > progressInterval) {
          double fileProgress = (double) currentPos / fileSize;
          double totalProgress = fileProgress * 0.28; // Pass 1 is 28% of total
          progressCallback.onProgress(totalProgress, "Pass 1/2: Collecting addresses");
          lastProgressReport = currentPos;
        }
      }
    }
  }

  private void collectAddressesFromHeapDump(
      RecordHeader header, LongArrayList objectAddresses) throws IOException {
    long endPos = header.bodyPosition() + header.length();

    while (reader.position() < endPos) {
      int subTag = reader.readU1();

      switch (subTag) {
        case HeapTag.INSTANCE_DUMP -> {
          long objId = reader.readId();
          objectAddresses.add(objId);
          reader.readI4(); // stack trace
          reader.readId(); // class ID
          int dataSize = reader.readI4();
          reader.skip(dataSize);
        }
        case HeapTag.OBJ_ARRAY_DUMP -> {
          long objId = reader.readId();
          objectAddresses.add(objId);
          reader.readI4(); // stack trace
          int length = reader.readI4();
          reader.readId(); // array class ID
          reader.skip(length * reader.getIdSize());
        }
        case HeapTag.PRIM_ARRAY_DUMP -> {
          long objId = reader.readId();
          objectAddresses.add(objId);
          reader.readI4(); // stack trace
          int length = reader.readI4();
          int elemType = reader.readU1();
          int elemSize = BasicType.sizeOf(elemType, reader.getIdSize());
          reader.skip(length * elemSize);
        }
        case HeapTag.CLASS_DUMP -> {
          // Just collect class address - defer parsing to separate phase
          long classId = reader.readId();
          objectAddresses.add(classId);
          classAddresses.add(classId); // Mark as class for filtering

          // Skip the rest of CLASS_DUMP for now (will parse in separate phase)
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
        default -> skipGcRoot(subTag);
      }

      // Verify we haven't read past segment end
      if (reader.position() > endPos) {
        throw new IOException("Read past HEAP_DUMP segment boundary! " +
                              "Position: " + reader.position() + ", endPos: " + endPos +
                              ", last subTag: 0x" + Integer.toHexString(subTag));
      }
    }
  }

  /**
   * Parse all CLASS_DUMP records to populate classesById.
   * This is done after Pass 1 address collection but before Pass 2 index building.
   */
  private void parseAllClasses(HeapDumpParser.ProgressCallback progressCallback) throws IOException {
    reader.reset();

    while (reader.hasMoreRecords()) {
      RecordHeader header = reader.readRecordHeader();
      if (header == null) break;

      if (header.tag() == HprofTag.HEAP_DUMP || header.tag() == HprofTag.HEAP_DUMP_SEGMENT) {
        parseClassesFromHeapDump(header);
      } else {
        reader.skipRecordBody(header);
      }
    }

    LOG.debug("Parsed {} classes", classesById.size());
  }

  /**
   * Parse only CLASS_DUMP records from a heap dump segment.
   */
  private void parseClassesFromHeapDump(RecordHeader header) throws IOException {
    long endPos = header.bodyPosition() + header.length();

    while (reader.position() < endPos) {
      int subTag = reader.readU1();

      if (subTag == HeapTag.CLASS_DUMP) {
        parseClassDump();
      } else {
        // Skip all other record types (we only want CLASS_DUMP)
        skipNonClassRecord(subTag);
      }
    }
  }

  /**
   * Skip a non-CLASS_DUMP heap record during class parsing phase.
   */
  private void skipNonClassRecord(int subTag) throws IOException {
    switch (subTag) {
      case HeapTag.INSTANCE_DUMP -> {
        reader.readId(); // object ID
        reader.readI4(); // stack trace
        reader.readId(); // class ID
        int dataSize = reader.readI4();
        reader.skip(dataSize);
      }
      case HeapTag.OBJ_ARRAY_DUMP -> {
        reader.readId(); // object ID
        reader.readI4(); // stack trace
        int length = reader.readI4();
        reader.readId(); // array class ID
        reader.skip(length * reader.getIdSize());
      }
      case HeapTag.PRIM_ARRAY_DUMP -> {
        reader.readId(); // object ID
        reader.readI4(); // stack trace
        int length = reader.readI4();
        int elemType = reader.readU1();
        int elemSize = BasicType.sizeOf(elemType, reader.getIdSize());
        reader.skip(length * elemSize);
      }
      default -> skipGcRoot(subTag);
    }
  }

  /** Pass 2: Build indexes using address-to-ID mapping. */
  private void buildIndexes(HeapDumpParser.ProgressCallback progressCallback) throws IOException {
    try (IndexWriter writer = new IndexWriter(indexDir)) {
      // Map for class addresses -> 32-bit class IDs
      Long2IntOpenHashMap classIdMap = new Long2IntOpenHashMap();
      classIdMap.defaultReturnValue(-1);

      // Collect object entries and GC roots during Pass 2
      List<ObjectEntry> objectEntries = new ArrayList<>();
      List<GcRootData> gcRootDataList = new ArrayList<>();

      reader.reset();

      while (reader.hasMoreRecords()) {
        RecordHeader header = reader.readRecordHeader();
        if (header == null) break;

        switch (header.tag()) {
          case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT ->
              buildIndexFromHeapDump(header, objectEntries, classIdMap, gcRootDataList);
          default -> reader.skipRecordBody(header);
        }
      }

      // Report Pass 2 sub-phase progress
      if (progressCallback != null) {
        progressCallback.onProgress(0.55, "Pass 2/2: Sorting entries");
      }

      // Sort object entries by ID (required for ObjectIndexReader's offset calculation)
      objectEntries.sort(java.util.Comparator.comparingInt(e -> e.objectId32));

      if (progressCallback != null) {
        progressCallback.onProgress(0.60, "Pass 2/2: Writing object index");
      }

      // Write sorted entries to objects.idx
      // Note: objectEntries includes both regular objects AND class objects (for GC root support)
      writer.beginObjectsIndex(objectEntries.size());
      int progressInterval = Math.max(1, objectEntries.size() / 100); // Report every 1%
      for (int i = 0; i < objectEntries.size(); i++) {
        ObjectEntry entry = objectEntries.get(i);
        writer.writeObjectEntry(entry.objectId32, entry.fileOffset, entry.dataSize,
            entry.classId, entry.arrayLength, entry.flags, entry.elementType);

        // Report progress periodically (60-85% of total)
        if (progressCallback != null && i % progressInterval == 0) {
          double entryProgress = (double) i / objectEntries.size();
          progressCallback.onProgress(0.60 + entryProgress * 0.25, "Pass 2/2: Writing object index");
        }
      }
      writer.finishObjectsIndex();

      if (progressCallback != null) {
        progressCallback.onProgress(0.85, "Pass 2/2: Writing class map");
      }

      // Write class ID mapping index
      writer.beginClassMapIndex(classIdMap.size());
      for (var entry : classIdMap.long2IntEntrySet()) {
        int classId32 = entry.getIntValue();
        long classAddress64 = entry.getLongKey();
        writer.writeClassMapEntry(classId32, classAddress64);
      }
      writer.finishClassMapIndex();

      if (progressCallback != null) {
        progressCallback.onProgress(0.87, "Pass 2/2: Writing object address map");
      }

      // Write object address mapping index (enables skipping Pass 1 on reopening)
      writer.beginObjectMapIndex(id32ToAddress.size());
      for (var entry : id32ToAddress.int2LongEntrySet()) {
        int objectId32 = entry.getIntKey();
        long objectAddress64 = entry.getLongValue();
        writer.writeObjectMapEntry(objectId32, objectAddress64);
      }
      writer.finishObjectMapIndex();

      if (progressCallback != null) {
        progressCallback.onProgress(0.90, "Pass 2/2: Writing GC roots");
      }

      // Write GC roots index - only count valid entries
      int validGcRootCount = 0;
      for (GcRootData gcRoot : gcRootDataList) {
        int objectId32 = addressToId32.get(gcRoot.objectAddress);
        if (objectId32 != -1) {
          validGcRootCount++;
        }
      }

      writer.beginGcRootsIndex(validGcRootCount);
      for (GcRootData gcRoot : gcRootDataList) {
        int objectId32 = addressToId32.get(gcRoot.objectAddress);
        if (objectId32 != -1) {
          writer.writeGcRootEntry(
              (byte) gcRoot.type.ordinal(),
              objectId32,
              gcRoot.threadSerial,
              gcRoot.frameNumber);
        }
      }
      writer.finishGcRootsIndex();

      LOG.debug("Persisted {} GC roots to index ({}  total collected)", validGcRootCount, gcRootDataList.size());
    }
  }

  /**
   * Loads class ID mapping from classmap.idx to enable reverse lookup.
   * Creates mapping from 32-bit class IDs to 64-bit class addresses.
   */
  private void loadClassIdMapping() throws IOException {
    Path classmapFile = indexDir.resolve(IndexFormat.CLASSMAP_INDEX_NAME);
    if (!Files.exists(classmapFile)) {
      throw new IOException("Class mapping index not found: " + classmapFile);
    }

    classId32ToAddress = new Int2LongOpenHashMap();
    classId32ToAddress.defaultReturnValue(-1L);

    // Initialize classAddresses if not already (happens when loading from existing indexes)
    if (classAddresses == null) {
      classAddresses = new LongOpenHashSet();
    }

    try (var channel = java.nio.channels.FileChannel.open(classmapFile, java.nio.file.StandardOpenOption.READ)) {
      var buffer = channel.map(
          java.nio.channels.FileChannel.MapMode.READ_ONLY,
          0,
          channel.size());

      // Read header
      int magic = buffer.getInt();
      if (magic != IndexFormat.CLASSMAP_INDEX_MAGIC) {
        throw new IOException("Invalid classmap.idx magic number: " + Integer.toHexString(magic));
      }

      int version = buffer.getInt();
      if (version != IndexFormat.FORMAT_VERSION) {
        throw new IOException("Unsupported classmap.idx version: " + version);
      }

      long entryCount = buffer.getLong();
      buffer.getInt(); // skip flags

      // Read class mappings
      for (int i = 0; i < entryCount; i++) {
        int classId32 = buffer.getInt();
        long classAddress64 = buffer.getLong();
        classId32ToAddress.put(classId32, classAddress64);
        classAddresses.add(classAddress64);
      }

      // Adjust objectCount: total addresses - class addresses
      // (This is needed when loading from existing indexes where objectCount wasn't set yet)
      if (id32ToAddress != null && objectCount == 0) {
        objectCount = id32ToAddress.size() - classAddresses.size();
      }

      LOG.debug("Loaded {} class ID mappings", entryCount);
    }
  }

  /**
   * Loads GC roots from gcroots.idx.
   * Creates GcRootImpl objects from the persisted GC root data.
   */
  private void loadGcRoots() throws IOException {
    Path gcrootsFile = indexDir.resolve(IndexFormat.GCROOTS_INDEX_NAME);
    if (!Files.exists(gcrootsFile)) {
      LOG.debug("No GC roots index found, skipping");
      return;
    }

    try (var channel = java.nio.channels.FileChannel.open(gcrootsFile, java.nio.file.StandardOpenOption.READ)) {
      var buffer = channel.map(
          java.nio.channels.FileChannel.MapMode.READ_ONLY,
          0,
          channel.size());

      // Read header
      int magic = buffer.getInt();
      if (magic != IndexFormat.GCROOTS_INDEX_MAGIC) {
        throw new IOException("Invalid gcroots.idx magic number: " + Integer.toHexString(magic));
      }

      int version = buffer.getInt();
      if (version != IndexFormat.FORMAT_VERSION) {
        throw new IOException("Unsupported gcroots.idx version: " + version);
      }

      long entryCount = buffer.getLong();
      buffer.getInt(); // skip flags

      // Read GC roots
      for (int i = 0; i < entryCount; i++) {
        byte typeOrdinal = buffer.get();
        int objectId32 = buffer.getInt();
        int threadSerial = buffer.getInt();
        int frameNumber = buffer.getInt();

        // Convert 32-bit ID back to 64-bit address using reverse mapping
        long objectAddress = id32ToAddress.get(objectId32);

        if (objectAddress != -1) {
          GcRoot.Type type = GcRoot.Type.values()[typeOrdinal];
          gcRoots.add(new GcRootImpl(type, objectAddress, threadSerial, frameNumber, this));
        }
      }

      LOG.debug("Loaded {} GC roots from index", entryCount);
    }
  }

  private void buildIndexFromHeapDump(
      RecordHeader header, List<ObjectEntry> objectEntries, Long2IntOpenHashMap classIdMap, List<GcRootData> gcRootDataList)
      throws IOException {
    long endPos = header.bodyPosition() + header.length();

    while (reader.position() < endPos) {
      int subTag = reader.readU1();

      switch (subTag) {
        case HeapTag.INSTANCE_DUMP -> {
          long objAddress = reader.readId();
          reader.readI4(); // stack trace
          long classAddress = reader.readId();
          int dataSize = reader.readI4();
          long fileOffset = reader.position();
          reader.skip(dataSize);

          int objectId32 = addressToId32.get(objAddress);
          int classId = getOrCreateClassId(classAddress, classIdMap);

          objectEntries.add(new ObjectEntry(objectId32, fileOffset, dataSize, classId, -1, (byte) 0, (byte) 0));
        }
        case HeapTag.OBJ_ARRAY_DUMP -> {
          long objAddress = reader.readId();
          reader.readI4(); // stack trace
          int length = reader.readI4();
          long arrayClassAddress = reader.readId();
          long fileOffset = reader.position();
          int dataSize = length * reader.getIdSize();
          reader.skip(dataSize);

          int objectId32 = addressToId32.get(objAddress);
          int classId = getOrCreateClassId(arrayClassAddress, classIdMap);

          objectEntries.add(new ObjectEntry(objectId32, fileOffset, dataSize, classId, length,
              IndexFormat.FLAG_IS_OBJECT_ARRAY, (byte) 0));
        }
        case HeapTag.PRIM_ARRAY_DUMP -> {
          long objAddress = reader.readId();
          reader.readI4(); // stack trace
          int length = reader.readI4();
          int elemType = reader.readU1();
          int elemSize = BasicType.sizeOf(elemType, reader.getIdSize());
          long fileOffset = reader.position();
          int dataSize = length * elemSize;
          reader.skip(dataSize);

          int objectId32 = addressToId32.get(objAddress);

          objectEntries.add(new ObjectEntry(objectId32, fileOffset, dataSize, -1, length,
              IndexFormat.FLAG_IS_PRIMITIVE_ARRAY, (byte) elemType));
        }
        case HeapTag.CLASS_DUMP -> {
          // Collect minimal entry for class object (for GC root support)
          long classAddress = reader.readId();
          int objectId32 = addressToId32.get(classAddress);
          int classId = getOrCreateClassId(classAddress, classIdMap);

          // Skip the rest of CLASS_DUMP (we already parsed it in in-memory pass)
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

          // Add minimal entry (fileOffset=0, dataSize=0 for classes)
          objectEntries.add(new ObjectEntry(objectId32, 0, 0, classId, -1, (byte) 0, (byte) 0));
        }
        default -> collectGcRoot(subTag, gcRootDataList);
      }
    }
  }

  private int getOrCreateClassId(long classAddress, Long2IntOpenHashMap classIdMap) {
    int classId = classIdMap.get(classAddress);
    if (classId == -1) {
      classId = classIdMap.size();
      classIdMap.put(classAddress, classId);
    }
    return classId;
  }

  private void skipClassDumpInPass2() {
    // Skip entire class dump record during index building
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

  /** Skip GC root during Pass 1 (only collecting object addresses). */
  private void skipGcRoot(int subTag) throws IOException {
    switch (subTag) {
      case HeapTag.ROOT_UNKNOWN, HeapTag.ROOT_STICKY_CLASS, HeapTag.ROOT_MONITOR_USED -> reader.readId();
      case HeapTag.ROOT_JNI_GLOBAL -> {
        reader.readId();
        reader.readId();
      }
      case HeapTag.ROOT_JNI_LOCAL, HeapTag.ROOT_JAVA_FRAME, HeapTag.ROOT_JNI_MONITOR -> {
        reader.readId();
        reader.readI4();
        reader.readI4();
      }
      case HeapTag.ROOT_NATIVE_STACK, HeapTag.ROOT_THREAD_BLOCK, HeapTag.ROOT_DEBUGGER -> {
        reader.readId();
        reader.readI4();
      }
      case HeapTag.ROOT_THREAD_OBJ -> {
        reader.readId();
        reader.readI4();
        reader.readI4();
      }
      case HeapTag.ROOT_INTERNED_STRING,
           HeapTag.ROOT_FINALIZING,
           HeapTag.ROOT_VM_INTERNAL,
           HeapTag.ROOT_REFERENCE_CLEANUP -> reader.readId();
      default -> {
        // Unknown GC root type
      }
    }
  }

  /** Collect GC root during Pass 2 (building indexes). */
  private void collectGcRoot(int subTag, List<GcRootData> gcRootDataList) throws IOException {
    switch (subTag) {
      // Standard GC roots (HPROF 1.0.2)
      case HeapTag.ROOT_UNKNOWN -> {
        long objId = reader.readId();
        gcRootDataList.add(new GcRootData(GcRoot.Type.UNKNOWN, objId, -1, -1));
      }
      case HeapTag.ROOT_STICKY_CLASS -> {
        long objId = reader.readId();
        gcRootDataList.add(new GcRootData(GcRoot.Type.STICKY_CLASS, objId, -1, -1));
      }
      case HeapTag.ROOT_MONITOR_USED -> {
        long objId = reader.readId();
        gcRootDataList.add(new GcRootData(GcRoot.Type.MONITOR_USED, objId, -1, -1));
      }
      case HeapTag.ROOT_JNI_GLOBAL -> {
        long objId = reader.readId();
        reader.readId(); // JNI global ref ID (not used)
        gcRootDataList.add(new GcRootData(GcRoot.Type.JNI_GLOBAL, objId, -1, -1));
      }
      case HeapTag.ROOT_JNI_LOCAL -> {
        long objId = reader.readId();
        int threadSerial = reader.readI4();
        int frameNum = reader.readI4();
        gcRootDataList.add(new GcRootData(GcRoot.Type.JNI_LOCAL, objId, threadSerial, frameNum));
      }
      case HeapTag.ROOT_NATIVE_STACK -> {
        long objId = reader.readId();
        int threadSerial = reader.readI4();
        gcRootDataList.add(new GcRootData(GcRoot.Type.NATIVE_STACK, objId, threadSerial, -1));
      }
      case HeapTag.ROOT_THREAD_BLOCK -> {
        long objId = reader.readId();
        int threadSerial = reader.readI4();
        gcRootDataList.add(new GcRootData(GcRoot.Type.THREAD_BLOCK, objId, threadSerial, -1));
      }
      case HeapTag.ROOT_JAVA_FRAME -> {
        long objId = reader.readId();
        int threadSerial = reader.readI4();
        int frameNum = reader.readI4();
        gcRootDataList.add(new GcRootData(GcRoot.Type.JAVA_FRAME, objId, threadSerial, frameNum));
      }
      case HeapTag.ROOT_THREAD_OBJ -> {
        long objId = reader.readId();
        int threadSerial = reader.readI4();
        reader.readI4(); // stack trace serial (not used)
        gcRootDataList.add(new GcRootData(GcRoot.Type.THREAD_OBJ, objId, threadSerial, -1));
      }
      // Extended GC roots (HPROF 1.0.3 - Android/modern JDK) - skip for now
      case HeapTag.ROOT_INTERNED_STRING,
           HeapTag.ROOT_FINALIZING,
           HeapTag.ROOT_VM_INTERNAL,
           HeapTag.ROOT_REFERENCE_CLEANUP -> reader.readId();
      case HeapTag.ROOT_DEBUGGER -> {
        reader.readId();
        reader.readI4();
      }
      case HeapTag.ROOT_JNI_MONITOR -> {
        reader.readId();
        reader.readI4();
        reader.readI4();
      }
      case HeapTag.HEAP_DUMP_INFO -> {
        reader.readI4();
        reader.readId();
      }
      case HeapTag.UNREACHABLE -> reader.readId();
      default -> {
        // Unknown tag - can't continue safely!
        LOG.error("Unknown heap sub-tag 0x{} at position {}. " +
                 "Heap dump may be corrupted or use unsupported HPROF format. " +
                 "Stopping parse to avoid data corruption.",
                 Integer.toHexString(subTag), reader.position());
        throw new IOException("Unsupported heap sub-tag: 0x" +
                              Integer.toHexString(subTag) + " at position " +
                              reader.position());
      }
    }
  }

  private void parseUtf8(RecordHeader header) {
    long id = reader.readId();
    int strLen = header.length() - reader.getIdSize();
    byte[] bytes = new byte[strLen];
    reader.readBytes(bytes);
    strings.put(id, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
  }

  private void parseLoadClass(RecordHeader header) {
    int classSerial = reader.readI4();
    long classId = reader.readId();
    int stackTraceSerial = reader.readI4();
    long classNameId = reader.readId();

    String className = strings.get(classNameId);
    // Keep internal format as-is (e.g., "java/lang/Object" not "java.lang.Object")
    // This matches HPROF specification format

    HeapClassImpl cls = new HeapClassImpl(classId, className, this);
    classesById.put(classId, cls);
    if (className != null) {
      classesByName.put(className, cls);
    }
  }

  private void parseHeapDump(RecordHeader header) {
    long endPos = header.bodyPosition() + header.length();

    while (reader.position() < endPos) {
      int subTag = reader.readU1();

      switch (subTag) {
        case HeapTag.ROOT_UNKNOWN -> parseGcRoot(GcRoot.Type.UNKNOWN);
        case HeapTag.ROOT_JNI_GLOBAL -> parseGcRootJniGlobal();
        case HeapTag.ROOT_JNI_LOCAL -> parseGcRootJniLocal();
        case HeapTag.ROOT_JAVA_FRAME -> parseGcRootJavaFrame();
        case HeapTag.ROOT_NATIVE_STACK -> parseGcRootNativeStack();
        case HeapTag.ROOT_STICKY_CLASS -> parseGcRoot(GcRoot.Type.STICKY_CLASS);
        case HeapTag.ROOT_THREAD_BLOCK -> parseGcRootThreadBlock();
        case HeapTag.ROOT_MONITOR_USED -> parseGcRoot(GcRoot.Type.MONITOR_USED);
        case HeapTag.ROOT_THREAD_OBJ -> parseGcRootThreadObj();
        case HeapTag.CLASS_DUMP -> parseClassDump();
        case HeapTag.INSTANCE_DUMP -> parseInstanceDump();
        case HeapTag.OBJ_ARRAY_DUMP -> parseObjArrayDump();
        case HeapTag.PRIM_ARRAY_DUMP -> parsePrimArrayDump();
        default -> {
          LOG.warn("Unknown heap dump sub-tag: 0x{}", Integer.toHexString(subTag));
          return; // Can't continue without knowing record size
        }
      }
    }
  }

  private void parseGcRoot(GcRoot.Type type) {
    long objId = reader.readId();
    gcRoots.add(new GcRootImpl(type, objId, -1, -1, this));
  }

  private void parseGcRootJniGlobal() {
    long objId = reader.readId();
    reader.readId(); // JNI global ref ID (ignored)
    gcRoots.add(new GcRootImpl(GcRoot.Type.JNI_GLOBAL, objId, -1, -1, this));
  }

  private void parseGcRootJniLocal() {
    long objId = reader.readId();
    int threadSerial = reader.readI4();
    int frameNum = reader.readI4();
    gcRoots.add(new GcRootImpl(GcRoot.Type.JNI_LOCAL, objId, threadSerial, frameNum, this));
  }

  private void parseGcRootJavaFrame() {
    long objId = reader.readId();
    int threadSerial = reader.readI4();
    int frameNum = reader.readI4();
    gcRoots.add(new GcRootImpl(GcRoot.Type.JAVA_FRAME, objId, threadSerial, frameNum, this));
  }

  private void parseGcRootNativeStack() {
    long objId = reader.readId();
    int threadSerial = reader.readI4();
    gcRoots.add(new GcRootImpl(GcRoot.Type.NATIVE_STACK, objId, threadSerial, -1, this));
  }

  private void parseGcRootThreadBlock() {
    long objId = reader.readId();
    int threadSerial = reader.readI4();
    gcRoots.add(new GcRootImpl(GcRoot.Type.THREAD_BLOCK, objId, threadSerial, -1, this));
  }

  private void parseGcRootThreadObj() {
    long objId = reader.readId();
    int threadSerial = reader.readI4();
    int stackTraceSerial = reader.readI4();
    gcRoots.add(new GcRootImpl(GcRoot.Type.THREAD_OBJ, objId, threadSerial, -1, this));
  }

  private void parseClassDump() {
    long classId = reader.readId();
    int stackTraceSerial = reader.readI4();
    long superClassId = reader.readId();
    long classLoaderId = reader.readId();
    long signersId = reader.readId();
    long protDomainId = reader.readId();
    reader.readId(); // reserved
    reader.readId(); // reserved
    int instanceSize = reader.readI4();

    HeapClassImpl cls = classesById.get(classId);
    if (cls == null) {
      // Class doesn't have a LOAD_CLASS record - create it with synthetic name
      String className = "Class@0x" + Long.toHexString(classId);
      cls = new HeapClassImpl(classId, className, this);
      classesById.put(classId, cls);
      classesByName.put(className, cls);
    }
    cls.setSuperClassId(superClassId);
    cls.setClassLoaderId(classLoaderId);
    cls.setInstanceSize(instanceSize);

    // Constant pool
    int cpSize = reader.readU2();
    for (int i = 0; i < cpSize; i++) {
      int cpIdx = reader.readU2();
      int type = reader.readU1();
      reader.readValue(type); // Skip value
    }

    // Static fields
    int staticCount = reader.readU2();
    List<HeapFieldImpl> staticFields = new ArrayList<>(staticCount);
    for (int i = 0; i < staticCount; i++) {
      long nameId = reader.readId();
      int type = reader.readU1();
      Object value = reader.readValue(type);
      String name = strings.get(nameId);
      staticFields.add(new HeapFieldImpl(name, type, true, cls, value));
    }
    cls.setStaticFields(staticFields);

    // Instance fields
    int fieldCount = reader.readU2();
    List<HeapFieldImpl> instanceFields = new ArrayList<>(fieldCount);
    for (int i = 0; i < fieldCount; i++) {
      long nameId = reader.readId();
      int type = reader.readU1();
      String name = strings.get(nameId);
      instanceFields.add(new HeapFieldImpl(name, type, false, cls, null));
    }
    cls.setInstanceFields(instanceFields);
  }

  private void parseInstanceDump() {
    long objId = reader.readId();
    int stackTraceSerial = reader.readI4();
    long classId = reader.readId();
    int dataSize = reader.readI4();

    // Store position for lazy field reading
    long dataPos = reader.position();
    reader.skip(dataSize);

    HeapClassImpl cls = classesById.get(classId);
    HeapObjectImpl obj = new HeapObjectImpl(objId, cls, dataPos, dataSize, this);
    cacheObject(objId, obj);
    objectCount++;

    int shallowSize = reader.getIdSize() * 2 + 8 + dataSize; // Approximate
    totalHeapSize += shallowSize;
    obj.setShallowSize(shallowSize);

    // Eagerly extract references for small objects (adaptive optimization)
    if (dataSize < 256) {
      obj.extractOutboundReferences();
    }

    if (cls != null) {
      cls.incrementInstanceCount();
    }
  }

  private void parseObjArrayDump() {
    long objId = reader.readId();
    int stackTraceSerial = reader.readI4();
    int length = reader.readI4();
    long arrayClassId = reader.readId();

    long dataPos = reader.position();
    int dataSize = length * reader.getIdSize();
    reader.skip(dataSize);

    HeapClassImpl cls = classesById.get(arrayClassId);
    HeapObjectImpl obj = new HeapObjectImpl(objId, cls, dataPos, dataSize, this);
    obj.setArrayLength(length);
    obj.setObjectArray(true);
    cacheObject(objId, obj);
    objectCount++;

    int shallowSize = reader.getIdSize() * 2 + 12 + dataSize;
    totalHeapSize += shallowSize;
    obj.setShallowSize(shallowSize);

    // Eagerly extract array references (cheap - contiguous block)
    obj.extractOutboundReferences();

    if (cls != null) {
      cls.incrementInstanceCount();
    }
  }

  private void parsePrimArrayDump() {
    long objId = reader.readId();
    int stackTraceSerial = reader.readI4();
    int length = reader.readI4();
    int elemType = reader.readU1();

    int elemSize = BasicType.sizeOf(elemType, reader.getIdSize());
    long dataPos = reader.position();
    int dataSize = length * elemSize;
    reader.skip(dataSize);

    // Create synthetic class name for primitive arrays
    String arrayClassName =
        switch (elemType) {
          case BasicType.BOOLEAN -> "[Z";
          case BasicType.CHAR -> "[C";
          case BasicType.FLOAT -> "[F";
          case BasicType.DOUBLE -> "[D";
          case BasicType.BYTE -> "[B";
          case BasicType.SHORT -> "[S";
          case BasicType.INT -> "[I";
          case BasicType.LONG -> "[J";
          default -> "[?";
        };

    HeapClassImpl cls = classesByName.get(arrayClassName);
    if (cls == null) {
      // Create synthetic class for primitive array
      cls = new HeapClassImpl(0, arrayClassName, this);
      cls.setPrimitiveArrayType(elemType);
      classesByName.put(arrayClassName, cls);
    }

    HeapObjectImpl obj = new HeapObjectImpl(objId, cls, dataPos, dataSize, this);
    obj.setArrayLength(length);
    obj.setPrimitiveArrayType(elemType);
    cacheObject(objId, obj);
    objectCount++;

    int shallowSize = reader.getIdSize() + 12 + dataSize;
    totalHeapSize += shallowSize;
    obj.setShallowSize(shallowSize);

    cls.incrementInstanceCount();
  }

  // === Package-private accessors for implementation classes ===

  HprofReader getReader() {
    return reader;
  }

  String getString(long id) {
    return strings.get(id);
  }

  HeapClassImpl getClassByIdInternal(long id) {
    return classesById.get(id);
  }

  HeapObjectImpl getObjectByIdInternal(long id) {
    // In-memory mode: lookup in map
    if (objectIndexReader == null) {
      return getCachedObject(id);
    }

    // Indexed mode: lazy load from index
    int id32 = addressToId32.get(id);
    if (id32 == -1) {
      return null; // Object not found
    }

    // Check if already loaded in cache
    HeapObjectImpl cached = getCachedObject(id);
    if (cached != null) {
      return cached;
    }

    // Load from index
    try {
      ObjectIndexReader.ObjectMetadata meta = objectIndexReader.readObject(id32);

      HeapClassImpl cls;

      // Special handling for primitive arrays (classId == -1)
      if (meta.classId == -1 && meta.isPrimitiveArray()) {
        // Create synthetic class name for primitive array
        String arrayClassName =
            switch (meta.elementType) {
              case BasicType.BOOLEAN -> "[Z";
              case BasicType.CHAR -> "[C";
              case BasicType.FLOAT -> "[F";
              case BasicType.DOUBLE -> "[D";
              case BasicType.BYTE -> "[B";
              case BasicType.SHORT -> "[S";
              case BasicType.INT -> "[I";
              case BasicType.LONG -> "[J";
              default -> "[?";
            };

        cls = classesByName.get(arrayClassName);
        if (cls == null) {
          // Create synthetic class for primitive array
          cls = new HeapClassImpl(0, arrayClassName, this);
          cls.setPrimitiveArrayType(meta.elementType);
          classesByName.put(arrayClassName, cls);
        }
      } else {
        // Regular object or object array - lookup class by address
        long classAddress64 = classId32ToAddress.get(meta.classId);
        if (classAddress64 == -1) {
          throw new IOException("Unknown class ID: " + meta.classId);
        }
        cls = getClassByIdInternal(classAddress64);
      }

      // Create object with lazy loading support
      HeapObjectImpl obj =
          new HeapObjectImpl(id, cls, meta.fileOffset, meta.dataSize, this);
      obj.setShallowSize(meta.dataSize); // Approximate

      if (meta.isArray()) {
        obj.setArrayLength(meta.arrayLength);
        if (meta.isObjectArray()) {
          obj.setObjectArray(true);
        } else if (meta.isPrimitiveArray()) {
          // Apply primitive type from index
          obj.setPrimitiveArrayType(meta.elementType);
        }
      }

      // Load retained size from persistent index if available
      if (retainedSizeReader != null) {
        try {
          long retainedSize = retainedSizeReader.getRetainedSize(id32);
          obj.setRetainedSize(retainedSize);
        } catch (IllegalArgumentException e) {
          // Object ID out of range in retained index - this can happen if index is stale
          LOG.warn("Retained size not found for object id32={}", id32);
        }
      }

      // Cache for future lookups (bounded LRU cache in indexed mode)
      cacheObject(id, obj);

      return obj;
    } catch (Exception e) {
      LOG.error("Failed to load object from index: id={}, id32={}", id, id32, e);
      return null;
    }
  }

  // === HeapDump interface implementation ===

  @Override
  public Path getPath() {
    return path;
  }

  @Override
  public long getTimestamp() {
    return reader.getTimestamp();
  }

  @Override
  public String getFormatVersion() {
    return reader.getFormatVersion();
  }

  @Override
  public int getIdSize() {
    return reader.getIdSize();
  }

  @Override
  public Collection<HeapClass> getClasses() {
    return Collections.unmodifiableCollection(classesById.values());
  }

  @Override
  public Optional<HeapClass> getClassById(long id) {
    return Optional.ofNullable(classesById.get(id));
  }

  @Override
  public Optional<HeapClass> getClassByName(String name) {
    return Optional.ofNullable(classesByName.get(name));
  }

  @Override
  public Stream<HeapClass> findClasses(Predicate<HeapClass> predicate) {
    return classesById.values().stream().map(c -> (HeapClass) c).filter(predicate);
  }

  @Override
  public Stream<HeapObject> getObjects() {
    // In indexed mode, iterate through all object addresses and lazy-load
    // Filter out class objects (classes are accessed via getClasses(), not getObjects())
    if (objectIndexReader != null) {
      return addressToId32.keySet().stream()
          .filter(addr -> !classAddresses.contains(addr)) // Exclude class objects
          .map(this::getObjectByIdInternal)
          .map(o -> (HeapObject) o);
    }
    // In-memory mode: return all cached objects
    return getCachedObjects().stream().map(o -> (HeapObject) o);
  }

  @Override
  public Optional<HeapObject> getObjectById(long id) {
    // In indexed mode, use lazy-loading mechanism
    if (objectIndexReader != null) {
      return Optional.ofNullable(getObjectByIdInternal(id));
    }
    // In-memory mode: direct cache lookup
    return Optional.ofNullable(getCachedObject(id));
  }

  @Override
  public Stream<HeapObject> getObjectsOfClass(HeapClass cls) {
    return getObjects().filter(o -> o.getHeapClass() == cls);
  }

  @Override
  public Stream<HeapObject> findObjects(Predicate<HeapObject> predicate) {
    return getObjects().filter(predicate);
  }

  @Override
  public Collection<GcRoot> getGcRoots() {
    return Collections.unmodifiableCollection(gcRoots);
  }

  @Override
  public Stream<GcRoot> getGcRoots(GcRoot.Type type) {
    return gcRoots.stream().filter(r -> r.getType() == type).map(r -> (GcRoot) r);
  }

  @Override
  public int getClassCount() {
    return classesById.size();
  }

  @Override
  public int getObjectCount() {
    return objectCount;
  }

  /**
   * Returns the inbound count reader for indexed parsing mode.
   * Package-private for use by ApproximateRetainedSizeComputer.
   *
   * @return inbound count reader, or null if in in-memory mode or not yet built
   */
  InboundCountReader getInboundCountReader() {
    return inboundCountReader;
  }

  /**
   * Returns the address-to-ID mapping for indexed parsing mode.
   * Package-private for use by ApproximateRetainedSizeComputer.
   *
   * @return address-to-ID mapping, or null if in in-memory mode
   */
  Long2IntOpenHashMap getAddressToId32() {
    return addressToId32;
  }

  @Override
  public int getGcRootCount() {
    return gcRoots.size();
  }

  @Override
  public long getTotalHeapSize() {
    return totalHeapSize;
  }

  @Override
  public void computeDominators() {
    computeDominators(null);
  }

  /**
   * Computes approximate retained sizes with optional progress callback.
   *
   * @param progressCallback optional callback for progress updates
   */
  public void computeDominators(ApproximateRetainedSizeComputer.ProgressCallback progressCallback) {
    if (dominatorsComputed) return;
    LOG.debug("Computing approximate retained sizes for {} objects...", objectCount);

    if (progressCallback != null) {
      progressCallback.onProgress(0.0, "Initializing retained size computation");
    }

    // For indexed mode, ensure inbound index is built first
    ensureInboundIndexBuilt(progressCallback);

    if (options.parsingMode() == HeapDumpParser.ParsingMode.INDEXED) {
      // Streaming mode with persistent storage - avoids loading all 114M objects into memory
      LOG.info("Using streaming computation with persistent retained size storage");

      if (progressCallback != null) {
        progressCallback.onProgress(0.01, "Creating retained size storage");
      }

      try {
        // Create persistent storage writer for ALL id32 values (including classes)
        // Classes will have retained size = 0, but this avoids complex id32 remapping
        int totalEntries = addressToId32.size();  // includes classes
        io.jafar.hdump.index.RetainedSizeWriter writer =
            new io.jafar.hdump.index.RetainedSizeWriter(indexDir, totalEntries);

        try {
          // Stream through ALL id32 values (objects + classes) without caching
          Iterable<HeapObjectImpl> streamingIterator = createStreamingObjectIterator();
          ApproximateRetainedSizeComputer.computeAll(
              this, streamingIterator, totalEntries, gcRoots, writer, progressCallback);
        } finally {
          writer.close(); // Atomic commit to retained.idx
        }

        // Open reader for future queries
        retainedSizeReader = new io.jafar.hdump.index.RetainedSizeReader(indexDir);
        LOG.info("Retained sizes persisted to {}", indexDir.resolve("retained.idx"));

      } catch (java.io.IOException e) {
        throw new RuntimeException("Failed to compute retained sizes in streaming mode", e);
      }
    } else {
      // In-memory mode: objects already in unbounded map, set retained sizes directly on objects
      ApproximateRetainedSizeComputer.computeAll(this, objectsByIdUnbounded, gcRoots, progressCallback);
    }

    dominatorsComputed = true;
  }

  /**
   * Ensures all objects are loaded into objectsById for algorithms that need full access.
   *
   * <p>This is only needed for indexed parsing mode when running algorithms like:
   * <ul>
   *   <li>Approximate retained size computation (needs to iterate all objects)
   *   <li>Hybrid dominator computation (needs top N by retained size)
   *   <li>Full dominator tree computation
   * </ul>
   *
   * <p>In in-memory mode, objects are already loaded during parsing, so this is a no-op.
   *
   * @param progressCallback optional callback for progress updates
   * @throws RuntimeException if loading fails
   */
  private void ensureAllObjectsLoaded(ApproximateRetainedSizeComputer.ProgressCallback progressCallback) {
    if (options.parsingMode() != HeapDumpParser.ParsingMode.INDEXED) {
      return; // Already loaded in in-memory mode
    }

    if (getCachedObjectCount() == objectCount) {
      return; // Already loaded
    }

    LOG.info("Loading all {} objects from index for algorithm execution...", objectCount);
    long startTime = System.currentTimeMillis();

    // Load all objects by iterating through address mapping
    // This triggers lazy loading via getObjectByIdInternal()
    int loaded = 0;
    for (var entry : addressToId32.long2IntEntrySet()) {
      long objectId = entry.getLongKey();
      getObjectByIdInternal(objectId); // Lazy load and cache

      loaded++;
      if (loaded % 100000 == 0) {
        double progress = (double) loaded / objectCount;
        if (progressCallback != null) {
          progressCallback.onProgress(progress * 0.3, String.format("Loading objects from index (%,d / %,d)", loaded, objectCount));
        }
        LOG.debug("Loaded {} / {} objects", loaded, objectCount);
      }
    }

    // Report completion
    if (progressCallback != null) {
      progressCallback.onProgress(0.3, String.format("Loaded %,d objects from index", objectCount));
    }

    long elapsedMs = System.currentTimeMillis() - startTime;
    LOG.info("Loaded all {} objects in {} seconds", objectCount, elapsedMs / 1000.0);
  }

  /**
   * Creates an iterable that streams through all objects WITHOUT caching them in objectsById.
   *
   * <p>Used for memory-efficient algorithms that need to process all objects once without
   * keeping them in memory. This enables approximate retained size computation for heap dumps
   * with 100M+ objects that would cause OOM if fully cached.
   *
   * <p><strong>Memory Characteristics:</strong>
   * <ul>
   *   <li>Heap usage: O(1) - only one object in memory at a time
   *   <li>Each object is reconstructed from index on-demand
   *   <li>No caching in objectsById map
   *   <li>Objects are GC-eligible immediately after iterator advances
   * </ul>
   *
   * <p><strong>Performance Trade-offs:</strong>
   * <ul>
   *   <li>Slower than cached access (reconstructs each object)
   *   <li>But enables unbounded heap size support
   *   <li>Disk I/O is sequential through index, SSD-friendly
   * </ul>
   *
   * @return iterable that lazy-loads each object exactly once
   */
  private Iterable<HeapObjectImpl> createStreamingObjectIterator() {
    return () -> new java.util.Iterator<HeapObjectImpl>() {
      // CRITICAL: Iterate id32 in sequential order (0, 1, 2, ...) for RetainedSizeWriter
      // Includes ALL id32 values even classes - classes will get retained size = 0
      private int currentId32 = 0;
      private final int maxId32 = id32ToAddress.size();

      @Override
      public boolean hasNext() {
        return currentId32 < maxId32;
      }

      @Override
      public HeapObjectImpl next() {
        if (!hasNext()) {
          throw new java.util.NoSuchElementException();
        }
        long address = id32ToAddress.get(currentId32);
        currentId32++;

        // For class addresses, return a minimal object (will get retained size = 0)
        if (classAddresses.contains(address)) {
          // Return minimal HeapObjectImpl for classes - retained size will be 0
          HeapClassImpl cls = classesById.get(address);
          HeapObjectImpl classObj = new HeapObjectImpl(address, cls, 0, 0, HeapDumpImpl.this);
          classObj.setRetainedSize(0);  // Classes have no retained size
          return classObj;
        }

        // Load regular object WITHOUT adding to objectsById cache
        return loadObjectWithoutCaching(address);
      }
    };
  }

  /**
   * Loads an object from index WITHOUT caching in objectsById.
   *
   * <p>This method reconstructs a HeapObjectImpl from the index without adding it to the
   * objectsById cache. Used by streaming algorithms to avoid OOM.
   *
   * <p><strong>Critical for Memory Efficiency:</strong> This is identical to
   * getObjectByIdInternal() except it does NOT call objectsById.put(). For 114M objects,
   * this difference prevents ~3.6GB HashMap overhead.
   *
   * @param objectId 64-bit object address
   * @return heap object (not cached)
   */
  private HeapObjectImpl loadObjectWithoutCaching(long objectId) {
    int id32 = addressToId32.get(objectId);
    if (id32 == -1) {
      throw new IllegalStateException("Object not found in addressToId32: " + objectId);
    }

    try {
      ObjectIndexReader.ObjectMetadata meta = objectIndexReader.readObject(id32);

      HeapClassImpl cls;

      // Special handling for primitive arrays (classId == -1)
      if (meta.classId == -1 && meta.isPrimitiveArray()) {
        // Create synthetic class name for primitive array
        String arrayClassName =
            switch (meta.elementType) {
              case BasicType.BOOLEAN -> "[Z";
              case BasicType.CHAR -> "[C";
              case BasicType.FLOAT -> "[F";
              case BasicType.DOUBLE -> "[D";
              case BasicType.BYTE -> "[B";
              case BasicType.SHORT -> "[S";
              case BasicType.INT -> "[I";
              case BasicType.LONG -> "[J";
              default -> "[?";
            };

        cls = classesByName.get(arrayClassName);
        if (cls == null) {
          // Create synthetic class for primitive array (these are cached since they're few)
          cls = new HeapClassImpl(0, arrayClassName, this);
          cls.setPrimitiveArrayType(meta.elementType);
          classesByName.put(arrayClassName, cls);
        }
      } else {
        // Regular object or object array - lookup class by address
        long classAddress64 = classId32ToAddress.get(meta.classId);
        if (classAddress64 == -1) {
          throw new IOException("Unknown class ID: " + meta.classId);
        }
        cls = getClassByIdInternal(classAddress64);
      }

      // Create object with lazy loading support
      HeapObjectImpl obj =
          new HeapObjectImpl(objectId, cls, meta.fileOffset, meta.dataSize, this);
      obj.setShallowSize(meta.dataSize); // Approximate

      if (meta.isArray()) {
        obj.setArrayLength(meta.arrayLength);
        if (meta.isObjectArray()) {
          obj.setObjectArray(true);
        } else if (meta.isPrimitiveArray()) {
          // Apply primitive type from index
          obj.setPrimitiveArrayType(meta.elementType);
        }
      }

      // Load retained size from persistent index if available
      if (retainedSizeReader != null) {
        try {
          long retainedSize = retainedSizeReader.getRetainedSize(id32);
          obj.setRetainedSize(retainedSize);
        } catch (IllegalArgumentException e) {
          // Object ID out of range in retained index - this can happen if index is stale
          LOG.warn("Retained size not found for object id32={}", id32);
        }
      }

      // CRITICAL DIFFERENCE: No objectsById.put() here!
      // This object will be GC-eligible as soon as caller releases reference

      return obj;
    } catch (Exception e) {
      LOG.error("Failed to load object from index without caching: id={}, id32={}", objectId, id32, e);
      throw new RuntimeException("Failed to load object", e);
    }
  }

  /**
   * Ensures inbound reference index is built for retained size computation.
   *
   * <p>This is only needed for indexed parsing mode. In in-memory mode, inbound references
   * are tracked directly if enabled via ParserOptions.
   *
   * <p>The inbound index is built on-demand when first needed (progressive indexing strategy).
   * Once built, it's persisted to disk for instant reuse on subsequent opens.
   *
   * <p>Package-private for testing.
   *
   * @throws RuntimeException if index building fails
   */
  void ensureInboundIndexBuilt(ApproximateRetainedSizeComputer.ProgressCallback progressCallback) {
    if (inboundIndexBuilt || options.parsingMode() != HeapDumpParser.ParsingMode.INDEXED) {
      return;
    }

    synchronized (this) {
      if (inboundIndexBuilt) {
        return; // Double-check after acquiring lock
      }

      try {
        Path inboundIndexFile = indexDir.resolve(IndexFormat.INBOUND_INDEX_NAME);

        if (Files.exists(inboundIndexFile)) {
          // Index already exists, just open it
          LOG.info("Loading existing inbound index from {}", inboundIndexFile);
          inboundCountReader = new InboundCountReader(indexDir);
        } else {
          // Build inbound index on-demand
          LOG.info("Building inbound reference index for {} objects (first retained-size query)...", objectCount);
          long startTime = System.currentTimeMillis();

          // Pass progressCallback through instead of hardcoding LOG
          // Convert ApproximateRetainedSizeComputer.ProgressCallback to InboundIndexBuilder.ProgressCallback
          InboundIndexBuilder.ProgressCallback indexBuilderCallback =
              progressCallback != null
                  ? (progress, message) -> progressCallback.onProgress(progress, message)
                  : (progress, message) -> LOG.debug("Index building {}: {}%", message, String.format("%.1f", progress * 100));

          InboundIndexBuilder.buildInboundIndex(
              path,
              indexDir,
              addressToId32,
              classesById,
              indexBuilderCallback
          );

          long elapsedMs = System.currentTimeMillis() - startTime;
          LOG.info("Inbound index built in {} seconds", elapsedMs / 1000.0);

          // Open the newly built index
          inboundCountReader = new InboundCountReader(indexDir);
        }

        inboundIndexBuilt = true;
      } catch (IOException e) {
        throw new RuntimeException("Failed to build inbound reference index", e);
      }
    }
  }

  /**
   * Package-private method called by HeapObjectImpl when retained size is accessed. Ensures
   * dominator computation (or approximation) has been performed before returning retained size.
   */
  void ensureDominatorsComputed() {
    // For indexed mode, build inbound index first if needed
    ensureInboundIndexBuilt(null);
    computeDominators();
  }

  /**
   * Computes full dominator tree with exact retained sizes and dominator relationships.
   *
   * <p>This is more expensive than approximate computation but provides:
   * <ul>
   *   <li>Exact retained sizes (not approximations)
   *   <li>Full dominator tree structure
   *   <li>Ability to query dominated objects
   * </ul>
   *
   * @param progressCallback optional callback for progress updates
   */
  public void computeFullDominatorTree(DominatorTreeComputer.ProgressCallback progressCallback) {
    if (fullDominatorTreeComputed) return;

    // Note: For very large heaps in indexed mode, this will stream through all objects
    // Objects are cached in LRU with bounded memory usage, but performance may be slower
    // than in-memory mode.For heaps > 50M objects, consider using computeDominators() instead.

    LOG.info("Computing full dominator tree for {} objects...", objectCount);

    // In-memory mode: use unbounded map directly
    // Indexed mode: stream objects into map without intermediate collection (avoid OOM)
    Long2ObjectMap<HeapObjectImpl> allObjects;
    if (objectIndexReader != null) {
      allObjects = new Long2ObjectOpenHashMap<>(objectCount);
      getObjects().forEach(obj -> allObjects.put(obj.getId(), (HeapObjectImpl) obj));
    } else {
      allObjects = objectsByIdUnbounded;
    }

    dominatorChildrenMap = DominatorTreeComputer.computeFull(this, allObjects, gcRoots, progressCallback);
    dominatorsComputed = true;
    fullDominatorTreeComputed = true;
  }

  /**
   * Returns whether full dominator tree has been computed.
   */
  public boolean hasFullDominatorTree() {
    return fullDominatorTreeComputed;
  }

  /**
   * Computes exact dominators for a subset of "interesting" objects using hybrid approach.
   *
   * <p>This is much more memory-efficient than full dominator tree computation:
   * <ul>
   *   <li>Phase 1: Approximate retained sizes for all objects (~8 bytes/object)
   *   <li>Phase 2: Identify interesting objects (top N retainers)
   *   <li>Phase 3: Exact dominators only for interesting subgraph
   * </ul>
   *
   * <p><strong>Memory savings:</strong> For 100M objects, uses ~1 GB instead of ~15 GB (93% reduction).
   *
   * @param topN number of top retainers to compute exactly
   * @param classPatterns optional class name patterns to include (e.g., "*.ThreadLocal*")
   * @param progressCallback optional progress callback
   */
  public void computeHybridDominators(
      int topN,
      Set<String> classPatterns,
      DominatorTreeComputer.ProgressCallback progressCallback) {

    // Note: In indexed mode, hybrid computation uses streaming with LRU cache.
    // Objects are loaded on-demand and automatically evicted, keeping memory bounded.
    // For in-memory mode, ensure all objects are loaded first
    // Adapt DominatorTreeComputer.ProgressCallback to ApproximateRetainedSizeComputer.ProgressCallback
    ApproximateRetainedSizeComputer.ProgressCallback adaptedCallback =
        progressCallback != null ? progressCallback::onProgress : null;
    ensureAllObjectsLoaded(adaptedCallback);

    // Phase 1: Ensure approximate retained sizes computed for all objects
    if (!dominatorsComputed) {
      LOG.info("Computing approximate retained sizes for all {} objects...", objectCount);
      computeDominators();
    }

    // Phase 2: Identify interesting objects (streams through objects with LRU cache in indexed mode)
    LOG.info("Identifying top {} interesting objects for exact computation...", topN);
    LongOpenHashSet interesting =
        HybridDominatorComputer.identifyInterestingObjects(this, topN, classPatterns);

    // Phase 3: Expand to include dominator paths
    LOG.info("Expanding interesting set to include dominator paths...");
    LongOpenHashSet expanded =
        HybridDominatorComputer.expandToDominatorPaths(this, gcRoots, interesting);

    // Phase 4: Compute exact dominators for subgraph
    LOG.info("Computing exact dominators for {} objects ({}% of heap)...",
        expanded.size(),
        String.format("%.2f", expanded.size() * 100.0 / objectCount));
    HybridDominatorComputer.computeExactForSubgraph(this, gcRoots, expanded, progressCallback);

    LOG.info(
        "Hybrid dominator computation complete: {} objects with exact retained sizes",
        expanded.size());
  }

  /**
   * Computes exact dominators for specific objects matching a filter.
   *
   * <p>This allows targeted exact computation for specific classes or patterns.
   *
   * @param classPatterns class name patterns to match (e.g., "java.util.HashMap", "*.cache.*")
   * @param progressCallback optional progress callback
   */
  public void computeExactForClasses(
      Set<String> classPatterns, DominatorTreeComputer.ProgressCallback progressCallback) {

    // Note: In indexed mode, streams through objects with LRU cache for memory efficiency

    // Ensure approximate computed first
    if (!dominatorsComputed) {
      computeDominators();
    }

    // Identify all objects matching patterns (streams through objects)
    LongOpenHashSet matching = new LongOpenHashSet();
    for (HeapObject obj : getObjects().toList()) {
      if (obj.getHeapClass() != null) {
        String className = obj.getHeapClass().getName();
        for (String pattern : classPatterns) {
          if (matchesPattern(className, pattern)) {
            matching.add(obj.getId());
            break;
          }
        }
      }
    }

    if (matching.isEmpty()) {
      LOG.warn("No objects matched patterns: {}", classPatterns);
      return;
    }

    LOG.info("Found {} objects matching patterns", matching.size());

    // Expand and compute exact
    LongOpenHashSet expanded =
        HybridDominatorComputer.expandToDominatorPaths(this, gcRoots, matching);
    HybridDominatorComputer.computeExactForSubgraph(this, gcRoots, expanded, progressCallback);
  }

  private static boolean matchesPattern(String text, String pattern) {
    if (pattern.equals("*")) {
      return true;
    }
    String regex =
        pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").replace("$", "\\$");
    return text.matches(regex);
  }

  @Override
  public boolean hasDominators() {
    return dominatorsComputed;
  }

  /**
   * Gets all objects dominated by the given object.
   *
   * <p>Requires full dominator tree to be computed first.
   *
   * @param dominator the dominating object
   * @return list of dominated objects (empty if full tree not computed)
   */
  public List<HeapObject> getDominatedObjects(HeapObject dominator) {
    if (!fullDominatorTreeComputed || dominatorChildrenMap == null) {
      return Collections.emptyList();
    }

    // O(1) lookup using cached children map instead of O(N) scan
    List<Long> childrenIds = dominatorChildrenMap.get(dominator.getId());
    if (childrenIds == null) {
      return Collections.emptyList();
    }

    List<HeapObject> dominated = new ArrayList<>(childrenIds.size());
    for (Long childId : childrenIds) {
      HeapObjectImpl obj = getCachedObject(childId);
      if (obj != null) {
        dominated.add(obj);
      }
    }
    return dominated;
  }

  @Override
  public List<HeapObject> findPathToGcRoot(HeapObject obj) {
    return PathFinder.findShortestPath(this, obj, gcRoots);
  }

  /**
   * Caches an object in the appropriate storage (unbounded map or LRU cache).
   * In-memory mode: uses unbounded fastutil map.
   * Indexed mode: uses bounded LRU cache.
   */
  private void cacheObject(long objectId, HeapObjectImpl obj) {
    if (objectsByIdLru != null) {
      objectsByIdLru.put(objectId, obj);
    } else {
      objectsByIdUnbounded.put(objectId, obj);
    }
  }

  /**
   * Retrieves a cached object from appropriate storage.
   * Returns null if not cached.
   */
  private HeapObjectImpl getCachedObject(long objectId) {
    if (objectsByIdLru != null) {
      return objectsByIdLru.get(objectId);
    } else {
      return objectsByIdUnbounded.get(objectId);
    }
  }

  /**
   * Returns all currently cached objects for iteration.
   * Used by algorithms that need to process all cached objects.
   */
  private java.util.Collection<HeapObjectImpl> getCachedObjects() {
    if (objectsByIdLru != null) {
      return objectsByIdLru.values();
    } else {
      return objectsByIdUnbounded.values();
    }
  }

  /**
   * Returns the number of objects currently cached in memory.
   * In indexed mode with LRU cache, this may be less than total object count.
   */
  private int getCachedObjectCount() {
    if (objectsByIdLru != null) {
      return objectsByIdLru.size();
    } else {
      return objectsByIdUnbounded.size();
    }
  }


  @Override
  public void close() throws IOException {
    // Capture cache size before clearing
    int cachedObjects = getCachedObjectCount();

    reader.close();
    if (objectIndexReader != null) {
      objectIndexReader.close();
    }
    if (inboundCountReader != null) {
      inboundCountReader.close();
    }
    if (retainedSizeReader != null) {
      retainedSizeReader.close();
    }

    // Clear object caches to free memory
    if (objectsByIdLru != null) {
      objectsByIdLru.clear();
    }
    if (objectsByIdUnbounded != null) {
      objectsByIdUnbounded.clear();
    }

    LOG.debug("Closed heap dump and released {} cached objects", cachedObjects);
  }
}
