package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapField;
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

  // Cache for all instance fields including inherited (lazily populated)
  private List<HeapField> allInstanceFieldsCache;

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
      // Lazily build and cache the full field list
      List<HeapField> all = new ArrayList<>();
      HeapClass cls = this;
      while (cls != null) {
        all.addAll(0, cls.getInstanceFields()); // Add superclass fields first
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

  @Override
  public String toString() {
    return "HeapClass[" + name + "]";
  }
}
