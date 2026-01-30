package io.jafar.hdump.api;

/** Represents a field in a Java class. Can be either an instance field or a static field. */
public interface HeapField {

  /** Returns the field name. */
  String getName();

  /**
   * Returns the field type as a basic type code. Use {@link
   * io.jafar.hdump.internal.BasicType#nameOf} to get a readable name.
   */
  int getType();

  /** Returns true if this is a static field. */
  boolean isStatic();

  /** Returns true if this field holds an object reference (not a primitive). */
  boolean isObjectRef();

  /** Returns the declaring class. */
  HeapClass getDeclaringClass();

  /**
   * For static fields, returns the field value. For instance fields, returns null (use {@link
   * HeapObject#getFieldValue} instead).
   */
  Object getStaticValue();
}
