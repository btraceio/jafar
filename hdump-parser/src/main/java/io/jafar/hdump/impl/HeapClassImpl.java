package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.internal.BasicType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Implementation of HeapClass. */
final class HeapClassImpl implements HeapClass {

  private final long id;
  private final String name;
  private final HeapDumpImpl dump;

  private long superClassId;
  private long classLoaderId;
  private int instanceSize;
  private int instanceCount;
  private List<HeapFieldImpl> staticFields = Collections.emptyList();
  private List<HeapFieldImpl> instanceFields = Collections.emptyList();
  private int primitiveArrayType = -1; // -1 means not a primitive array

  // Cache for all instance fields including inherited (volatile for safe lazy publication)
  private volatile List<HeapField> allInstanceFieldsCache;

  // Cached flag: -1 = not computed, 0 = false, 1 = true. Volatile for safe lazy publication.
  private volatile int referenceSubclass = -1;

  HeapClassImpl(long id, String name, HeapDumpImpl dump) {
    this.id = id;
    this.name = name;
    this.dump = dump;
  }

  void setSuperClassId(long superClassId) {
    this.superClassId = superClassId;
  }

  void setClassLoaderId(long classLoaderId) {
    this.classLoaderId = classLoaderId;
  }

  void setInstanceSize(int instanceSize) {
    this.instanceSize = instanceSize;
  }

  void setStaticFields(List<HeapFieldImpl> fields) {
    this.staticFields = fields;
  }

  void setInstanceFields(List<HeapFieldImpl> fields) {
    this.instanceFields = fields;
  }

  void setPrimitiveArrayType(int type) {
    this.primitiveArrayType = type;
  }

  void incrementInstanceCount() {
    instanceCount++;
  }

  @Override
  public long getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public HeapClass getSuperClass() {
    if (superClassId == 0) return null;
    return dump.getClassByIdInternal(superClassId);
  }

  @Override
  public long getClassLoaderId() {
    return classLoaderId;
  }

  @Override
  public List<HeapField> getInstanceFields() {
    return Collections.unmodifiableList(instanceFields);
  }

  @Override
  public List<HeapField> getAllInstanceFields() {
    if (allInstanceFieldsCache == null) {
      // Build the full field list in subclass-first order matching HPROF instance data layout:
      // the HPROF writer emits fields starting from the most-derived class, then superclass fields.
      List<HeapField> all = new ArrayList<>();
      HeapClass cls = this;
      while (cls != null) {
        all.addAll(cls.getInstanceFields());
        cls = cls.getSuperClass();
      }
      allInstanceFieldsCache = Collections.unmodifiableList(all);
    }
    return allInstanceFieldsCache;
  }

  @Override
  public List<HeapField> getStaticFields() {
    return Collections.unmodifiableList(staticFields);
  }

  @Override
  public int getInstanceSize() {
    return instanceSize;
  }

  @Override
  public int getInstanceCount() {
    return instanceCount;
  }

  @Override
  public boolean isArray() {
    return name != null && name.startsWith("[");
  }

  @Override
  public boolean isPrimitiveArray() {
    return primitiveArrayType >= 0;
  }

  @Override
  public boolean isObjectArray() {
    return name != null && name.startsWith("[L");
  }

  @Override
  public HeapClass getComponentType() {
    if (!isArray()) return null;
    if (isPrimitiveArray()) return null; // No HeapClass for primitives

    if (name.startsWith("[L") && name.endsWith(";")) {
      String compName = name.substring(2, name.length() - 1);
      return dump.getClassByName(compName).orElse(null);
    }
    return null;
  }

  private static final String REFERENCE_CLASS_NAME = "java/lang/ref/Reference";

  /** Returns true if this class is {@code java.lang.ref.Reference} or a subclass of it. */
  boolean isReferenceSubclass() {
    int cached = referenceSubclass;
    if (cached == -1) {
      // Computed into a local and published once: a concurrent caller must never see a
      // provisional 0 for a Reference subclass.
      cached = 0;
      for (HeapClass c = this; c != null; c = c.getSuperClass()) {
        if (REFERENCE_CLASS_NAME.equals(c.getName())) {
          cached = 1;
          break;
        }
      }
      referenceSubclass = cached;
    }
    return cached == 1;
  }

  // -1 = not computed, 0 = no, 1 = yes
  private volatile int duplicateFieldNames = -1;

  /**
   * Returns true if two instance fields of this class (own or inherited) share a name — a subclass
   * field shadowing a superclass field. {@link HeapObject#getFieldValues()} keeps one entry per
   * name, so for such classes it is not a field-by-field view of the instance data.
   */
  boolean hasDuplicateInstanceFieldNames() {
    int cached = duplicateFieldNames;
    if (cached == -1) {
      java.util.Set<String> seen = new java.util.HashSet<>();
      cached = 0;
      for (HeapField f : getAllInstanceFields()) {
        if (!seen.add(f.getName())) {
          cached = 1;
          break;
        }
      }
      duplicateFieldNames = cached;
    }
    return cached == 1;
  }

  // Byte offsets of the object-reference fields in an instance's data, in field order
  // (getAllInstanceFields). Computed on first use, like allInstanceFieldsCache; not keyed by
  // idSize, which is fixed for the dump a class belongs to. Shared arrays: callers must not modify.
  private volatile int[] refOffsets;
  // As refOffsets, without referent/discovered when this is a Reference subclass.
  private volatile int[] strongRefOffsets;

  /** Offsets of all object-reference fields within the instance data. */
  int[] refOffsets(int idSize) {
    int[] offs = refOffsets;
    if (offs == null) {
      offs = computeRefOffsets(idSize, false);
      refOffsets = offs;
    }
    return offs;
  }

  /**
   * Offsets of the strong object-reference fields: all of them, except {@code referent} and {@code
   * discovered} on {@code java.lang.ref.Reference} subclasses.
   */
  int[] strongRefOffsets(int idSize) {
    int[] offs = strongRefOffsets;
    if (offs == null) {
      offs = isReferenceSubclass() ? computeRefOffsets(idSize, true) : refOffsets(idSize);
      strongRefOffsets = offs;
    }
    return offs;
  }

  private int[] computeRefOffsets(int idSize, boolean strongOnly) {
    List<HeapField> all = getAllInstanceFields();
    int[] tmp = new int[all.size()];
    int count = 0;
    int offset = 0;
    for (HeapField f : all) {
      int type = f.getType();
      if (type == BasicType.OBJECT) {
        String n = f.getName();
        if (!strongOnly || !("referent".equals(n) || "discovered".equals(n))) {
          tmp[count++] = offset;
        }
      }
      offset += BasicType.sizeOf(type, idSize);
    }
    return count == tmp.length ? tmp : java.util.Arrays.copyOf(tmp, count);
  }

  @Override
  public String toString() {
    return "HeapClass[" + name + "]";
  }
}
