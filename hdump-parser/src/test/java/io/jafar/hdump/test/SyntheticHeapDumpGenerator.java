package io.jafar.hdump.test;

import io.jafar.hdump.internal.BasicType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates synthetic HPROF heap dumps for testing parser spec compliance.
 *
 * <p>Creates valid heap dumps covering all HPROF format elements for systematic verification
 * against the OpenJDK specification.
 */
public class SyntheticHeapDumpGenerator {

  private static final int ID_SIZE = 8; // 64-bit addresses

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

  /**
   * Test Suite A1: Minimal heap dump - single object, minimal fields.
   *
   * <p>Verifies basic parsing infrastructure.
   */
  public static void generateA1Minimal(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "java/lang/Object");
      writeLoadClass(out, 1, 100);

      // Heap dump with single object
      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // CLASS_DUMP for Object
      writeClassDump(h, 100, 0, 0, 0, 0);

      // INSTANCE_DUMP - single object
      writeInstanceDump(h, 1000, 100, new byte[0]);

      // GC root
      h.writeByte(0xFF); // ROOT_UNKNOWN
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A2: All primitive array types.
   *
   * <p>Verifies PRIM_ARRAY_DUMP parsing for all BasicType codes.
   */
  public static void generateA2AllPrimitiveTypes(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "java/lang/Object");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      writeClassDump(h, 100, 0, 0, 0, 0);

      // Create arrays for each primitive type
      int objectId = 1000;

      // boolean[] - type 4
      writePrimArrayDump(h, objectId++, BasicType.BOOLEAN.code, new byte[] {1, 0, 1});

      // char[] - type 5
      writePrimArrayDump(
          h,
          objectId++,
          BasicType.CHAR.code,
          new byte[] {0, 65, 0, 66, 0, 67}); // 'A', 'B', 'C'

      // float[] - type 6
      writePrimArrayDump(h, objectId++, BasicType.FLOAT.code, floatArrayBytes(1.0f, 2.0f));

      // double[] - type 7
      writePrimArrayDump(h, objectId++, BasicType.DOUBLE.code, doubleArrayBytes(1.0, 2.0));

      // byte[] - type 8
      writePrimArrayDump(h, objectId++, BasicType.BYTE.code, new byte[] {1, 2, 3});

      // short[] - type 9
      writePrimArrayDump(
          h, objectId++, BasicType.SHORT.code, new byte[] {0, 1, 0, 2, 0, 3}); // 1, 2, 3

      // int[] - type 10
      writePrimArrayDump(
          h,
          objectId++,
          BasicType.INT.code,
          new byte[] {0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3}); // 1, 2, 3

