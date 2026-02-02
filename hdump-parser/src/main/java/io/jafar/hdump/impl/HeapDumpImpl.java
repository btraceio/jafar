package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.internal.BasicType;
import io.jafar.hdump.internal.HeapTag;
import io.jafar.hdump.internal.HprofReader;
import io.jafar.hdump.internal.HprofReader.RecordHeader;
import io.jafar.hdump.internal.HprofTag;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  // Object table: ID -> HeapObjectImpl (lazily populated)
  private final Long2ObjectMap<HeapObjectImpl> objectsById = new Long2ObjectOpenHashMap<>();

  // GC roots
  private final List<GcRootImpl> gcRoots = new ArrayList<>();

  // Statistics
  private int objectCount = 0;
  private long totalHeapSize = 0;
  private volatile boolean dominatorsComputed = false;

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
   * @return parsed heap dump
   * @throws IOException if parsing fails
   */
  public static HeapDump parse(Path path, ParserOptions options) throws IOException {
    HprofReader reader = new HprofReader(path);
    HeapDumpImpl dump = new HeapDumpImpl(path, reader, options);
    dump.parseRecords();
    if (options.computeDominators()) {
      dump.computeDominators();
    }
    return dump;
  }

  private void parseRecords() throws IOException {
    reader.reset();
    LOG.debug("Parsing heap dump: {}", path);

    while (reader.hasMoreRecords()) {
      RecordHeader header = reader.readRecordHeader();
      if (header == null) break;

      switch (header.tag()) {
        case HprofTag.UTF8 -> parseUtf8(header);
        case HprofTag.LOAD_CLASS -> parseLoadClass(header);
        case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT -> parseHeapDump(header);
        default -> reader.skipRecordBody(header);
      }
    }

    LOG.debug(
        "Parsed {} classes, {} objects, {} GC roots",
        classesById.size(),
        objectCount,
        gcRoots.size());
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
    int endPos = header.bodyPosition() + header.length();

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
    int dataPos = reader.position();
    reader.skip(dataSize);

    HeapClassImpl cls = classesById.get(classId);
    HeapObjectImpl obj = new HeapObjectImpl(objId, cls, dataPos, dataSize, this);
    objectsById.put(objId, obj);
    objectCount++;

    int shallowSize = reader.getIdSize() * 2 + 8 + dataSize; // Approximate
    totalHeapSize += shallowSize;
    obj.setShallowSize(shallowSize);

    if (cls != null) {
      cls.incrementInstanceCount();
    }
  }

  private void parseObjArrayDump() {
    long objId = reader.readId();
    int stackTraceSerial = reader.readI4();
    int length = reader.readI4();
    long arrayClassId = reader.readId();

    int dataPos = reader.position();
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
    int dataPos = reader.position();
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
    return objectsById.get(id);
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
    if (dominatorsComputed) return;
    LOG.debug("Computing dominators for {} objects...", objectCount);
    // TODO: Implement Lengauer-Tarjan algorithm
    dominatorsComputed = true;
  }

  @Override
  public boolean hasDominators() {
    // Return false since dominator computation is not yet implemented.
    // When implemented, this should return dominatorsComputed.
    return false;
  }

  @Override
  public List<HeapObject> findPathToGcRoot(HeapObject obj) {
    // TODO: Implement BFS from GC roots
    return Collections.emptyList();
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
