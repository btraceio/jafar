package io.jafar.parser.impl;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
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
public final class LazyEventMap extends AbstractMap<String, Object> {
  // Reference to the ArrayPool to extract arrays on demand
  private final LazyMapValueBuilder.ArrayPool pool;
  private final int size;

  // Cached arrays (created on first access)
  private String[] keys;
  private Object[] values;
  private Map<String, Object> materializedMap;

  public LazyEventMap(LazyMapValueBuilder.ArrayPool pool, int size) {
    this.pool = pool;
    this.size = size;
  }

  /**
   * Creates a map over arrays owned by the caller, without copying them. Used by generated untyped
   * deserializers, which pass a per-class constant {@code keys} array shared by every map they
   * return and a fresh {@code values} array per event.
   *
   * <p>Neither array may be modified afterwards. This map never writes to them: materialization and
   * {@link #keySet()} iteration copy.
   *
   * @param keys field names; may be shared between instances
   * @param values field values, same order and length as {@code keys}
   */
  public LazyEventMap(String[] keys, Object[] values) {
    if (keys.length != values.length) {
      throw new IllegalArgumentException(keys.length + " keys, " + values.length + " values");
    }
    this.pool = null;
    this.size = values.length;
    this.keys = keys;
    this.values = values;
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
      if (Objects.equals(keys[i], key)) {
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
      if (Objects.equals(keys[i], key)) {
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
