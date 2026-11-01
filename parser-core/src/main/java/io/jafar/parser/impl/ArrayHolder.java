package io.jafar.parser.impl;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.RecordingStream;
import java.io.IOException;
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

  /** Wraps an already filled array of objects. */
  private ArrayHolder(String type, Object[] elements) {
    this.type = type;
    this.array = elements;
    this.longs = null;
    this.doubles = null;
    this.floats = null;
    this.shorts = null;
    this.chars = null;
    this.index = elements.length;
  }

  /**
   * Reads a whole array of a primitive JFR type (including {@code java.lang.String}) from the
   * stream: its length, then its elements, stored unboxed in the same backing array class the value
   * builders produce ({@code long[]} for {@code int} and {@code long}).
   *
   * <p>Elements are decoded exactly as {@code GenericValueReader.readSingleValue} decodes a scalar
   * of the type. Used by the generated untyped deserializers, which cannot reach the
   * package-private constructor and {@code add} methods. Values are not tick-normalized; see {@link
   * #read(RecordingStream, String, int, ParserContext)}.
   *
   * @param stream the stream positioned at the array length
   * @param type the JFR element type name
   * @return the decoded array
   * @throws IOException if a string element cannot be read
   * @throws IllegalArgumentException if {@code type} is not a primitive JFR type
   */
  @Internal
  public static ArrayHolder read(RecordingStream stream, String type) throws IOException {
    return read(stream, type, TemporalNormalizer.KIND_NONE, null);
  }

  /**
   * Like {@link #read(RecordingStream, String)}, then converts the {@code int} and {@code long}
   * elements of a field annotated {@code Timestamp(TICKS)} or {@code Timespan(TICKS)} to
   * nanoseconds, as the generic reader's value builders do.
   *
   * @param stream the stream positioned at the array length
   * @param type the JFR element type name
   * @param temporalKind the field's kind from {@link TemporalNormalizer#kindOf}
   * @param context the chunk's parser context, holding its timing
   * @return the decoded array
   * @throws IOException if a string element cannot be read
   * @throws IllegalArgumentException if {@code type} is not a primitive JFR type
   */
  @Internal
  public static ArrayHolder read(
      RecordingStream stream, String type, int temporalKind, ParserContext context)
      throws IOException {
    int len = (int) stream.readVarint();
    ArrayHolder holder = new ArrayHolder(type, len);
    switch (type) {
      case "short":
        for (int i = 0; i < len; i++) holder.shorts[i] = (short) stream.readVarint();
        break;
      case "char":
        for (int i = 0; i < len; i++) holder.chars[i] = (char) stream.readVarint();
        break;
      case "int":
        for (int i = 0; i < len; i++) holder.longs[i] = (int) stream.readVarint();
        break;
      case "long":
        for (int i = 0; i < len; i++) holder.longs[i] = stream.readVarint();
        break;
      case "byte":
        {
          byte[] bytes = (byte[]) holder.array;
          for (int i = 0; i < len; i++) bytes[i] = stream.read();
          break;
        }
      case "boolean":
        {
          boolean[] booleans = (boolean[]) holder.array;
          for (int i = 0; i < len; i++) booleans[i] = stream.read() != 0;
          break;
        }
      case "float":
        for (int i = 0; i < len; i++) holder.floats[i] = stream.readFloat();
        break;
      case "double":
        for (int i = 0; i < len; i++) holder.doubles[i] = stream.readDouble();
        break;
      case "java.lang.String":
        {
          Object[] strings = (Object[]) holder.array;
          for (int i = 0; i < len; i++) strings[i] = stream.readUTF8();
          break;
        }
      default:
        throw new IllegalArgumentException("Not a primitive JFR type: " + type);
    }
    if (holder.longs != null) {
      TemporalNormalizer.applyAll(holder.longs, len, temporalKind, context);
    }
    holder.index = len;
    return holder;
  }

  /**
   * Wraps an array of decoded elements (complex values, constant pool accessors) of the given JFR
   * element type. Used by the generated untyped deserializers.
   *
   * @param type the JFR element type name
   * @param elements the decoded elements; not copied
   * @return an {@link ArrayType} backed by {@code elements}
   */
  @Internal
  public static ArrayHolder wrap(String type, Object[] elements) {
    return new ArrayHolder(type, elements);
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
