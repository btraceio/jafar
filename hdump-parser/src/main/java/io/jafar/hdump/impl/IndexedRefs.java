package io.jafar.hdump.impl;

import io.jafar.hdump.index.IndexFormat;
import io.jafar.hdump.index.ObjectIndexReader;
import io.jafar.hdump.internal.HprofReader;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Object-free access to the strong-reference graph of an INDEXED dump, by 32-bit object id.
 *
 * <p>Reads {@code objects.idx} entries and the reference fields straight from the mapped files — no
 * {@link HeapObjectImpl}, no LRU cache — with the per-class field offsets of {@link
 * HeapClassImpl#strongRefOffsets}. {@link #strongRefs} yields what {@link
 * HeapObjectImpl#getStrongOutboundReferenceIds()} yields for the loaded object, restricted to ids
 * that name an object (an id32), in the same order:
 *
 * <ul>
 *   <li>instances: the strong object fields, non-null, in field order ({@code referent}/{@code
 *       discovered} excluded on {@code Reference} subclasses); none when the class is unknown;
 *   <li>object arrays: the non-null elements in index order; primitive arrays: none;
 *   <li>class entries (INDEXED mode lists class objects as objects, with no data): none. A loaded
 *       class object would read its own instance layout at file offset 0, the HPROF header, whose
 *       bytes are not object ids in practice; here that is made explicit.
 * </ul>
 *
 * <p>Not thread-safe: one instance per thread (it holds a reader view and a scratch buffer).
 */
final class IndexedRefs {

  private final HeapDumpImpl dump;
  private final ObjectIndexReader index;
  private final HprofReader reader;
  private final Long2IntOpenHashMap addressToId32;
  private final Int2LongOpenHashMap classId32ToAddress;
  private final int idSize;
  private final int count;

  private HeapClassImpl[] classes = new HeapClassImpl[16]; // by class id32
  private int[][] strongOffsets = new int[16][]; // by class id32
  private boolean[] resolved = new boolean[16];

  private int[] buf = new int[16];
  private final StrongRefsIndex stored; // null: decode from the dump

  /** Decodes references from the dump's files (used to build {@link StrongRefsIndex}). */
  IndexedRefs(HeapDumpImpl dump) {
    this(dump, null);
  }

  /** Reads references from {@code stored} when given, decoding them otherwise. */
  IndexedRefs(HeapDumpImpl dump, StrongRefsIndex stored) {
    this.stored = stored == null ? null : stored.view();
    this.dump = dump;
    this.index = dump.getObjectIndexReader().view();
    this.reader = dump.getThreadLocalReader();
    this.addressToId32 = dump.getAddressToId32();
    this.classId32ToAddress = dump.getClassId32ToAddress();
    this.idSize = reader.getIdSize();
    this.count = index.getEntryCount();
  }

  /** Number of id32 values (objects and class entries). */
  int size() {
    return count;
  }

  /** The scratch buffer the last {@link #strongRefs} call filled. */
  int[] refs() {
    return buf;
  }

  /** Shallow size, by the same formula as the loaded object. */
  int shallowSize(int id32) {
    byte flags = index.flagsOf(id32);
    int dataSize = index.dataSizeOf(id32);
    if ((flags & IndexFormat.FLAG_IS_OBJECT_ARRAY) != 0) return idSize * 2 + 12 + dataSize;
    if ((flags & IndexFormat.FLAG_IS_PRIMITIVE_ARRAY) != 0) return idSize + 12 + dataSize;
    return idSize * 2 + 8 + dataSize;
  }

  /** The class of an instance or object array entry; null for primitive arrays and unknowns. */
  HeapClassImpl classOf(int id32) {
    int classId = index.classIdOf(id32);
    if (classId < 0) return null;
    ensureClass(classId);
    return classes[classId];
  }

  /** Array length, or -1 for instances and class entries. */
  int arrayLengthOf(int id32) {
    return index.arrayLengthOf(id32);
  }

  /** True for a class object's entry: not an array, no data. */
  boolean isClassEntry(int id32) {
    return index.flagsOf(id32) == 0
        && index.arrayLengthOf(id32) < 0
        && index.fileOffsetOf(id32) == 0
        && index.dataSizeOf(id32) == 0;
  }

  /**
   * Fills {@link #refs()} with the id32 of every strong reference of {@code id32} that names an
   * object, in reference order (duplicates and self references kept); returns how many.
   */
  int strongRefs(int id32) {
    if (stored != null) {
      long from = stored.start(id32);
      int k = (int) (stored.start(id32 + 1) - from);
      if (buf.length < k) buf = new int[Math.max(k, buf.length * 2)];
      for (int i = 0; i < k; i++) {
        buf[i] = stored.ref(from + i);
      }
      return k;
    }
    byte flags = index.flagsOf(id32);
    if ((flags & IndexFormat.FLAG_IS_PRIMITIVE_ARRAY) != 0) {
      return 0;
    }
    long pos = index.fileOffsetOf(id32);
    if ((flags & IndexFormat.FLAG_IS_OBJECT_ARRAY) != 0) {
      int len = index.arrayLengthOf(id32);
      if (len <= 0) return 0;
      if (buf.length < len) buf = new int[Math.max(len, buf.length * 2)];
      int k = 0;
      for (int i = 0; i < len; i++, pos += idSize) {
        k = add(reader.readIdAt(pos), k);
      }
      return k;
    }
    if (isClassEntry(id32)) {
      return 0;
    }
    int classId = index.classIdOf(id32);
    if (classId < 0) return 0;
    ensureClass(classId);
    int[] offs = strongOffsets[classId];
    if (offs == null || offs.length == 0) return 0;
    if (buf.length < offs.length) buf = new int[Math.max(offs.length, buf.length * 2)];
    int k = 0;
    for (int off : offs) {
      k = add(reader.readIdAt(pos + off), k);
    }
    return k;
  }

  private int add(long id, int k) {
    if (id != 0) {
      int t = addressToId32.get(id);
      if (t >= 0) buf[k++] = t;
    }
    return k;
  }

  private void ensureClass(int classId) {
    if (classId >= resolved.length) {
      int n = Math.max(classId + 1, resolved.length * 2);
      classes = java.util.Arrays.copyOf(classes, n);
      strongOffsets = java.util.Arrays.copyOf(strongOffsets, n);
      resolved = java.util.Arrays.copyOf(resolved, n);
    }
    if (!resolved[classId]) {
      long address = classId32ToAddress.get(classId);
      HeapClassImpl cls = address == -1 ? null : dump.getClassByIdInternal(address);
      classes[classId] = cls;
      strongOffsets[classId] = cls == null ? null : cls.strongRefOffsets(idSize);
      resolved[classId] = true;
    }
  }
}
