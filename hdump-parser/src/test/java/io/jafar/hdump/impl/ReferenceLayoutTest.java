package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.internal.BasicType;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Outbound reference ids read from instance data: field offsets across mixed primitive widths, the
 * superclass part of the layout, a shadowed field name, {@code Reference.referent}/{@code
 * discovered} excluded from the strong ids, object arrays (empty, with nulls), and unsigned u4 ids
 * — for both identifier sizes.
 */
class ReferenceLayoutTest {

  // class ids
  private static final long OBJECT = 0x10, BASE = 0x11, SUB = 0x12, REF = 0x13, WEAK = 0x14;
  private static final long ARR_CLASS = 0x15;
  // object ids (one with the top bit set: must read back unsigned at idSize 4)
  private static final long SUB_OBJ = 0x100, WEAK_OBJ = 0x101, ARR = 0x102, EMPTY_ARR = 0x103;
  private static final long T1 = 0x201, T2 = 0x202, T3 = 0x203, T4 = 0x204, HIGH = 0xFFFFFFF0L;

  private int idSize;
  private DataOutputStream heap;

  @ParameterizedTest(name = "idSize={0}")
  @ValueSource(ints = {4, 8})
  void referenceIdsMatchTheInstanceLayout(int idSize, @TempDir Path dir) throws IOException {
    Path file = dir.resolve("layout-" + idSize + ".hprof");
    write(file, idSize);
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      // Sub layout (subclass first): int a, long b, obj x=T1, byte c, obj next=null, obj y=HIGH;
      // then Base: short s, obj next=T2, double d, obj z=T3
      HeapObjectImpl sub = obj(dump, SUB_OBJ);
      long[] subRefs = {T1, HIGH, T2, T3};
      assertArrayEquals(subRefs, sub.getOutboundReferenceIds(), "Sub refs");
      assertArrayEquals(subRefs, sub.getStrongOutboundReferenceIds(), "Sub strong refs");

      // Weak (extends Reference): obj extra=T4; Reference: referent=T1, queue=T2, next=null,
      // discovered=T3
      HeapObjectImpl weak = obj(dump, WEAK_OBJ);
      assertArrayEquals(new long[] {T4, T1, T2, T3}, weak.getOutboundReferenceIds(), "Weak refs");
      assertArrayEquals(
          new long[] {T4, T2}, weak.getStrongOutboundReferenceIds(), "Weak strong refs");

      assertArrayEquals(new long[] {T3, HIGH, T1}, obj(dump, ARR).getOutboundReferenceIds());
      assertArrayEquals(new long[0], obj(dump, EMPTY_ARR).getOutboundReferenceIds());
    }
  }

  private static HeapObjectImpl obj(HeapDump dump, long id) {
    HeapObjectImpl o = (HeapObjectImpl) dump.getObjectById(id).orElse(null);
    assertNotNull(o, "object 0x" + Long.toHexString(id));
    return o;
  }

  // ---- a minimal HPROF writer with a configurable identifier size ----

  private void write(Path file, int idSize) throws IOException {
    this.idSize = idSize;
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
      out.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII));
      out.writeInt(idSize);
      out.writeLong(0);
      String[] names = {
        "java/lang/Object",
        "Base",
        "Sub",
        "java/lang/ref/Reference",
        "Weak",
        "[LBase;",
        "a",
        "b",
        "x",
        "c",
        "next",
        "y",
        "s",
        "d",
        "z",
        "extra",
        "referent",
        "queue",
        "discovered"
      };
      for (int i = 0; i < names.length; i++) utf8(out, 1000 + i, names[i]);
      long[] classes = {OBJECT, BASE, SUB, REF, WEAK, ARR_CLASS};
      for (int i = 0; i < classes.length; i++) loadClass(out, i + 1, classes[i], 1000 + i);

      ByteArrayOutputStream body = new ByteArrayOutputStream();
      heap = new DataOutputStream(body);
      classDump(OBJECT, 0);
      classDump(
          BASE,
          OBJECT,
          f("s", BasicType.SHORT),
          f("next", BasicType.OBJECT),
          f("d", BasicType.DOUBLE),
          f("z", BasicType.OBJECT));
      classDump(
          SUB,
          BASE,
          f("a", BasicType.INT),
          f("b", BasicType.LONG),
          f("x", BasicType.OBJECT),
          f("c", BasicType.BYTE),
          f("next", BasicType.OBJECT),
          f("y", BasicType.OBJECT));
      classDump(
          REF,
          OBJECT,
          f("referent", BasicType.OBJECT),
          f("queue", BasicType.OBJECT),
          f("next", BasicType.OBJECT),
          f("discovered", BasicType.OBJECT));
      classDump(WEAK, REF, f("extra", BasicType.OBJECT));
      classDump(ARR_CLASS, OBJECT);
      for (long t : new long[] {T1, T2, T3, T4, HIGH}) instance(t, OBJECT, new byte[0]);

      ByteArrayOutputStream d = new ByteArrayOutputStream();
      DataOutputStream v = new DataOutputStream(d);
      v.writeInt(7); // a
      v.writeLong(8); // b
      id(v, T1); // x
      v.writeByte(9); // c
      id(v, 0); // Sub.next
      id(v, HIGH); // y
      v.writeShort(10); // s
      id(v, T2); // Base.next
      v.writeDouble(1.5); // d
      id(v, T3); // z
      instance(SUB_OBJ, SUB, d.toByteArray());

      d.reset();
      id(v, T4); // extra
      id(v, T1); // referent
      id(v, T2); // queue
      id(v, 0); // next
      id(v, T3); // discovered
      instance(WEAK_OBJ, WEAK, d.toByteArray());

      objArray(ARR, T3, 0, HIGH, 0, T1);
      objArray(EMPTY_ARR);
      heap.writeByte(0xFF); // ROOT_UNKNOWN
      id(heap, SUB_OBJ);

      byte[] bytes = body.toByteArray();
      out.writeByte(0x1C); // HEAP_DUMP_SEGMENT
      out.writeInt(0);
      out.writeInt(bytes.length);
      out.write(bytes);
      out.writeByte(0x2C); // HEAP_DUMP_END
      out.writeInt(0);
      out.writeInt(0);
    }
  }

  private record Field(String name, int type) {}

  private static Field f(String name, int type) {
    return new Field(name, type);
  }

  private void id(DataOutputStream o, long id) throws IOException {
    if (idSize == 4) o.writeInt((int) id);
    else o.writeLong(id);
  }

  private void utf8(DataOutputStream out, long id, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.writeByte(0x01);
    out.writeInt(0);
    out.writeInt(idSize + b.length);
    id(out, id);
    out.write(b);
  }

  private void loadClass(DataOutputStream out, int serial, long classId, long nameId)
      throws IOException {
    out.writeByte(0x02);
    out.writeInt(0);
    out.writeInt(4 + idSize + 4 + idSize);
    out.writeInt(serial);
    id(out, classId);
    out.writeInt(0);
    id(out, nameId);
  }

  private static final String[] FIELD_NAMES = {
    "a", "b", "x", "c", "next", "y", "s", "d", "z", "extra", "referent", "queue", "discovered"
  };

  private static long nameId(String field) {
    for (int i = 0; i < FIELD_NAMES.length; i++) {
      if (FIELD_NAMES[i].equals(field)) return 1006 + i;
    }
    throw new IllegalArgumentException(field);
  }

  private void classDump(long classId, long superId, Field... fields) throws IOException {
    heap.writeByte(0x20);
    id(heap, classId);
    heap.writeInt(0);
    id(heap, superId);
    for (int i = 0; i < 5; i++) id(heap, 0); // loader, signers, domain, reserved x2
    int size = 0;
    for (Field fl : fields) size += BasicType.sizeOf(fl.type(), idSize);
    heap.writeInt(size);
    heap.writeShort(0); // constant pool
    heap.writeShort(0); // statics
    heap.writeShort(fields.length);
    for (Field fl : fields) {
      id(heap, nameId(fl.name()));
      heap.writeByte(fl.type());
    }
  }

  private void instance(long id, long classId, byte[] data) throws IOException {
    heap.writeByte(0x21);
    id(heap, id);
    heap.writeInt(0);
    id(heap, classId);
    heap.writeInt(data.length);
    heap.write(data);
  }

  private void objArray(long id, long... elements) throws IOException {
    heap.writeByte(0x22);
    id(heap, id);
    heap.writeInt(0);
    heap.writeInt(elements.length);
    id(heap, ARR_CLASS);
    for (long e : elements) id(heap, e);
  }
}
