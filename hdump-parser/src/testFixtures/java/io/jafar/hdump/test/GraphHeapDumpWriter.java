package io.jafar.hdump.test;

import io.jafar.hdump.internal.BasicType;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writes an HPROF file from an explicit object-graph description.
 *
 * <p>Nodes are numbered {@code 0..nodeCount()-1} in creation order. Every node has a fixed number
 * of reference <em>slots</em> (fields of an instance, elements of an object array); a slot holds a
 * node, {@code null} (the default) or a dangling id (an id with no object behind it).
 *
 * <p>Node kinds:
 *
 * <ul>
 *   <li>{@link Kind#INSTANCE}: instance of {@code test/Node}, declaring {@code instanceFieldCount}
 *       object fields {@code f0..} and an {@code int value}.
 *   <li>{@link Kind#OBJ_ARRAY}: {@code Object[]} of a given length.
 *   <li>{@link Kind#BYTE_ARRAY}: {@code byte[]} of a given length (no outbound edges).
 *   <li>{@link Kind#WEAK_REF}: instance of {@code java/lang/ref/WeakReference}, whose superclass
 *       {@code java/lang/ref/Reference} declares object fields {@code referent, queue, next,
 *       discovered} (slots 0..3). Slots 0 and 3 are not strong edges.
 * </ul>
 *
 * <p>Output is streamed through a {@link BufferedOutputStream}; heap data is split into multiple
 * {@code HEAP_DUMP_SEGMENT} records once a segment reaches the configured size, so graphs with
 * millions of nodes do not need to be held in memory as a byte array. Only the graph description
 * itself (a few ints per node and per slot) is retained.
 *
 * <p>Object ids are {@link #objectId(int)}; ID size is 8.
 */
public final class GraphHeapDumpWriter {

  public enum Kind {
    INSTANCE,
    OBJ_ARRAY,
    BYTE_ARRAY,
    WEAK_REF
  }

  /** GC root record types; the value is the HPROF sub-record tag. */
  public enum RootType {
    UNKNOWN(0xFF),
    JNI_GLOBAL(0x01),
    JNI_LOCAL(0x02),
    JAVA_FRAME(0x03),
    NATIVE_STACK(0x04),
    STICKY_CLASS(0x05),
    THREAD_BLOCK(0x06),
    MONITOR_USED(0x07),
    THREAD_OBJ(0x08);

    final int tag;

    RootType(int tag) {
      this.tag = tag;
    }
  }

  /** Slot value: null reference. */
  public static final int NULL = -1;

  /** Slot value: reference to an id for which no object exists. */
  public static final int DANGLING = -2;

  /** Slot numbers of {@code java.lang.ref.Reference} fields. */
  public static final int REF_REFERENT = 0, REF_QUEUE = 1, REF_NEXT = 2, REF_DISCOVERED = 3;

  /** Default segment size: HEAP_DUMP_SEGMENT length is u4, so keep well below 4GB. */
  public static final int DEFAULT_SEGMENT_BYTES = 16 * 1024 * 1024;

  private static final int ID_SIZE = 8;
  private static final long OBJECT_ID_BASE = 1L << 20;
  private static final long OBJECT_ID_STEP = 16;
  private static final long DANGLING_ID_BASE = 1L << 50;

  // class ids
  private static final long C_OBJECT = 10,
      C_NODE = 11,
      C_OBJ_ARRAY = 12,
      C_REFERENCE = 13,
      C_WEAK_REF = 14,
      C_STATICS = 15;

  // string ids (field names for instance fields are FIELD_NAME_BASE + i)
  private static final long S_OBJECT = 1,
      S_NODE = 2,
      S_OBJ_ARRAY = 3,
      S_REFERENCE = 4,
      S_WEAK_REF = 5,
      S_STATICS = 6,
      S_REFERENT = 7,
      S_QUEUE = 8,
      S_NEXT = 9,
      S_DISCOVERED = 10,
      S_VALUE = 11,
      S_STATIC_INT = 12,
      FIELD_NAME_BASE = 1000,
      STATIC_NAME_BASE = 1L << 32;

  private final int instanceFieldCount;

  private byte[] kinds = new byte[16];
  private int[] slotStart = new int[17];
  private int[] bytesLen = new int[16]; // byte array length
  private int nodeCount;

  private int[] slots = new int[64];
  private int slotCount;
  private int[] fillCursor = new int[16]; // next free slot via addEdge

  private int[] rootNodes = new int[8];
  private byte[] rootTypes = new byte[8];
  private int rootCount;

  private int[] staticTargets = new int[4];
  private int staticCount;

  public GraphHeapDumpWriter(int instanceFieldCount) {
    if (instanceFieldCount < 0) throw new IllegalArgumentException();
    this.instanceFieldCount = instanceFieldCount;
  }

  // ------------------------------------------------------------------ description

  public int addInstance() {
    return addNode(Kind.INSTANCE, instanceFieldCount, 0);
  }

  public int addObjectArray(int length) {
    return addNode(Kind.OBJ_ARRAY, length, 0);
  }

  public int addByteArray(int length) {
    return addNode(Kind.BYTE_ARRAY, 0, length);
  }

  public int addWeakReference() {
    return addNode(Kind.WEAK_REF, 4, 0);
  }

  private int addNode(Kind kind, int slotCnt, int byteLen) {
    if (nodeCount == kinds.length) {
      int n = kinds.length * 2;
      kinds = Arrays.copyOf(kinds, n);
      bytesLen = Arrays.copyOf(bytesLen, n);
      fillCursor = Arrays.copyOf(fillCursor, n);
      slotStart = Arrays.copyOf(slotStart, n + 1);
    }
    int node = nodeCount++;
    kinds[node] = (byte) kind.ordinal();
    bytesLen[node] = byteLen;
    slotStart[node] = slotCount;
    slotStart[node + 1] = slotCount + slotCnt;
    if (slotCount + slotCnt > slots.length) {
      slots = Arrays.copyOf(slots, Math.max(slots.length * 2, slotCount + slotCnt));
    }
    Arrays.fill(slots, slotCount, slotCount + slotCnt, NULL);
    slotCount += slotCnt;
    return node;
  }

  /**
   * Sets slot {@code slot} of {@code from} to {@code to} (a node, {@link #NULL}, {@link
   * #DANGLING}).
   */
  public void setSlot(int from, int slot, int to) {
    if (slot < 0 || slot >= slotCountOf(from)) {
      throw new IllegalArgumentException("slot " + slot + " out of range for node " + from);
    }
    slots[slotStart[from] + slot] = to;
  }

  /** Stores {@code to} in the next free slot of {@code from} (slots are filled in order). */
  public void addEdge(int from, int to) {
    // for weak refs, plain edges go to 'queue'/'next' (slots 1,2): skip referent/discovered
    int cnt = slotCountOf(from);
    int cur = fillCursor[from];
    if (kind(from) == Kind.WEAK_REF) {
      // order of free slots: queue, next
      int[] order = {REF_QUEUE, REF_NEXT};
      if (cur >= order.length) throw new IllegalStateException("no free slot on node " + from);
      setSlot(from, order[cur], to);
    } else {
      if (cur >= cnt) throw new IllegalStateException("no free slot on node " + from);
      setSlot(from, cur, to);
    }
    fillCursor[from] = cur + 1;
  }

  public void addDanglingEdge(int from) {
    addEdge(from, DANGLING);
  }

  public void addRoot(int node, RootType type) {
    if (node < 0 || node >= nodeCount) throw new IllegalArgumentException();
    ensureRoot();
    rootNodes[rootCount] = node;
    rootTypes[rootCount++] = (byte) type.ordinal();
  }

  /** A root record for an id without an object. Parsers must ignore it. */
  public void addDanglingRoot(RootType type) {
    ensureRoot();
    rootNodes[rootCount] = DANGLING;
    rootTypes[rootCount++] = (byte) type.ordinal();
  }

  private void ensureRoot() {
    if (rootCount == rootNodes.length) {
      rootNodes = Arrays.copyOf(rootNodes, rootCount * 2);
      rootTypes = Arrays.copyOf(rootTypes, rootCount * 2);
    }
  }

  /**
   * Adds a static object field (on class {@code test/Statics}) pointing at {@code target}. Class
   * {@code test/Statics} is not itself a GC root.
   */
  public void addStaticRef(int target) {
    if (staticCount == staticTargets.length) {
      staticTargets = Arrays.copyOf(staticTargets, staticCount * 2);
    }
    staticTargets[staticCount++] = target;
  }

  // ------------------------------------------------------------------ introspection

  public int nodeCount() {
    return nodeCount;
  }

  public Kind kind(int node) {
    return Kind.values()[kinds[node]];
  }

  public int slotCountOf(int node) {
    return slotStart[node + 1] - slotStart[node];
  }

  public int slot(int node, int slot) {
    return slots[slotStart[node] + slot];
  }

  public int[] rootNodes() {
    int n = 0;
    for (int i = 0; i < rootCount; i++) if (rootNodes[i] >= 0) n++;
    int[] r = new int[n];
    n = 0;
    for (int i = 0; i < rootCount; i++) if (rootNodes[i] >= 0) r[n++] = rootNodes[i];
    return r;
  }

  public int[] staticTargets() {
    int n = 0;
    for (int i = 0; i < staticCount; i++) if (staticTargets[i] >= 0) n++;
    int[] r = new int[n];
    n = 0;
    for (int i = 0; i < staticCount; i++) if (staticTargets[i] >= 0) r[n++] = staticTargets[i];
    return r;
  }

  /**
   * Strong adjacency as the parser is expected to see it: slot targets that are real nodes (null
   * and dangling dropped), minus {@code referent}/{@code discovered} of weak references. Duplicates
   * and self-loops are kept; order is slot order.
   */
  public int[][] strongAdjacency() {
    int[][] adj = new int[nodeCount][];
    for (int v = 0; v < nodeCount; v++) {
      int cnt = slotCountOf(v);
      int[] tmp = new int[cnt];
      int k = 0;
      boolean weak = kind(v) == Kind.WEAK_REF;
      for (int s = 0; s < cnt; s++) {
        if (weak && (s == REF_REFERENT || s == REF_DISCOVERED)) continue;
        int t = slot(v, s);
        if (t >= 0) tmp[k++] = t;
      }
      adj[v] = Arrays.copyOf(tmp, k);
    }
    return adj;
  }

  public static long objectId(int node) {
    return OBJECT_ID_BASE + node * OBJECT_ID_STEP;
  }

  /** Inverse of {@link #objectId(int)}; -1 if the id is not a node id. */
  public static int nodeOfId(long id) {
    long d = id - OBJECT_ID_BASE;
    if (d < 0 || d % OBJECT_ID_STEP != 0) return -1;
    return (int) (d / OBJECT_ID_STEP);
  }

  private static long refId(int slotValue) {
    if (slotValue >= 0) return objectId(slotValue);
    if (slotValue == DANGLING) return DANGLING_ID_BASE;
    return 0;
  }

  // ------------------------------------------------------------------ writing

  public void write(Path path) throws IOException {
    write(path, DEFAULT_SEGMENT_BYTES);
  }

  /**
   * Writes the dump. A new HEAP_DUMP_SEGMENT is started once the current one has at least {@code
   * segmentBytes} bytes (checked between sub-records, so a segment may exceed it by one record).
   */
  public void write(Path path, int segmentBytes) throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 20))) {
      writeHeader(out);
      writeString(out, S_OBJECT, "java/lang/Object");
      writeString(out, S_NODE, "test/Node");
      writeString(out, S_OBJ_ARRAY, "[Ljava/lang/Object;");
      writeString(out, S_REFERENCE, "java/lang/ref/Reference");
      writeString(out, S_WEAK_REF, "java/lang/ref/WeakReference");
      writeString(out, S_STATICS, "test/Statics");
      writeString(out, S_REFERENT, "referent");
      writeString(out, S_QUEUE, "queue");
      writeString(out, S_NEXT, "next");
      writeString(out, S_DISCOVERED, "discovered");
      writeString(out, S_VALUE, "value");
      writeString(out, S_STATIC_INT, "CONSTANT");
      for (int i = 0; i < instanceFieldCount; i++) {
        writeString(out, FIELD_NAME_BASE + i, "f" + i);
      }
      for (int i = 0; i < staticCount; i++) {
        writeString(out, STATIC_NAME_BASE + i, "s" + i);
      }
      writeLoadClass(out, 1, S_OBJECT, C_OBJECT);
      writeLoadClass(out, 2, S_NODE, C_NODE);
      writeLoadClass(out, 3, S_OBJ_ARRAY, C_OBJ_ARRAY);
      writeLoadClass(out, 4, S_REFERENCE, C_REFERENCE);
      writeLoadClass(out, 5, S_WEAK_REF, C_WEAK_REF);
      writeLoadClass(out, 6, S_STATICS, C_STATICS);

      Segments seg = new Segments(out, segmentBytes);
      DataOutputStream h = seg.data;

      // classes
      classDump(h, C_OBJECT, 0, 0, null, null, null);
      long[] nodeFields = new long[instanceFieldCount + 1];
      int[] nodeTypes = new int[instanceFieldCount + 1];
      for (int i = 0; i < instanceFieldCount; i++) {
        nodeFields[i] = FIELD_NAME_BASE + i;
        nodeTypes[i] = BasicType.OBJECT;
      }
      nodeFields[instanceFieldCount] = S_VALUE;
      nodeTypes[instanceFieldCount] = BasicType.INT;
      classDump(h, C_NODE, C_OBJECT, instanceFieldCount * ID_SIZE + 4, nodeFields, nodeTypes, null);
      classDump(h, C_OBJ_ARRAY, C_OBJECT, 0, null, null, null);
      classDump(
          h,
          C_REFERENCE,
          C_OBJECT,
          4 * ID_SIZE,
          new long[] {S_REFERENT, S_QUEUE, S_NEXT, S_DISCOVERED},
          new int[] {2, 2, 2, 2},
          null);
      classDump(h, C_WEAK_REF, C_REFERENCE, 0, null, null, null);
      // statics class: int CONSTANT then object refs s0..
      classDump(h, C_STATICS, C_OBJECT, 0, null, null, this);
      seg.maybeFlush();

      for (int v = 0; v < nodeCount; v++) {
        switch (kind(v)) {
          case INSTANCE -> {
            h.writeByte(0x21);
            h.writeLong(objectId(v));
            h.writeInt(0);
            h.writeLong(C_NODE);
            h.writeInt(instanceFieldCount * ID_SIZE + 4);
            for (int s = 0; s < instanceFieldCount; s++) h.writeLong(refId(slot(v, s)));
            h.writeInt(v);
          }
          case WEAK_REF -> {
            h.writeByte(0x21);
            h.writeLong(objectId(v));
            h.writeInt(0);
            h.writeLong(C_WEAK_REF);
            h.writeInt(4 * ID_SIZE);
            for (int s = 0; s < 4; s++) h.writeLong(refId(slot(v, s)));
          }
          case OBJ_ARRAY -> {
            int len = slotCountOf(v);
            h.writeByte(0x22);
            h.writeLong(objectId(v));
            h.writeInt(0);
            h.writeInt(len);
            h.writeLong(C_OBJ_ARRAY);
            for (int s = 0; s < len; s++) h.writeLong(refId(slot(v, s)));
          }
          case BYTE_ARRAY -> {
            h.writeByte(0x23);
            h.writeLong(objectId(v));
            h.writeInt(0);
            h.writeInt(bytesLen[v]);
            h.writeByte(BasicType.BYTE);
            h.write(new byte[bytesLen[v]]);
          }
        }
        seg.maybeFlush();
      }

      RootType[] rts = RootType.values();
      for (int i = 0; i < rootCount; i++) {
        RootType t = rts[rootTypes[i]];
        long id = rootNodes[i] >= 0 ? objectId(rootNodes[i]) : DANGLING_ID_BASE + 8;
        h.writeByte(t.tag);
        h.writeLong(id);
        switch (t) {
          case JNI_GLOBAL -> h.writeLong(5000 + i);
          case JNI_LOCAL, JAVA_FRAME -> {
            h.writeInt(1);
            h.writeInt(i);
          }
          case NATIVE_STACK, THREAD_BLOCK -> h.writeInt(1);
          case THREAD_OBJ -> {
            h.writeInt(1);
            h.writeInt(100);
          }
          default -> {}
        }
        seg.maybeFlush();
      }
      seg.flush();
      out.writeByte(0x2C); // HEAP_DUMP_END
      out.writeInt(0);
      out.writeInt(0);
    }
  }

  private static void classDump(
      DataOutputStream h,
      long classId,
      long superId,
      int instanceSize,
      long[] fieldNames,
      int[] fieldTypes,
      GraphHeapDumpWriter statics)
      throws IOException {
    h.writeByte(0x20);
    h.writeLong(classId);
    h.writeInt(0);
    h.writeLong(superId);
    h.writeLong(0);
    h.writeLong(0);
    h.writeLong(0);
    h.writeLong(0);
    h.writeLong(0);
    h.writeInt(instanceSize);
    h.writeShort(0); // constant pool
    if (statics == null) {
      h.writeShort(0);
    } else {
      h.writeShort(1 + statics.staticCount);
      h.writeLong(S_STATIC_INT);
      h.writeByte(BasicType.INT);
      h.writeInt(42);
      for (int i = 0; i < statics.staticCount; i++) {
        h.writeLong(STATIC_NAME_BASE + i);
        h.writeByte(BasicType.OBJECT);
        h.writeLong(refId(statics.staticTargets[i]));
      }
    }
    int fc = fieldNames == null ? 0 : fieldNames.length;
    h.writeShort(fc);
    for (int i = 0; i < fc; i++) {
      h.writeLong(fieldNames[i]);
      h.writeByte(fieldTypes[i]);
    }
  }

  /** Accumulates heap sub-records and emits them as HEAP_DUMP_SEGMENT records. */
  private static final class Segments {
    private final DataOutputStream out;
    private final int limit;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
    final DataOutputStream data = new DataOutputStream(buf);

    Segments(DataOutputStream out, int limit) {
      this.out = out;
      this.limit = limit;
    }

    void maybeFlush() throws IOException {
      if (buf.size() >= limit) flush();
    }

    void flush() throws IOException {
      if (buf.size() == 0) return;
      out.writeByte(0x1C);
      out.writeInt(0);
      out.writeInt(buf.size()); // u4; segments stay far below 2^31
      buf.writeTo(out);
      buf.reset();
    }
  }

  private static void writeHeader(DataOutputStream out) throws IOException {
    out.write("JAVA PROFILE 1.0.2\0".getBytes("UTF-8"));
    out.writeInt(ID_SIZE);
    out.writeLong(0L);
  }

  private static void writeString(DataOutputStream out, long id, String text) throws IOException {
    byte[] bytes = text.getBytes("UTF-8");
    out.writeByte(0x01);
    out.writeInt(0);
    out.writeInt(ID_SIZE + bytes.length);
    out.writeLong(id);
    out.write(bytes);
  }

  private static void writeLoadClass(DataOutputStream out, int serial, long nameId, long classId)
      throws IOException {
    out.writeByte(0x02);
    out.writeInt(0);
    out.writeInt(4 + ID_SIZE + 4 + ID_SIZE);
    out.writeInt(serial);
    out.writeLong(classId);
    out.writeInt(0);
    out.writeLong(nameId);
  }
}
