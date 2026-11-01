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

  /** {@link #array} as a {@code long[]} for {@code int} and {@code long} elements, else null. */
  private final long[] longs;

  /** {@link #array} as a {@code double[]} for {@code double} elements, else null. */
  private final double[] doubles;

  /** {@link #array} as a {@code float[]} for {@code float} elements, else null. */
  private final float[] floats;

  /** {@link #array} as a {@code short[]} for {@code short} elements, else null. */
  private final short[] shorts;

  /** {@link #array} as a {@code char[]} for {@code char} elements, else null. */
  private final char[] chars;

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
    longs = array instanceof long[] ? (long[]) array : null;
    doubles = array instanceof double[] ? (double[]) array : null;
    floats = array instanceof float[] ? (float[]) array : null;
    shorts = array instanceof short[] ? (short[]) array : null;
    chars = array instanceof char[] ? (char[]) array : null;
  }

  /**
   * Adds a value to the array at the current index.
   *
   * @param value the value to add
   */
  void add(Object value) {
    Array.set(array, index++, value);
  }

  /**
   * Adds an {@code int} or {@code long} element at the current index without boxing it.
   *
   * <p>{@link #add(Object)} would box the value and store it through reflection; the box escapes
   * into the native {@code Array.set}, so it is a real allocation for every element outside the
   * {@code Long} cache (-128..127), which includes every normalized timestamp.
   *
   * @param value the value to add
   * @throws IllegalStateException if the element type is neither {@code int} nor {@code long}
   */
  void addLong(long value) {
    if (longs == null) {
      throw new IllegalStateException("addLong on an array of " + type);
    }
    longs[index++] = value;
  }

  /**
   * Adds a {@code double} element at the current index without boxing it. {@code Double} has no
   * value cache, so {@link #add(Object)} would allocate for every element.
   *
   * @param value the value to add
   * @throws IllegalStateException if the element type is not {@code double}
   */
  void addDouble(double value) {
    if (doubles == null) {
      throw new IllegalStateException("addDouble on an array of " + type);
    }
    doubles[index++] = value;
  }

  /**
   * Adds a {@code float} element at the current index without boxing it. {@code Float} has no value
   * cache, so {@link #add(Object)} would allocate for every element.
   *
   * @param value the value to add
   * @throws IllegalStateException if the element type is not {@code float}
   */
  void addFloat(float value) {
    if (floats == null) {
      throw new IllegalStateException("addFloat on an array of " + type);
    }
    floats[index++] = value;
  }

  /**
   * Adds a {@code short} element at the current index without boxing it. {@code Short} caches only
   * -128..127, so {@link #add(Object)} would allocate for every other element.
   *
   * @param value the value to add
   * @throws IllegalStateException if the element type is not {@code short}
   */
  void addShort(short value) {
    if (shorts == null) {
      throw new IllegalStateException("addShort on an array of " + type);
    }
    shorts[index++] = value;
  }

  /**
   * Adds a {@code char} element at the current index without boxing it. {@code Character} caches
   * only 0..127, so {@link #add(Object)} would allocate for every other element.
   *
   * @param value the value to add
   * @throws IllegalStateException if the element type is not {@code char}
   */
  void addChar(char value) {
    if (chars == null) {
      throw new IllegalStateException("addChar on an array of " + type);
    }
    chars[index++] = value;
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
