package io.jafar.hdump.impl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU (Least Recently Used) cache with automatic eviction.
 *
 * <p>This cache maintains a maximum size and automatically evicts the least recently accessed
 * entries when capacity is exceeded. Thread-safe for single-threaded access patterns typical
 * in heap dump analysis.
 *
 * <p><b>Memory benefits for large heaps:</b>
 * <ul>
 *   <li>Without LRU: 114M objects = ~4-6GB of HeapObjectImpl instances
 *   <li>With LRU (100K): 100K objects = ~3-4MB of HeapObjectImpl instances
 *   <li>Reduction: ~1000x memory savings
 * </ul>
 *
 * @param <K> key type
 * @param <V> value type
 */
final class LruCache<K, V> {

  private final int maxSize;
  private final Map<K, V> cache;

  /**
   * Creates an LRU cache with the specified maximum size.
   *
   * @param maxSize maximum number of entries to keep in cache
   */
  LruCache(int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
    }
    this.maxSize = maxSize;
    // LinkedHashMap with access-order (true) for LRU behavior
    this.cache = new LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > LruCache.this.maxSize;
      }
    };
  }

  /**
   * Returns the value associated with the key, or null if not present.
   * Accessing a key marks it as recently used.
   */
  V get(K key) {
    return cache.get(key);
  }

  /**
   * Associates the value with the key. If cache is at capacity, evicts the
   * least recently used entry. The newly added entry becomes most recently used.
   */
  void put(K key, V value) {
    cache.put(key, value);
  }

  /**
   * Returns the current number of entries in the cache.
   */
  int size() {
    return cache.size();
  }

  /**
   * Returns the maximum capacity of the cache.
   */
  int maxSize() {
    return maxSize;
  }

  /**
   * Removes all entries from the cache.
   */
  void clear() {
    cache.clear();
  }

  /**
   * Returns true if the cache contains the key.
   * Note: This marks the key as recently accessed.
   */
  boolean containsKey(K key) {
    return cache.containsKey(key);
  }

  /**
   * Returns all values currently in the cache.
   * Used for algorithms that need to iterate all cached objects.
   */
  java.util.Collection<V> values() {
    return cache.values();
  }
}
