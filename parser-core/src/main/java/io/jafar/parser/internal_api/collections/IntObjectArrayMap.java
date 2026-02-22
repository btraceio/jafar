package io.jafar.parser.internal_api.collections;

import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Direct-indexed map from small non-negative {@code int} keys to object values.
 *
 * <p>Uses a plain {@code Object[]} — zero hash computation, O(1) access. Suitable for keys that are
 * dense non-negative integers (e.g., chunk indices).
 */
public final class IntObjectArrayMap<V> {

  private Object[] data;

  public IntObjectArrayMap() {
    this(16);
  }

  public IntObjectArrayMap(int initialCapacity) {
    data = new Object[initialCapacity];
  }

  /** Copy constructor — creates a shallow copy. */
  public IntObjectArrayMap(IntObjectArrayMap<V> other) {
    data = Arrays.copyOf(other.data, other.data.length);
  }

  @SuppressWarnings("unchecked")
  public V get(int key) {
    if (key < 0 || key >= data.length) return null;
    return (V) data[key];
  }

  public void put(int key, V value) {
    ensureCapacity(key);
    data[key] = value;
  }

  @SuppressWarnings("unchecked")
  public V computeIfAbsent(int key, IntFunction<V> mappingFunction) {
    ensureCapacity(key);
    V val = (V) data[key];
    if (val == null) {
      val = mappingFunction.apply(key);
      data[key] = val;
    }
    return val;
  }

  public void clear() {
    Arrays.fill(data, null);
  }

  private void ensureCapacity(int key) {
    if (key < 0) throw new IllegalArgumentException("negative key: " + key);
    if (key >= data.length) {
      int newLen = data.length;
      while (newLen <= key) {
        newLen <<= 1;
      }
      data = Arrays.copyOf(data, newLen);
    }
  }
}
