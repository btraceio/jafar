package io.jafar.hdump.internal;

/**
 * HPROF basic (primitive) type identifiers. These are used in CLASS_DUMP and PRIM_ARRAY_DUMP
 * records to identify the type of fields and array elements.
 */
public final class BasicType {
  private BasicType() {}

  public static final int OBJECT = 2;
  public static final int BOOLEAN = 4;
  public static final int CHAR = 5;
  public static final int FLOAT = 6;
  public static final int DOUBLE = 7;
  public static final int BYTE = 8;
  public static final int SHORT = 9;
  public static final int INT = 10;
  public static final int LONG = 11;

  /**
   * Returns the size in bytes of the given basic type.
   *
   * @param type the basic type identifier
   * @param idSize the size of object IDs in this heap dump (4 or 8)
   * @return size in bytes
   */
  public static int sizeOf(int type, int idSize) {
    return switch (type) {
      case OBJECT -> idSize;
      case BOOLEAN, BYTE -> 1;
      case CHAR, SHORT -> 2;
      case FLOAT, INT -> 4;
      case DOUBLE, LONG -> 8;
      default -> throw new IllegalArgumentException("Unknown basic type: " + type);
    };
  }

  /** Returns a human-readable name for the given basic type. */
  public static String nameOf(int type) {
    return switch (type) {
      case OBJECT -> "object";
      case BOOLEAN -> "boolean";
      case CHAR -> "char";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case BYTE -> "byte";
      case SHORT -> "short";
      case INT -> "int";
      case LONG -> "long";
      default -> "unknown(" + type + ")";
    };
  }

  /** Returns the Java class for the given basic type. */
  public static Class<?> classOf(int type) {
    return switch (type) {
      case BOOLEAN -> boolean.class;
      case CHAR -> char.class;
      case FLOAT -> float.class;
      case DOUBLE -> double.class;
      case BYTE -> byte.class;
      case SHORT -> short.class;
      case INT -> int.class;
      case LONG -> long.class;
      default -> Object.class;
    };
  }
}
