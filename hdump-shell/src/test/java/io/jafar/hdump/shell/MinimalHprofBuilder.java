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
final class MinimalHprofBuilder {

  private static final int ID_SIZE = 8;

  private record ClassDef(long id, String name) {}

  private record Instance(long id, long classId) {}

  private record GcRoot(long objectId) {}

  private final List<ClassDef> classes = new ArrayList<>();
  private final List<Instance> instances = new ArrayList<>();
  private final List<GcRoot> roots = new ArrayList<>();

  /** Registers a class with the given address and internal name (e.g. {@code java/lang/String}). */
  MinimalHprofBuilder addClass(long classId, String internalName) {
    classes.add(new ClassDef(classId, internalName));
    return this;
  }

  /** Adds an instance of the given class. */
  MinimalHprofBuilder addInstance(long objectId, long classId) {
    instances.add(new Instance(objectId, classId));
    return this;
  }

  /** Adds a ROOT_UNKNOWN GC root for the given object. */
  MinimalHprofBuilder addGcRoot(long objectId) {
    roots.add(new GcRoot(objectId));
    return this;
  }

  /**
   * Writes the HPROF to a temp file inside {@code dir} and returns the path.
   *
   * @param dir directory for the temp file
   * @return path to the written file
   */
  Path write(Path dir) throws IOException {
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

    // LOAD_CLASS record for each class
    for (ClassDef cls : classes) {
      writeLoadClass(out, cls.id, cls.id + 10_000);
    }

    // Build the HEAP_DUMP segment
    ByteArrayOutputStream heapBos = new ByteArrayOutputStream();
    DataOutputStream h = new DataOutputStream(heapBos);

    // CLASS_DUMP for every class
    for (ClassDef cls : classes) {
      writeClassDump(h, cls.id, 0, 0);
    }

    // INSTANCE_DUMP for every instance
    for (Instance inst : instances) {
      writeInstanceDump(h, inst.id, inst.classId, new byte[0]);
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

  private static void writeClassDump(
      DataOutputStream out, long classId, long superId, int instanceSize) throws IOException {
    out.writeByte(0x20); // CLASS_DUMP
    out.writeLong(classId);
    out.writeInt(0); // stack trace
    out.writeLong(superId);
    out.writeLong(0); // class loader
    out.writeLong(0); // signers
    out.writeLong(0); // protection domain
    out.writeLong(0); // reserved
    out.writeLong(0); // reserved
    out.writeInt(instanceSize);
    out.writeShort(0); // constant pool
    out.writeShort(0); // static fields
    out.writeShort(0); // instance fields
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