      // long[] - type 11
      writePrimArrayDump(
          h,
          objectId++,
          BasicType.LONG.code,
          new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2}); // 1L, 2L

      // GC roots for all arrays
      for (int i = 1000; i < objectId; i++) {
        h.writeByte(0xFF); // ROOT_UNKNOWN
        h.writeLong(i);
      }

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A3: All GC root types (standard HPROF 1.0.2).
   *
   * <p>Verifies all GC root parsing methods.
   */
  public static void generateA3AllGcRoots(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "java/lang/Object");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      writeClassDump(h, 100, 0, 0, 0, 0);

      // Create objects for each root type
      for (int i = 0; i < 9; i++) {
        writeInstanceDump(h, 1000 + i, 100, new byte[0]);
      }

      // ROOT_UNKNOWN (0xFF)
      h.writeByte(0xFF);
      h.writeLong(1000);

      // ROOT_JNI_GLOBAL (0x01)
      h.writeByte(0x01);
      h.writeLong(1001); // object ID
      h.writeLong(5000); // JNI global ref ID

      // ROOT_JNI_LOCAL (0x02)
      h.writeByte(0x02);
      h.writeLong(1002); // object ID
      h.writeInt(1); // thread serial
      h.writeInt(0); // frame number

      // ROOT_JAVA_FRAME (0x03)
      h.writeByte(0x03);
      h.writeLong(1003); // object ID
      h.writeInt(1); // thread serial
      h.writeInt(2); // frame number

      // ROOT_NATIVE_STACK (0x04)
      h.writeByte(0x04);
      h.writeLong(1004); // object ID
      h.writeInt(1); // thread serial

      // ROOT_STICKY_CLASS (0x05)
      h.writeByte(0x05);
      h.writeLong(1005); // object ID

      // ROOT_THREAD_BLOCK (0x06)
      h.writeByte(0x06);
      h.writeLong(1006); // object ID
      h.writeInt(1); // thread serial

      // ROOT_MONITOR_USED (0x07)
      h.writeByte(0x07);
      h.writeLong(1007); // object ID

      // ROOT_THREAD_OBJ (0x08)
      h.writeByte(0x08);
      h.writeLong(1008); // thread object ID
      h.writeInt(1); // thread serial
      h.writeInt(100); // stack trace serial

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A4: Complex object graph with references.
   *
   * <p>Verifies reference extraction and graph traversal.
   */
  public static void generateA4ComplexReferences(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "Node");
      writeUtf8String(out, 2, "next");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Node class with 'next' field
      h.writeByte(0x20); // CLASS_DUMP
      h.writeLong(100); // class ID
      h.writeInt(0); // stack trace
      h.writeLong(0); // super
      h.writeLong(0); // loader
      h.writeLong(0); // signers
      h.writeLong(0); // protection domain
      h.writeLong(0); // reserved
      h.writeLong(0); // reserved
      h.writeInt(8); // instance size
      h.writeShort(0); // constant pool size
      h.writeShort(0); // static fields
      h.writeShort(1); // instance field count
      h.writeLong(2); // field name "next"
      h.writeByte(BasicType.OBJECT.code); // field type

      // Create chain: 1000 -> 1001 -> 1002 -> 1003 -> null
      for (int i = 0; i < 4; i++) {
        long nextId = (i < 3) ? (1001 + i) : 0; // Last one has null
        ByteArrayOutputStream fieldData = new ByteArrayOutputStream();
        new DataOutputStream(fieldData).writeLong(nextId);
        writeInstanceDump(h, 1000 + i, 100, fieldData.toByteArray());
      }

      // GC root pointing to head
      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A5: Objects with instance field references.
   *
   * <p>Critical for testing InboundIndexBuilder reference extraction.
   */
  public static void generateA5InstanceFields(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "Holder");
      writeUtf8String(out, 2, "ref1");
      writeUtf8String(out, 3, "ref2");
      writeUtf8String(out, 4, "Target");
      writeLoadClass(out, 1, 100); // Holder
      writeLoadClass(out, 4, 101); // Target

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Holder class with two reference fields
      h.writeByte(0x20);
      h.writeLong(100);
      h.writeInt(0);
      h.writeLong(0); // super
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeInt(16); // instance size (2 references)
      h.writeShort(0); // constant pool
      h.writeShort(0); // static fields
      h.writeShort(2); // instance fields
      h.writeLong(2); // ref1
      h.writeByte(BasicType.OBJECT.code);
      h.writeLong(3); // ref2
      h.writeByte(BasicType.OBJECT.code);

      // Target class (no fields)
      writeClassDump(h, 101, 0, 0, 0, 0);

      // Target objects
      writeInstanceDump(h, 2000, 101, new byte[0]);
      writeInstanceDump(h, 2001, 101, new byte[0]);

      // Holder object with references to both targets
      ByteArrayOutputStream fieldData = new ByteArrayOutputStream();
      DataOutputStream fd = new DataOutputStream(fieldData);
      fd.writeLong(2000); // ref1
      fd.writeLong(2001); // ref2
      writeInstanceDump(h, 1000, 100, fieldData.toByteArray());

      // GC root
      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A6: Classes with static field references.
   *
   * <p>Verifies static field parsing with values.
   */
  public static void generateA6StaticFields(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "ClassWithStatics");
      writeUtf8String(out, 2, "CONSTANT");
      writeUtf8String(out, 3, "TARGET_OBJ");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Target object
      writeClassDump(h, 101, 0, 0, 0, 0);
      writeInstanceDump(h, 2000, 101, new byte[0]);

      // Class with static fields
      h.writeByte(0x20);
      h.writeLong(100);
      h.writeInt(0);
      h.writeLong(0); // super
      h.writeLong(0); // loader
      h.writeLong(0); // signers
      h.writeLong(0); // protection domain
      h.writeLong(0); // reserved
      h.writeLong(0); // reserved
      h.writeInt(0); // instance size
      h.writeShort(0); // constant pool
      h.writeShort(2); // static field count
      h.writeLong(2); // field name "CONSTANT"
      h.writeByte(BasicType.INT.code);
      h.writeInt(42); // value
      h.writeLong(3); // field name "TARGET_OBJ"
      h.writeByte(BasicType.OBJECT.code);
      h.writeLong(2000); // reference to target
      h.writeShort(0); // instance field count

      // GC root for class
      h.writeByte(0x05); // ROOT_STICKY_CLASS
      h.writeLong(100);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite A7: Class hierarchy with field shadowing.
   *
   * <p>Verifies superclass field handling.
   */
  public static void generateA7ClassHierarchy(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "Base");
      writeUtf8String(out, 2, "Derived");
      writeUtf8String(out, 3, "baseField");
      writeUtf8String(out, 4, "derivedField");
      writeLoadClass(out, 1, 100); // Base
      writeLoadClass(out, 2, 101); // Derived

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Base class
      h.writeByte(0x20);
      h.writeLong(100);
      h.writeInt(0);
      h.writeLong(0); // no super
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeInt(4); // instance size
      h.writeShort(0);
      h.writeShort(0);
      h.writeShort(1); // instance field
      h.writeLong(3); // baseField
      h.writeByte(BasicType.INT.code);

      // Derived class (extends Base)
      h.writeByte(0x20);
      h.writeLong(101);
      h.writeInt(0);
      h.writeLong(100); // super = Base
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeInt(8); // instance size (4 from base + 4 own)
      h.writeShort(0);
      h.writeShort(0);
      h.writeShort(1); // instance field
      h.writeLong(4); // derivedField
      h.writeByte(BasicType.INT.code);

      // Instance of Derived with both fields
      ByteArrayOutputStream fieldData = new ByteArrayOutputStream();
      DataOutputStream fd = new DataOutputStream(fieldData);
      fd.writeInt(100); // baseField value
      fd.writeInt(200); // derivedField value
      writeInstanceDump(h, 1000, 101, fieldData.toByteArray());

      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite B1: Zero-length arrays.
   *
   * <p>Verifies edge case handling for empty arrays.
   */
  public static void generateB1ZeroLengthArrays(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "java/lang/Object");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      writeClassDump(h, 100, 0, 0, 0, 0);

      // Zero-length object array
      writeObjArrayDump(h, 1000, 100, new long[0]);

      // Zero-length primitive arrays
      writePrimArrayDump(h, 1001, BasicType.INT.code, new byte[0]);
      writePrimArrayDump(h, 1002, BasicType.BYTE.code, new byte[0]);

      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite B2: Null references.
   *
   * <p>Verifies null (ID=0) handling in fields and arrays.
   */
  public static void generateB2NullReferences(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "Holder");
      writeUtf8String(out, 2, "ref");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Holder with one reference field
      h.writeByte(0x20);
      h.writeLong(100);
      h.writeInt(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeInt(8);
      h.writeShort(0);
      h.writeShort(0);
      h.writeShort(1);
      h.writeLong(2); // field name
      h.writeByte(BasicType.OBJECT.code);

      // Instance with null field
      ByteArrayOutputStream fieldData = new ByteArrayOutputStream();
      new DataOutputStream(fieldData).writeLong(0); // null reference
      writeInstanceDump(h, 1000, 100, fieldData.toByteArray());

      // Array with null elements
      writeObjArrayDump(h, 2000, 100, new long[] {0, 0, 0});

      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite B3: Circular references.
   *
   * <p>Verifies handling of self-references and cycles.
   */
  public static void generateB3CircularReferences(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "Node");
      writeUtf8String(out, 2, "next");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      // Node class
      h.writeByte(0x20);
      h.writeLong(100);
      h.writeInt(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeLong(0);
      h.writeInt(8);
      h.writeShort(0);
      h.writeShort(0);
      h.writeShort(1);
      h.writeLong(2);
      h.writeByte(BasicType.OBJECT.code);

      // Self-referencing object: 1000 -> 1000
      ByteArrayOutputStream field1 = new ByteArrayOutputStream();
      new DataOutputStream(field1).writeLong(1000); // points to self
      writeInstanceDump(h, 1000, 100, field1.toByteArray());

      // Cycle: 2000 -> 2001 -> 2000
      ByteArrayOutputStream field2 = new ByteArrayOutputStream();
      new DataOutputStream(field2).writeLong(2001);
      writeInstanceDump(h, 2000, 100, field2.toByteArray());

      ByteArrayOutputStream field3 = new ByteArrayOutputStream();
      new DataOutputStream(field3).writeLong(2000);
      writeInstanceDump(h, 2001, 100, field3.toByteArray());

      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  /**
   * Test Suite B4: Large arrays.
   *
   * <p>Verifies handling of arrays with varying sizes.
   */
  public static void generateB4LargeArrays(Path outputPath) throws IOException {
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
      writeHeader(out);

      writeUtf8String(out, 1, "java/lang/Object");
      writeLoadClass(out, 1, 100);

      ByteArrayOutputStream heap = new ByteArrayOutputStream();
      DataOutputStream h = new DataOutputStream(heap);

      writeClassDump(h, 100, 0, 0, 0, 0);

      // Arrays of different sizes: 1, 1000, 100000
      long[] smallArray = {1001};
      long[] mediumArray = new long[1000];
      for (int i = 0; i < 1000; i++) mediumArray[i] = 2000 + i;

      writeObjArrayDump(h, 1000, 100, smallArray);
      writeObjArrayDump(h, 3000, 100, mediumArray);

      // Large primitive array
      byte[] largeBytes = new byte[100000];
      writePrimArrayDump(h, 4000, BasicType.BYTE.code, largeBytes);

      h.writeByte(0xFF);
      h.writeLong(1000);

      writeHeapDumpSegment(out, heap.toByteArray());
    }
  }

  // ===== Helper Methods =====

  private static void writeHeader(DataOutputStream out) throws IOException {
    out.write("JAVA PROFILE 1.0.2\0".getBytes("UTF-8"));
    out.writeInt(ID_SIZE);
    out.writeLong(System.currentTimeMillis());
  }

  private static void writeUtf8String(DataOutputStream out, long id, String text)
      throws IOException {
    byte[] bytes = text.getBytes("UTF-8");
    out.writeByte(0x01); // UTF8 tag
    out.writeInt(0); // Timestamp
    out.writeInt(ID_SIZE + bytes.length); // Length
    out.writeLong(id);
    out.write(bytes);
  }

  private static void writeLoadClass(DataOutputStream out, long classNameId, long classId)
      throws IOException {
    out.writeByte(0x02); // LOAD_CLASS tag
    out.writeInt(0);
    out.writeInt(4 + ID_SIZE + 4 + ID_SIZE);
    out.writeInt(1); // Class serial
    out.writeLong(classId);
    out.writeInt(0); // Stack trace
    out.writeLong(classNameId);
  }

  private static void writeHeapDumpSegment(DataOutputStream out, byte[] heapData)
      throws IOException {
    out.writeByte(0x1C); // HEAP_DUMP_SEGMENT
    out.writeInt(0);
    out.writeInt(heapData.length);
    out.write(heapData);
  }

  private static void writeClassDump(
      DataOutputStream out,
      long classId,
      long superId,
      long loaderId,
      int constantPoolSize,
      int instanceSize)
      throws IOException {
    out.writeByte(0x20); // CLASS_DUMP
    out.writeLong(classId);
    out.writeInt(0); // stack trace
    out.writeLong(superId);
    out.writeLong(loaderId);
    out.writeLong(0); // signers
    out.writeLong(0); // protection domain
    out.writeLong(0); // reserved
    out.writeLong(0); // reserved
    out.writeInt(instanceSize);
    out.writeShort(constantPoolSize);
    out.writeShort(0); // static fields
    out.writeShort(0); // instance fields
  }

  private static void writeInstanceDump(
      DataOutputStream out, long objectId, long classId, byte[] fieldData) throws IOException {
    out.writeByte(0x21); // INSTANCE_DUMP
    out.writeLong(objectId);
    out.writeInt(0); // stack trace
    out.writeLong(classId);
    out.writeInt(fieldData.length);
    out.write(fieldData);
  }

  private static void writeObjArrayDump(
      DataOutputStream out, long arrayId, long arrayClassId, long[] elements)
      throws IOException {
    out.writeByte(0x22); // OBJ_ARRAY_DUMP
    out.writeLong(arrayId);
    out.writeInt(0); // stack trace
    out.writeInt(elements.length);
    out.writeLong(arrayClassId);
    for (long element : elements) {
      out.writeLong(element);
    }
  }

  private static void writePrimArrayDump(
      DataOutputStream out, long arrayId, int elementType, byte[] data) throws IOException {
    out.writeByte(0x23); // PRIM_ARRAY_DUMP
    out.writeLong(arrayId);
    out.writeInt(0); // stack trace
    out.writeInt(data.length / getElementSize(elementType)); // element count
    out.writeByte(elementType);
    out.write(data);
  }

  private static int getElementSize(int basicType) {
    return switch (basicType) {
      case 4, 8 -> 1; // BOOLEAN, BYTE
      case 5, 9 -> 2; // CHAR, SHORT
      case 6, 10 -> 4; // FLOAT, INT
      case 7, 11 -> 8; // DOUBLE, LONG
      default -> 1;
    };
  }

  private static byte[] floatArrayBytes(float... values) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    for (float v : values) {
      dos.writeFloat(v);
    }
    return bos.toByteArray();
  }

  private static byte[] doubleArrayBytes(double... values) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    for (double v : values) {
      dos.writeDouble(v);
    }
    return bos.toByteArray();
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
