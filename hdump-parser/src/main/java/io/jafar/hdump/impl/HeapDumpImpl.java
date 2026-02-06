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

  // Object table: ID -> HeapObjectImpl (lazily populated) - IN-MEMORY MODE ONLY
  private final Long2ObjectMap<HeapObjectImpl> objectsById = new Long2ObjectOpenHashMap<>();

  // Index-based parsing fields - INDEXED MODE ONLY
  private ObjectIndexReader objectIndexReader;  // null in in-memory mode
  private InboundCountReader inboundCountReader; // null until first retained-size query
  private Long2IntOpenHashMap addressToId32;    // 64-bit address -> 32-bit ID mapping
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

    if (options.useIndexedParsing()) {
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
   * <p>Memory usage: ~3.5 GB for 114M objects (vs 25 GB for in-memory)
   * <p>Parse time: ~90 seconds for 114M objects (vs 15-20 minutes full index build)
   */
  private void parseTwoPass(HeapDumpParser.ProgressCallback progressCallback) throws IOException {
    // Create index directory alongside heap dump
    indexDir = path.getParent().resolve(path.getFileName().toString() + ".idx");
    Files.createDirectories(indexDir);

    LOG.debug("Two-pass parsing: building indexes in {}", indexDir);

    // Pass 1: Collect addresses (30% of parse time)
    if (progressCallback != null) {
      progressCallback.onProgress(0.0, "Pass 1: Collecting addresses");
    }

    LongArrayList objectAddresses = new LongArrayList();
    collectObjectAddresses(objectAddresses);

    // Sort and create address-to-ID mapping
    objectAddresses.sort(null);
    addressToId32 = new Long2IntOpenHashMap(objectAddresses.size());
    addressToId32.defaultReturnValue(-1);
    for (int i = 0; i < objectAddresses.size(); i++) {
      addressToId32.put(objectAddresses.getLong(i), i);
    }

    objectCount = objectAddresses.size();
    LOG.debug("Pass 1 complete: collected {} object addresses", objectCount);

    if (progressCallback != null) {
      progressCallback.onProgress(0.3, "Pass 1 complete");
    }

    // Pass 2: Build indexes (70% of parse time)
    if (progressCallback != null) {
      progressCallback.onProgress(0.3, "Pass 2: Building indexes");
    }

    buildIndexes();

    LOG.debug("Pass 2 complete: indexes built");

    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Complete");
    }

    // Open index reader for lazy loading
    objectIndexReader = new ObjectIndexReader(indexDir);

    LOG.debug(
        "Parsed {} classes, {} objects, {} GC roots (indexed mode)",
        classesById.size(),
        objectCount,
        gcRoots.size());
  }

  /** Pass 1: Collect all object addresses in single scan. */
  private void collectObjectAddresses(LongArrayList objectAddresses) throws IOException {
    reader.reset();

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
    }
  }

  private void collectAddressesFromHeapDump(
      RecordHeader header, LongArrayList objectAddresses) {
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
        case HeapTag.CLASS_DUMP -> parseClassDump();  // Still need class metadata
        default -> skipGcRoot(subTag);  // GC roots, etc.
      }
    }
  }

  /** Pass 2: Build indexes using address-to-ID mapping. */
  private void buildIndexes() throws IOException {
    try (IndexWriter writer = new IndexWriter(indexDir)) {
      writer.beginObjectsIndex(objectCount);

      // Map for class addresses -> 32-bit class IDs
      Long2IntOpenHashMap classIdMap = new Long2IntOpenHashMap();
      classIdMap.defaultReturnValue(-1);

      reader.reset();

      while (reader.hasMoreRecords()) {
        RecordHeader header = reader.readRecordHeader();
        if (header == null) break;

        switch (header.tag()) {
          case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT ->
              buildIndexFromHeapDump(header, writer, classIdMap);
          default -> reader.skipRecordBody(header);
        }
      }

      writer.finishObjectsIndex();
    }
  }

  private void buildIndexFromHeapDump(
      RecordHeader header, IndexWriter writer, Long2IntOpenHashMap classIdMap)
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

          writer.writeObjectEntry(objectId32, fileOffset, dataSize, classId, -1, (byte) 0);
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

          writer.writeObjectEntry(
              objectId32, fileOffset, dataSize, classId, length, IndexFormat.FLAG_IS_OBJECT_ARRAY);
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

          writer.writeObjectEntry(
              objectId32,
              fileOffset,
              dataSize,
              -1,
              length,
              IndexFormat.FLAG_IS_PRIMITIVE_ARRAY);
        }
        case HeapTag.CLASS_DUMP -> skipClassDumpInPass2();
        default -> skipGcRoot(subTag);
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

  private void skipGcRoot(int subTag) {
    switch (subTag) {
      case HeapTag.ROOT_UNKNOWN, HeapTag.ROOT_STICKY_CLASS, HeapTag.ROOT_MONITOR_USED -> reader.readId();
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
        // Unknown tag - shouldn't happen with well-formed dumps
        LOG.warn("Unknown heap sub-tag during indexed parsing: 0x{}", Integer.toHexString(subTag));
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
    if (className != null) {
      // Convert internal format (e.g., "java/lang/String") to external ("java.lang.String")
      className = className.replace('/', '.');
    }

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
    if (cls != null) {
      cls.setSuperClassId(superClassId);
      cls.setClassLoaderId(classLoaderId);
      cls.setInstanceSize(instanceSize);
    }

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
    if (cls != null) {
      cls.setStaticFields(staticFields);
    }

    // Instance fields
    int fieldCount = reader.readU2();
    List<HeapFieldImpl> instanceFields = new ArrayList<>(fieldCount);
    for (int i = 0; i < fieldCount; i++) {
      long nameId = reader.readId();
      int type = reader.readU1();
      String name = strings.get(nameId);
      instanceFields.add(new HeapFieldImpl(name, type, false, cls, null));
    }
    if (cls != null) {
      cls.setInstanceFields(instanceFields);
    }
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
    objectsById.put(objId, obj);
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
    objectsById.put(objId, obj);
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
    objectsById.put(objId, obj);
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
      return objectsById.get(id);
    }

    // Indexed mode: lazy load from index
    int id32 = addressToId32.get(id);
    if (id32 == -1) {
      return null; // Object not found
    }

    // Check if already loaded
    HeapObjectImpl cached = objectsById.get(id);
    if (cached != null) {
      return cached;
    }

    // Load from index
    try {
      ObjectIndexReader.ObjectMetadata meta = objectIndexReader.readObject(id32);
      HeapClassImpl cls = getClassByIdInternal(meta.classId);  // TODO: need to map classId correctly

      // Create object with lazy loading support
      HeapObjectImpl obj =
          new HeapObjectImpl(id, cls, meta.fileOffset, meta.dataSize, this);
      obj.setShallowSize(meta.dataSize); // Approximate

      if (meta.isArray()) {
        obj.setArrayLength(meta.arrayLength);
        if (meta.isObjectArray()) {
          obj.setObjectArray(true);
        } else if (meta.isPrimitiveArray()) {
          // Determine primitive type from class (if available)
          obj.setPrimitiveArrayType(0); // TODO: extract from meta
        }
      }

      // Cache for future lookups
      objectsById.put(id, obj);

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
    return objectsById.values().stream().map(o -> (HeapObject) o);
  }

  @Override
  public Optional<HeapObject> getObjectById(long id) {
    return Optional.ofNullable(objectsById.get(id));
  }

  @Override
  public Stream<HeapObject> getObjectsOfClass(HeapClass cls) {
    return objectsById.values().stream()
        .filter(o -> o.getHeapClass() == cls)
        .map(o -> (HeapObject) o);
  }

  @Override
  public Stream<HeapObject> findObjects(Predicate<HeapObject> predicate) {
    return objectsById.values().stream().map(o -> (HeapObject) o).filter(predicate);
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
    ApproximateRetainedSizeComputer.computeAll(this, objectsById, gcRoots, progressCallback);
    dominatorsComputed = true;
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
  void ensureInboundIndexBuilt() {
    if (inboundIndexBuilt || !options.useIndexedParsing()) {
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

          InboundIndexBuilder.buildInboundIndex(
              path,
              indexDir,
              addressToId32,
              (progress, message) -> LOG.debug("Index building {}: {}%", message, String.format("%.1f", progress * 100))
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
    ensureInboundIndexBuilt();
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
    LOG.info("Computing full dominator tree for {} objects...", objectCount);
    dominatorChildrenMap = DominatorTreeComputer.computeFull(this, objectsById, gcRoots, progressCallback);
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

    // Phase 1: Ensure approximate retained sizes computed for all objects
    if (!dominatorsComputed) {
      LOG.info("Computing approximate retained sizes for all {} objects...", objectCount);
      computeDominators();
    }

    // Phase 2: Identify interesting objects
    LOG.info("Identifying top {} interesting objects for exact computation...", topN);
    LongOpenHashSet interesting =
        HybridDominatorComputer.identifyInterestingObjects(objectsById, topN, classPatterns);

    // Phase 3: Expand to include dominator paths
    LOG.info("Expanding interesting set to include dominator paths...");
    LongOpenHashSet expanded =
        HybridDominatorComputer.expandToDominatorPaths(this, objectsById, gcRoots, interesting);

    // Phase 4: Compute exact dominators for subgraph
    LOG.info("Computing exact dominators for {} objects ({}% of heap)...",
        expanded.size(),
        String.format("%.2f", expanded.size() * 100.0 / objectCount));
    HybridDominatorComputer.computeExactForSubgraph(
        this, objectsById, gcRoots, expanded, progressCallback);

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

    // Ensure approximate computed first
    if (!dominatorsComputed) {
      computeDominators();
    }

    // Identify all objects matching patterns
    LongOpenHashSet matching = new LongOpenHashSet();
    for (HeapObjectImpl obj : objectsById.values()) {
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
        HybridDominatorComputer.expandToDominatorPaths(this, objectsById, gcRoots, matching);
    HybridDominatorComputer.computeExactForSubgraph(
        this, objectsById, gcRoots, expanded, progressCallback);
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
      HeapObjectImpl obj = objectsById.get(childId);
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

  @Override
  public void close() throws IOException {
    reader.close();
    if (objectIndexReader != null) {
      objectIndexReader.close();
    }
    if (inboundCountReader != null) {
      inboundCountReader.close();
    }
  }
}
