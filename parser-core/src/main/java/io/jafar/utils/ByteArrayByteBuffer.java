package io.jafar.utils;

import java.nio.ByteOrder;

/**
 * A {@link CustomByteBuffer} implementation that wraps a raw byte array. It supports Little Endian
 * byte order by default, which is standard for JFR data streams.
 */
public final class ByteArrayByteBuffer implements CustomByteBuffer {
  private final byte[] arr;
  private ByteOrder order;
  private int pos;
  private int limit;

  /**
   * Constructs a new ByteArrayByteBuffer wrapping the specified array.
   *
   * @param arr the byte array to wrap
   */
  public ByteArrayByteBuffer(byte[] arr) {
    this(arr, 0, arr.length, ByteOrder.LITTLE_ENDIAN);
  }

  /**
   * Constructs a new ByteArrayByteBuffer with a specific slice of the array and byte order.
   *
   * @param arr the byte array to wrap
   * @param offset the starting index in the array
   * @param length the length of the slice
   * @param order the byte order to use
   */
  public ByteArrayByteBuffer(byte[] arr, int offset, int length, ByteOrder order) {
    this.arr = arr;
    this.pos = offset;
    this.limit = offset + length;
    this.order = order;
  }

  @Override
  public long limit() {
    return limit;
  }

  @Override
  public boolean isNativeOrder() {
    return order == ByteOrder.nativeOrder();
  }

  @Override
  public CustomByteBuffer order(ByteOrder order) {
    this.order = order;
    return this;
  }

  @Override
  public ByteOrder order() {
    return order;
  }

  @Override
  public void position(long position) {
    if (position < 0 || position > limit) {
      throw new IllegalArgumentException("Position out of bounds: " + position);
    }
    this.pos = (int) position;
  }

  @Override
  public long position() {
    return pos;
  }

  @Override
  public long remaining() {
    return limit - pos;
  }

  @Override
  public void get(byte[] buffer, int offset, int length) {
    if (pos < 0 || pos + length > limit) {
      throw new IllegalArgumentException("Read operation exceeds buffer limits");
    }
    System.arraycopy(arr, pos, buffer, offset, length);
    pos += length;
  }

  @Override
  public byte get() {
    if (pos >= limit) {
      throw new IllegalArgumentException("Read operation exceeds buffer limits");
    }
    return arr[pos++];
  }

  @Override
  public short getShort() {
    if (pos + 2 > limit) {
      throw new IllegalArgumentException("Read operation exceeds buffer limits");
    }
    if (order == ByteOrder.BIG_ENDIAN) {
      return (short) ((arr[pos] << 8) | (arr[pos + 1] & 0xFF));
    } else {
      return (short) ((arr[pos] & 0xFF) | (arr[pos + 1] << 8));
    }
  }

  @Override
  public int getInt() {
    if (pos + 4 > limit) {
      throw new IllegalArgumentException("Read operation exceeds buffer limits");
    }
    int value;
    if (order == ByteOrder.BIG_ENDIAN) {
      value =
          (arr[pos] << 24)
              | ((arr[pos + 1] & 0xFF) << 16)
              | ((arr[pos + 2] & 0xFF) << 8)
              | (arr[pos + 3] & 0xFF);
    } else {
      value =
          (arr[pos] & 0xFF)
              | ((arr[pos + 1] & 0xFF) << 8)
              | ((arr[pos + 2] & 0xFF) << 16)
              | (arr[pos + 3] << 24);
    }
    pos += 4;
    return value;
  }

  @Override
  public float getFloat() {
    return Float.intBitsToFloat(getInt());
  }

  @Override
  public double getDouble() {
    return Double.longBitsToDouble(getLong());
  }

