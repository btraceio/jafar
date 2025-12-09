package io.jafar.parser.impl;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Lazy Map implementation for JFR events (Tier 2 optimization with ultra-lazy allocation).
 *
 * <p>This map defers ALL allocations until fields are actually accessed. Arrays are only created
 * when the map is first used, making count-only workloads allocation-free.
 *
 * <p>Expected benefits:
 *
 * <ul>
 *   <li>Count-only (no access): Zero allocations (arrays never created)
 *   <li>Sparse access (1-3 fields): 70-80% allocation reduction (no HashMap.Node allocations)
 *   <li>Full iteration: ~10% overhead (one-time HashMap materialization cost)
 * </ul>
 *
 * <p>This optimization targets the common use case where handlers only access a few fields per
 * event (e.g., filtering by timestamp, checking event type), and also handles count-only scenarios
 * efficiently.
 */
final class LazyEventMap extends AbstractMap<String, Object> {
  // Reference to the ArrayPool to extract arrays on demand
  private final LazyMapValueBuilder.ArrayPool pool;
  private final int size;

  // Cached arrays (created on first access)
  private String[] keys;
  private Object[] values;
  private Map<String, Object> materializedMap;

  LazyEventMap(LazyMapValueBuilder.ArrayPool pool, int size) {
    this.pool = pool;
    this.size = size;
  }

  private void ensureArrays() {
    if (keys == null) {
      keys = pool.getKeys();
      values = pool.getValues();
    }
  }

  @Override
  public Object get(Object key) {
    if (materializedMap != null) {
      return materializedMap.get(key);
    }

    ensureArrays();

    // Linear search for sparse access (fast for small field counts)
    for (int i = 0; i < size; i++) {
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

    ensureArrays();

    for (int i = 0; i < size; i++) {
      if (keys[i].equals(key)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int size() {
    return size;
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

    ensureArrays();

    // Return a set view without materializing the HashMap
    return new AbstractSet<String>() {
      @Override
      public Iterator<String> iterator() {
        return Arrays.asList(Arrays.copyOf(keys, size)).iterator();
      }

      @Override
      public int size() {
        return size;
      }

      @Override
      public boolean contains(Object o) {
        return containsKey(o);
      }
    };
  }

  private void materialize() {
    if (materializedMap == null) {
      ensureArrays();
      materializedMap = new HashMap<>(size);
      for (int i = 0; i < size; i++) {
        materializedMap.put(keys[i], values[i]);
      }
    }
  }
}
