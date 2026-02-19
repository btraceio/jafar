package io.jafar.parser.internal_api.collections;

import java.util.Arrays;

/**
 * Growable {@code int[]} wrapper for building instruction arrays.
 *
 * <p>Supports append, positional set, and bulk export to {@code int[]}. Doubles capacity on
 * overflow.
 */
public final class IntGrowableArray {
  private int[] data;
  private int size;

  public IntGrowableArray(int initialCapacity) {
    this.data = new int[initialCapacity];
  }

  public void add(int value) {
    if (size == data.length) {
      data = Arrays.copyOf(data, Math.max(1, data.length << 1));
    }
    data[size++] = value;
  }

  public void set(int index, int value) {
    data[index] = value;
  }

  public int size() {
    return size;
  }

  public int[] toIntArray() {
    return Arrays.copyOf(data, size);
  }
}
