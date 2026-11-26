package io.jafar.hdump.shell;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds minimal in-memory HPROF files for unit tests. Produces parseable heap dumps without
 * requiring an external file or running a real JVM.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Path hprof = new MinimalHprofBuilder()
 *     .addClass(100, "java/lang/Object")
 *     .addClass(101, "java/lang/String")
 *     .addInstance(1000, 100)
 *     .addInstance(1001, 101)
 *     .addGcRoot(1000)
 *     .write(tempDir);
 * }</pre>
 */
public final class MinimalHprofBuilder {

  private static final int ID_SIZE = 8;

  /** HPROF basic type tags used by {@link #addClass(long, String, long, Field...)}. */
  public static final int TYPE_OBJECT = 2;

  public static final int TYPE_BOOLEAN = 4;

  public static final int TYPE_INT = 10;

  /** An instance field declared by a class: its name and HPROF basic type. */
  public record Field(String name, int type) {}

  private record ClassDef(long id, String name, long superId, List<Field> fields) {}

  private record Instance(long id, long classId, byte[] data) {}

  private record ObjArray(long id, long arrayClassId, long[] elements) {}

  private record PrimArray(long id, int elemType, int length) {}

  private record GcRoot(long objectId) {}

  private record JavaFrameRoot(long objectId, int threadSerial, int frameNumber) {}

  private final List<ClassDef> classes = new ArrayList<>();
  private final List<Instance> instances = new ArrayList<>();
  private final List<GcRoot> roots = new ArrayList<>();
  private final List<JavaFrameRoot> frameRoots = new ArrayList<>();
  private final List<ObjArray> arrays = new ArrayList<>();
  private final List<PrimArray> primArrays = new ArrayList<>();

  /** Registers a class with the given address and internal name (e.g. {@code java/lang/String}). */
  public MinimalHprofBuilder addClass(long classId, String internalName) {
    return addClass(classId, internalName, 0);
  }

  /**
   * Registers a class with a superclass (0 for none) and the instance fields it declares itself,
   * excluding inherited ones, in HPROF order.
   */
  public MinimalHprofBuilder addClass(
      long classId, String internalName, long superId, Field... fields) {
    classes.add(new ClassDef(classId, internalName, superId, List.of(fields)));
    return this;
  }

  /** Adds an instance of the given class with no field data. */
  public MinimalHprofBuilder addInstance(long objectId, long classId) {
    return addInstance(objectId, classId, new byte[0]);
  }

  /**
   * Adds an instance with raw field data: the class's own fields first, then each superclass's, as
   * HotSpot writes them. Build it with {@link #fieldData()}.
   */
  public MinimalHprofBuilder addInstance(long objectId, long classId, byte[] data) {
    instances.add(new Instance(objectId, classId, data));
    return this;
  }

  /** Adds an object array of the given array class holding the given element ids (0 = null). */
  public MinimalHprofBuilder addObjectArray(long arrayId, long arrayClassId, long... elements) {
    arrays.add(new ObjArray(arrayId, arrayClassId, elements));
    return this;
  }

  /**
   * Adds a zero-filled primitive array; {@code elemType} is an HPROF basic type (8 = byte, 10 =
   * int, ...). The parser gives it a {@code [B}/{@code [I}/... class; no class needs registering.
   */
  public MinimalHprofBuilder addPrimitiveArray(long arrayId, int elemType, int length) {
    primArrays.add(new PrimArray(arrayId, elemType, length));
    return this;
  }

  /** Writes instance field values in HPROF encoding (ids are 8 bytes). */
  public static final class FieldData {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bos);

    public FieldData ref(long id) throws IOException {
      out.writeLong(id);
      return this;
    }

    public FieldData bool(boolean v) throws IOException {
      out.writeByte(v ? 1 : 0);
      return this;
    }

    public FieldData i32(int v) throws IOException {
      out.writeInt(v);
      return this;
    }

