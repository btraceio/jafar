package io.jafar.hdump.test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates minimal synthetic HPROF heap dumps for testing.
 *
 * <p>Creates valid but tiny heap dumps that can be used in unit tests without requiring large
 * binary test resources to be checked in.
 */
public class SyntheticHeapDumpGenerator {

  /**
   * Generates a minimal heap dump with a few objects.
   *
   * @param outputPath where to write the heap dump
   * @param objectCount number of objects to create
   * @throws IOException if writing fails
   */
  public static void generateMinimalHeapDump(Path outputPath, int objectCount)
      throws IOException {

    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {

      // Write HPROF header
      writeHeader(out);

      // Write UTF-8 strings (class names)
      writeUtf8String(out, 1, "java/lang/Object");
      writeUtf8String(out, 2, "java/lang/String");
      writeUtf8String(out, 3, "value");

      // Write LOAD_CLASS records
      writeLoadClass(out, 1, 100); // Object class
      writeLoadClass(out, 2, 101); // String class

      // Write HEAP_DUMP record with objects
      writeHeapDump(out, objectCount);
    }
  }

  private static void writeHeader(DataOutputStream out) throws IOException {
    // HPROF file format header: "JAVA PROFILE 1.0.2\0"
    out.write("JAVA PROFILE 1.0.2\0".getBytes("UTF-8"));

    // ID size (4 bytes for 32-bit addresses, or 8 for 64-bit)
    out.writeInt(8); // 64-bit

    // Timestamp (milliseconds)
    out.writeLong(System.currentTimeMillis());
  }

  private static void writeUtf8String(DataOutputStream out, long id, String text)
      throws IOException {
    byte[] bytes = text.getBytes("UTF-8");

    out.writeByte(0x01); // UTF8 tag
    out.writeInt(0); // Timestamp
    out.writeInt(8 + bytes.length); // Length
    out.writeLong(id); // String ID
    out.write(bytes);
  }

  private static void writeLoadClass(DataOutputStream out, long classNameId, long classId)
      throws IOException {
    out.writeByte(0x02); // LOAD_CLASS tag
    out.writeInt(0); // Timestamp
    out.writeInt(4 + 8 + 4 + 8); // Length: serial + classId + stackTrace + nameId
    out.writeInt(1); // Class serial number
    out.writeLong(classId); // Class object ID
    out.writeInt(0); // Stack trace serial
    out.writeLong(classNameId); // Class name string ID
  }

  private static void writeHeapDump(DataOutputStream out, int objectCount) throws IOException {
    // Calculate heap dump size
    int heapDumpSize = 0;

    // CLASS_DUMP for Object class
    heapDumpSize += 1 + 8 + 4 + 8 + 8 + 8 + 8 + 8 + 8 + 4 + 2 + 2 + 2; // ~70 bytes

    // CLASS_DUMP for String class
    heapDumpSize += 1 + 8 + 4 + 8 + 8 + 8 + 8 + 8 + 8 + 4 + 2 + 2 + (2 + 8 + 1); // ~80 bytes

    // INSTANCE_DUMP records
    heapDumpSize += objectCount * (1 + 8 + 4 + 8 + 4 + 16); // ~41 bytes each

    // GC root (at end)
    heapDumpSize += 1 + 8; // ROOT_UNKNOWN

    out.writeByte(0x0C); // HEAP_DUMP tag
    out.writeInt(0); // Timestamp
    out.writeInt(heapDumpSize); // Length

    // Write CLASS_DUMP for Object class
    out.writeByte(0x20); // CLASS_DUMP sub-tag
    out.writeLong(100); // Class object ID
    out.writeInt(0); // Stack trace serial
    out.writeLong(0); // Super class (none)
    out.writeLong(0); // Class loader
    out.writeLong(0); // Signers
    out.writeLong(0); // Protection domain
    out.writeLong(0); // Reserved
    out.writeLong(0); // Reserved
    out.writeInt(0); // Instance size
    out.writeShort(0); // Constant pool size
    out.writeShort(0); // Static field count
    out.writeShort(0); // Instance field count

    // Write CLASS_DUMP for String class
    out.writeByte(0x20); // CLASS_DUMP sub-tag
    out.writeLong(101); // Class object ID
    out.writeInt(0); // Stack trace serial
    out.writeLong(100); // Super class (Object)
    out.writeLong(0); // Class loader
    out.writeLong(0); // Signers
    out.writeLong(0); // Protection domain
    out.writeLong(0); // Reserved
    out.writeLong(0); // Reserved
    out.writeInt(16); // Instance size
    out.writeShort(0); // Constant pool size
    out.writeShort(0); // Static field count
    out.writeShort(1); // Instance field count: value
    out.writeLong(3); // Field name ID ("value")
    out.writeByte(2); // Field type: OBJECT

    // Write INSTANCE_DUMP records
    for (int i = 0; i < objectCount; i++) {
      out.writeByte(0x21); // INSTANCE_DUMP sub-tag
      out.writeLong(1000 + i); // Object ID (1000, 1001, 1002, ...)
      out.writeInt(0); // Stack trace serial
      out.writeLong(i % 2 == 0 ? 100 : 101); // Class ID (alternate Object/String)
      out.writeInt(16); // Data size
      // Write 16 bytes of dummy field data
      out.writeLong(0);
      out.writeLong(0);
    }

    // Write GC root pointing to first object
    out.writeByte(0xFF); // ROOT_UNKNOWN
    out.writeLong(1000); // Object ID (points to first object)
  }
}
