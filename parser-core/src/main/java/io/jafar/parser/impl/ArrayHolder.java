package io.jafar.parser.impl;

import io.jafar.parser.api.ArrayType;
import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * Implementation of ArrayType that holds array data from JFR recordings.
 *
 * <p>This class provides a concrete implementation for storing and managing array values parsed
 * from JFR data, supporting all primitive types and objects.
 *
 * <p>The backing array type follows the element type, except that JFR {@code int} elements are
 * stored in a {@code long[]}: the untyped value model represents every {@code int} as a {@code
 * long}, matching scalar {@code int} fields (which surface as {@link Long}).
 */
public final class ArrayHolder implements ArrayType {
  /** The type name of the array elements. */
  private final String type;

  /** The actual array object. */
  private final Object array;

  /** The current index for adding elements. */
  private int index = 0;

  /**
   * Constructs a new ArrayHolder with the specified type and length.
   *
   * @param type the type name of the array elements
   * @param len the length of the array to create
   */
  ArrayHolder(String type, int len) {
    this.type = type;
    switch (type) {
      case "short":
        array = new short[len];
        break;
      case "char":
        array = new char[len];
        break;
      case "int":
      // JFR 'int' values reach the value builders as long (see ValueProcessor#onIntValue) and
      // scalar int fields surface as Long, so int arrays are long[] too. Narrowing would also
      // corrupt @Timestamp/@Timespan(TICKS) elements, whose nanosecond values exceed int range.
      case "long":
        array = new long[len];
        break;
      case "byte":
        array = new byte[len];
        break;
      case "boolean":
        array = new boolean[len];
        break;
      case "double":
        array = new double[len];
        break;
      case "float":
        array = new float[len];
        break;
      default:
        array = new Object[len];
    }
  }

  /**
   * Adds a value to the array at the current index.
   *
   * @param value the value to add
   */
  void add(Object value) {
    Array.set(array, index++, value);
  }

  @Override
  public Object getArray() {
    return array;
  }

  @Override
  public Class<?> getType() {
    return array.getClass();
  }

  @Override
  public String toString() {
    switch (type) {
      case "short":
        return Arrays.toString((short[]) array);
      case "char":
        return Arrays.toString((char[]) array);
      case "int":
      case "long":
        return Arrays.toString((long[]) array);
      case "byte":
        return Arrays.toString((byte[]) array);
      case "boolean":
        return Arrays.toString((boolean[]) array);
      case "double":
        return Arrays.toString((double[]) array);
      case "float":
        return Arrays.toString((float[]) array);
      default:
        return Arrays.toString((Object[]) array);
    }
  }
}
