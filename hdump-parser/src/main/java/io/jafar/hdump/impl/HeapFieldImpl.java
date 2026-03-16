package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.internal.BasicType;

/** Implementation of HeapField. */
final class HeapFieldImpl implements HeapField {

  private final String name;
  private final int type;
  private final boolean isStatic;
  private final HeapClassImpl declaringClass;
  private final Object staticValue; // Only for static fields

  HeapFieldImpl(
      String name, int type, boolean isStatic, HeapClassImpl declaringClass, Object staticValue) {
    this.name = name;
    this.type = type;
    this.isStatic = isStatic;
    this.declaringClass = declaringClass;
    this.staticValue = staticValue;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public int getType() {
    return type;
  }

  @Override
  public boolean isStatic() {
    return isStatic;
  }

  @Override
  public boolean isObjectRef() {
    return type == BasicType.OBJECT;
  }

  @Override
  public HeapClass getDeclaringClass() {
    return declaringClass;
  }

  @Override
  public Object getStaticValue() {
    return isStatic ? staticValue : null;
  }

  /** Returns the size of this field's value in bytes. */
  int getValueSize(int idSize) {
    return BasicType.sizeOf(type, idSize);
  }

  @Override
  public String toString() {
    String typeName = BasicType.nameOf(type);
    return (isStatic ? "static " : "") + typeName + " " + name;
  }
}
