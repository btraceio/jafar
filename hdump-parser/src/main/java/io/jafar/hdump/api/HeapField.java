package io.jafar.hdump.api;

/** Represents a field in a Java class. Can be either an instance field or a static field. */
public interface HeapField {

  /** Field type constants matching HPROF basic type identifiers. */
  interface Type {
    int OBJECT = 2;
    int BOOLEAN = 4;
    int CHAR = 5;
    int FLOAT = 6;
    int DOUBLE = 7;
    int BYTE = 8;
    int SHORT = 9;
    int INT = 10;
    int LONG = 11;
  }

  /** Returns the field name. */
  String getName();

  /**
   * Returns the field type as a basic type code. Compare against {@link Type} constants.
   *
   * @see #getTypeName()
   */
  int getType();

  /**
   * Returns a human-readable name for the field type (e.g., "int", "boolean", "object").
   *
   * @return the type name
   */
  default String getTypeName() {
    return switch (getType()) {
      case Type.OBJECT -> "object";
      case Type.BOOLEAN -> "boolean";
      case Type.CHAR -> "char";
      case Type.FLOAT -> "float";
      case Type.DOUBLE -> "double";
      case Type.BYTE -> "byte";
      case Type.SHORT -> "short";
      case Type.INT -> "int";
      case Type.LONG -> "long";
      default -> "unknown";
    };
  }

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