  @Override
  public long getLong() {
    if (pos + 8 > limit) {
      throw new IllegalArgumentException("Read operation exceeds buffer limits");
    }
    long value;
    if (order == ByteOrder.BIG_ENDIAN) {
      value =
          (long) arr[pos + 7] & 0xFF
              | ((long) arr[pos + 6] & 0xFF) << 8
              | ((long) arr[pos + 5] & 0xFF) << 16
              | ((long) arr[pos + 4] & 0xFF) << 24
              | ((long) arr[pos + 3] & 0xFF) << 32
              | ((long) arr[pos + 2] & 0xFF) << 40
              | ((long) arr[pos + 1] & 0xFF) << 48
              | ((long) arr[pos]) << 56;
    } else {
      value =
          (long) arr[pos + 7] << 56
              | ((long) arr[pos + 6] & 0xFF) << 48
              | ((long) arr[pos + 5] & 0xFF) << 40
              | ((long) arr[pos + 4] & 0xFF) << 32
              | ((long) arr[pos + 3] & 0xFF) << 24
              | ((long) arr[pos + 2] & 0xFF) << 16
              | ((long) arr[pos + 1] & 0xFF) << 8
              | ((long) arr[pos] & 0xFF);
    }
    pos += 8;
    return value;
  }

  @Override
  public void mark() {
    // Not applicable for simple array access, but required by interface
  }

  @Override
  public void reset() {
    // Not applicable for simple array access
  }

  @Override
  public CustomByteBuffer slice() {
    return new ByteArrayByteBuffer(arr, pos, limit - pos, order);
  }

  @Override
  public CustomByteBuffer slice(long pos, long len) {
    if (pos < 0 || len < 0 || pos + len > this.limit) {
      throw new IllegalArgumentException("Slice out of bounds");
    }
    return new ByteArrayByteBuffer(arr, this.pos + (int) pos, (int) len, order);
  }

  @Override
  public long getLong(long offset) {
    if (pos + offset + 8 > limit) {
      throw new IllegalArgumentException("Out of bounds access");
    }
    int base = this.pos + (int) offset;
    long value;
    if (order == ByteOrder.BIG_ENDIAN) {
      value =
          (long) arr[base + 7] & 0xFF
              | ((long) arr[base + 6] & 0xFF) << 8
              | ((long) arr[base + 5] & 0xFF) << 16
              | ((long) arr[base + 4] & 0xFF) << 24
              | ((long) arr[base + 3] & 0xFF) << 32
              | ((long) arr[base + 2] & 0xFF) << 40
              | ((long) arr[base + 1] & 0xFF) << 48
              | ((long) arr[base]) << 56;
    } else {
      value =
          (long) arr[base + 7] << 56
              | ((long) arr[base + 6] & 0xFF) << 48
              | ((long) arr[base + 5] & 0xFF) << 40
              | ((long) arr[base + 4] & 0xFF) << 32
              | ((long) arr[base + 3] & 0xFF) << 24
              | ((long) arr[base + 2] & 0xFF) << 16
              | ((long) arr[base + 1] & 0xFF) << 8
              | ((long) arr[base] & 0xFF);
    }
    return value;
  }

  @Override
  public int getInt(long offset) {
    if (pos + offset + 4 > limit) {
      throw new IllegalArgumentException("Out of bounds access");
    }
    int base = this.pos + (int) offset;
    int value;
    if (order == ByteOrder.BIG_ENDIAN) {
      value =
          (arr[base] << 24)
              | ((arr[base + 1] & 0xFF) << 16)
              | ((arr[base + 2] & 0xFF) << 8)
              | (arr[base + 3] & 0xFF);
    } else {
      value =
          (arr[base] & 0xFF)
              | ((arr[base + 1] & 0xFF) << 8)
              | ((arr[base + 2] & 0xFF) << 16)
              | (arr[base + 3] << 24);
    }
    return value;
  }

  @Override
  public byte get(long offset) {
    if (pos + offset >= limit) {
      throw new IllegalArgumentException("Out of bounds access");
    }
    return arr[this.pos + (int) offset];
  }

  @Override
  public void close() {}
}
