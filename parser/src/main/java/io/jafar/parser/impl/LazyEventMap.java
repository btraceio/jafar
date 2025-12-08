package io.jafar.parser.impl;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Lazy Map implementation for JFR events (Tier 2 optimization).
 *
 * <p>This map defers HashMap.Node allocations until fields are actually accessed. Fields are stored
 * in parallel arrays, and the internal HashMap is only created on demand.
 *
 * <p>Expected benefits:
 *
 * <ul>
 *   <li>Sparse access (1-3 fields): 70-80% allocation reduction (no HashMap.Node allocations)
 *   <li>Full iteration: ~10% overhead (one-time HashMap materialization cost)
 *   <li>Medium access (4-7 fields): 30-50% reduction
 * </ul>
 *
 * <p>This optimization targets the common use case where handlers only access a few fields per
 * event (e.g., filtering by timestamp, checking event type).
 */
final class LazyEventMap extends AbstractMap<String, Object> {
  private final String[] keys;
  private final Object[] values;
  private Map<String, Object> materializedMap;

  LazyEventMap(String[] keys, Object[] values) {
    if (keys.length != values.length) {
      throw new IllegalArgumentException("Keys and values arrays must have the same length");
    }
    this.keys = keys;
    this.values = values;
  }

  @Override
  public Object get(Object key) {
    if (materializedMap != null) {
      return materializedMap.get(key);
    }

    // Linear search for sparse access (fast for small field counts)
    for (int i = 0; i < keys.length; i++) {
      if (keys[i].equals(key)) {
        return values[i];
      }
    }
    return null;
  }

  @Override
  public boolean containsKey(Object key) {
    if (materializedMap != null) {
      return materializedMap.containsKey(key);
    }

    for (String k : keys) {
      if (k.equals(key)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int size() {
    return keys.length;
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    // Full iteration triggers materialization
    materialize();
    return materializedMap.entrySet();
  }

  @Override
  public Set<String> keySet() {
    if (materializedMap != null) {
      return materializedMap.keySet();
    }

    // Return a set view without materializing the HashMap
    return new AbstractSet<String>() {
      @Override
      public Iterator<String> iterator() {
        return Arrays.asList(keys).iterator();
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

  private void materialize() {
    if (materializedMap == null) {
      materializedMap = new HashMap<>(keys.length);
      for (int i = 0; i < keys.length; i++) {
        materializedMap.put(keys[i], values[i]);
      }
    }
  }
}