    public byte[] bytes() {
      return bos.toByteArray();
    }
  }

  public static FieldData fieldData() {
    return new FieldData();
  }

  /** Adds a ROOT_JAVA_FRAME GC root (the object id need not exist in the dump). */
  public MinimalHprofBuilder addJavaFrameRoot(long objectId, int threadSerial, int frameNumber) {
    frameRoots.add(new JavaFrameRoot(objectId, threadSerial, frameNumber));
    return this;
  }

  /** Adds a ROOT_UNKNOWN GC root for the given object. */
  public MinimalHprofBuilder addGcRoot(long objectId) {
    roots.add(new GcRoot(objectId));
    return this;
  }

  /**
   * Writes the HPROF to a temp file inside {@code dir} and returns the path.
   *
   * @param dir directory for the temp file
   * @return path to the written file
   */
  public Path write(Path dir) throws IOException {
    Path file = Files.createTempFile(dir, "test-", ".hprof");
    Files.write(file, toBytes());
    return file;
  }

  /** Returns the serialised HPROF as a byte array. */
  byte[] toBytes() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bos);

    writeHeader(out);

    // UTF-8 name strings for each class (string id = class id + 10000 offset to avoid collision)
    for (ClassDef cls : classes) {
      writeUtf8(out, cls.id + 10_000, cls.name);
    }
    // Field name strings: one id per distinct name, from 900000 up
    java.util.Map<String, Long> fieldNameIds = new java.util.LinkedHashMap<>();
    for (ClassDef cls : classes) {
      for (Field f : cls.fields) {
        if (!fieldNameIds.containsKey(f.name())) {
          long id = 900_000L + fieldNameIds.size();
          fieldNameIds.put(f.name(), id);
          writeUtf8(out, id, f.name());
        }
      }
    }

    // LOAD_CLASS record for each class
    for (ClassDef cls : classes) {
      writeLoadClass(out, cls.id, cls.id + 10_000);
    }

    // Build the HEAP_DUMP segment
    ByteArrayOutputStream heapBos = new ByteArrayOutputStream();
    DataOutputStream h = new DataOutputStream(heapBos);

    // CLASS_DUMP for every class
    java.util.Map<Long, ClassDef> byId = new java.util.HashMap<>();
    for (ClassDef cls : classes) {
      byId.put(cls.id, cls);
    }
    for (ClassDef cls : classes) {
      writeClassDump(h, cls, instanceSize(cls, byId), fieldNameIds);
    }

    // INSTANCE_DUMP for every instance
    for (Instance inst : instances) {
      writeInstanceDump(h, inst.id, inst.classId, inst.data);
    }

    // OBJ_ARRAY_DUMP for every object array
    for (ObjArray arr : arrays) {
      h.writeByte(0x22);
      h.writeLong(arr.id);
      h.writeInt(0); // stack trace
      h.writeInt(arr.elements.length);
      h.writeLong(arr.arrayClassId);
      for (long e : arr.elements) {
        h.writeLong(e);
      }
    }

    // PRIM_ARRAY_DUMP for every primitive array
    for (PrimArray arr : primArrays) {
      h.writeByte(0x23);
      h.writeLong(arr.id);
      h.writeInt(0); // stack trace
      h.writeInt(arr.length);
      h.writeByte(arr.elemType);
      h.write(new byte[arr.length * fieldWidth(arr.elemType)]);
    }

    // ROOT_JAVA_FRAME: id, u4 thread serial, u4 frame number
    for (JavaFrameRoot root : frameRoots) {
      h.writeByte(0x03);
      h.writeLong(root.objectId);
      h.writeInt(root.threadSerial);
      h.writeInt(root.frameNumber);
    }

    // ROOT_UNKNOWN for every registered root
    for (GcRoot root : roots) {
      h.writeByte(0xFF); // ROOT_UNKNOWN
      h.writeLong(root.objectId);
    }

    byte[] heapBytes = heapBos.toByteArray();
    out.writeByte(0x0C); // HEAP_DUMP
    out.writeInt(0);
    out.writeInt(heapBytes.length);
    out.write(heapBytes);

    return bos.toByteArray();
  }

  // ---- HPROF primitives ----

  private static void writeHeader(DataOutputStream out) throws IOException {
    out.write("JAVA PROFILE 1.0.2\0".getBytes("UTF-8"));
    out.writeInt(ID_SIZE);
    out.writeLong(0L);
  }

  private static void writeUtf8(DataOutputStream out, long id, String text) throws IOException {
    byte[] bytes = text.getBytes("UTF-8");
    out.writeByte(0x01); // UTF8
    out.writeInt(0);
    out.writeInt(ID_SIZE + bytes.length);
    out.writeLong(id);
    out.write(bytes);
  }

  private static void writeLoadClass(DataOutputStream out, long classId, long nameId)
      throws IOException {
    out.writeByte(0x02); // LOAD_CLASS
    out.writeInt(0);
    out.writeInt(4 + ID_SIZE + 4 + ID_SIZE);
    out.writeInt(1); // class serial
    out.writeLong(classId);
    out.writeInt(0); // stack trace
    out.writeLong(nameId);
  }

  /**
   * Instance size as HotSpot writes it: the field bytes of the class and all its superclasses (no
   * object header). Field-less classes, interfaces included, get 0.
   */
  private static int instanceSize(ClassDef cls, java.util.Map<Long, ClassDef> byId) {
    int size = 0;
    for (ClassDef c = cls; c != null; c = c.superId == 0 ? null : byId.get(c.superId)) {
      for (Field f : c.fields) {
        size += fieldWidth(f.type());
      }
    }
    return size;
  }

  /** Byte width of an HPROF basic type in field data (ids are {@value #ID_SIZE} bytes). */
  private static int fieldWidth(int type) {
    return switch (type) {
      case TYPE_OBJECT -> ID_SIZE;
      case 4, 8 -> 1; // boolean, byte
      case 5, 9 -> 2; // char, short
      case 6, TYPE_INT -> 4; // float, int
      case 7, 11 -> 8; // double, long
      default -> throw new IllegalArgumentException("not an HPROF basic type: " + type);
    };
  }

  private static void writeClassDump(
      DataOutputStream out,
      ClassDef cls,
      int instanceSize,
      java.util.Map<String, Long> fieldNameIds)
      throws IOException {
    out.writeByte(0x20); // CLASS_DUMP
    out.writeLong(cls.id);
    out.writeInt(0); // stack trace
    out.writeLong(cls.superId);
    out.writeLong(0); // class loader
    out.writeLong(0); // signers
    out.writeLong(0); // protection domain
    out.writeLong(0); // reserved
    out.writeLong(0); // reserved
    out.writeInt(instanceSize);
    out.writeShort(0); // constant pool
    out.writeShort(0); // static fields
    out.writeShort(cls.fields.size()); // instance fields (own only)
    for (Field f : cls.fields) {
      out.writeLong(fieldNameIds.get(f.name()));
      out.writeByte(f.type());
    }
  }

  private static void writeInstanceDump(
      DataOutputStream out, long objectId, long classId, byte[] data) throws IOException {
    out.writeByte(0x21); // INSTANCE_DUMP
    out.writeLong(objectId);
    out.writeInt(0); // stack trace
    out.writeLong(classId);
    out.writeInt(data.length);
    out.write(data);
  }
}
