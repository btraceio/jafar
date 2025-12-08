package io.jafar.parser.impl;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Memory-efficient Map implementation using flyweight pattern for JFR event data.
 *
 * <p>This class shares the key array (field names) across all instances of the same event type,
 * storing only the values array per instance. Keys are sorted for binary search lookup.
 *
 * <p>This implementation is immutable - mutation operations throw {@link
 * UnsupportedOperationException}.
 *
 * <p><b>Memory savings:</b> For events with N fields, eliminates N HashMap.Node allocations (40
 * bytes each), reducing per-event overhead from ~300 bytes to ~50 bytes.
 */
final class FlyweightEventMap extends AbstractMap<String, Object> {
  private final String[] keys;
  private final Object[] values;
  private transient Set<Entry<String, Object>> entrySet;

  /**
   * Constructs a flyweight map with shared keys and unique values.
   *
   * @param keys sorted array of field names (shared across instances)
   * @param values array of field values (unique per instance)
   */
  FlyweightEventMap(String[] keys, Object[] values) {
    if (keys.length != values.length) {
      throw new IllegalArgumentException(
          "Keys and values arrays must have same length: " + keys.length + " != " + values.length);
    }
    this.keys = keys;
    this.values = values;
  }

  @Override
  public Object get(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    int idx = Arrays.binarySearch(keys, key);
    return idx >= 0 ? values[idx] : null;
  }

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) {
      return false;
    }
    return Arrays.binarySearch(keys, key) >= 0;
  }

  @Override
  public int size() {
    return keys.length;
  }

  @Override
  public boolean isEmpty() {
    return keys.length == 0;
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    Set<Entry<String, Object>> es = entrySet;
    if (es == null) {
      es = new EntrySet();
      entrySet = es;
    }
    return es;
  }

  @Override
  public Set<String> keySet() {
    return new AbstractSet<String>() {
      @Override
      public Iterator<String> iterator() {
        return new Iterator<String>() {
          private int pos = 0;

          @Override
          public boolean hasNext() {
            return pos < keys.length;
          }

          @Override
          public String next() {
            if (pos >= keys.length) {
              throw new NoSuchElementException();
            }
            return keys[pos++];
          }
        };
      }

      @Override
      public int size() {
        return keys.length;
      }

      @Override
      public boolean contains(Object o) {
        return containsKey(o);
      }
    };
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Map)) {
      return false;
    }
    Map<?, ?> m = (Map<?, ?>) o;
    if (m.size() != size()) {
      return false;
    }
    try {
      for (int i = 0; i < keys.length; i++) {
        String key = keys[i];
        Object value = values[i];
        if (value == null) {
          if (!(m.get(key) == null && m.containsKey(key))) {
            return false;
          }
        } else {
          if (!value.equals(m.get(key))) {
            return false;
          }
        }
      }
    } catch (ClassCastException | NullPointerException unused) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int h = 0;
    for (int i = 0; i < keys.length; i++) {
      h += Objects.hashCode(keys[i]) ^ Objects.hashCode(values[i]);
    }
    return h;
  }

  @Override
  public String toString() {
    if (isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(keys[i]);
      sb.append('=');
      Object value = values[i];
      sb.append(value == this ? "(this Map)" : value);
    }
    sb.append('}');
    return sb.toString();
  }

  private final class EntrySet extends AbstractSet<Entry<String, Object>> {
    @Override
    public Iterator<Entry<String, Object>> iterator() {
      return new Iterator<Entry<String, Object>>() {
        private int pos = 0;

        @Override
        public boolean hasNext() {
          return pos < keys.length;
        }

        @Override
        public Entry<String, Object> next() {
          if (pos >= keys.length) {
            throw new NoSuchElementException();
          }
          int current = pos++;
          return new SimpleImmutableEntry<>(keys[current], values[current]);
        }
      };
    }

    @Override
    public int size() {
      return keys.length;
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry<?, ?> e = (Entry<?, ?>) o;
      Object key = e.getKey();
      if (!(key instanceof String)) {
        return false;
      }
      int idx = Arrays.binarySearch(keys, key);
      if (idx < 0) {
        return false;
      }
      return Objects.equals(values[idx], e.getValue());
    }
  }
}
