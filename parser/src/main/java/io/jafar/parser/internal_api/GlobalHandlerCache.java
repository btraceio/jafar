package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM-global singleton cache for sharing generated handler classes across parsing sessions.
 *
 * <p>This cache maps metadata fingerprints to deserializer caches, enabling safe reuse of handler
 * classes when metadata is compatible. Uses an LRU eviction policy with a configurable maximum size
 * to prevent unbounded memory growth.
 *
 * <p>Thread-safe for concurrent access from multiple parsing sessions.
 */
public final class GlobalHandlerCache {
  private static final GlobalHandlerCache INSTANCE = new GlobalHandlerCache();
  private static final int MAX_CACHE_SIZE = 1000;

  private final Map<MetadataFingerprint, CacheEntry> cache;
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicLong totalHits = new AtomicLong(0);
  private final AtomicLong totalMisses = new AtomicLong(0);
  private final AtomicLong totalEvictions = new AtomicLong(0);

  private GlobalHandlerCache() {
    // LinkedHashMap with access-order for LRU
    this.cache =
        new LinkedHashMap<MetadataFingerprint, CacheEntry>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<MetadataFingerprint, CacheEntry> eldest) {
            if (size() > MAX_CACHE_SIZE) {
              totalEvictions.incrementAndGet();
              return true;
            }
            return false;
          }
        };
  }

  /**
   * Returns the singleton instance of GlobalHandlerCache.
   *
   * @return the singleton instance
   */
  public static GlobalHandlerCache getInstance() {
    return INSTANCE;
  }

  /**
   * Gets or creates a deserializer cache for the given metadata fingerprint.
   *
   * <p>If a cache already exists for this fingerprint (cache hit), returns the existing cache and
   * updates LRU ordering. Otherwise (cache miss), creates a new cache, stores it, and returns it.
   *
   * @param fingerprint the metadata fingerprint identifying the cache
   * @return the deserializer cache for this fingerprint
   */
  public DeserializerCache getOrCreateCache(MetadataFingerprint fingerprint) {
    lock.lock();
    try {
      CacheEntry entry = cache.get(fingerprint);
      if (entry != null) {
        // Cache hit - update statistics and LRU
        entry.hits.incrementAndGet();
        entry.lastAccessTime.set(System.nanoTime());
        totalHits.incrementAndGet();
        return entry.deserializerCache;
      }

      // Cache miss - create new entry
      totalMisses.incrementAndGet();
      DeserializerCache newCache = new DeserializerCache.Impl();
      entry = new CacheEntry(newCache);
      cache.put(fingerprint, entry);

      return newCache;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the current number of cached fingerprints.
   *
   * @return the cache size
   */
  public int size() {
    lock.lock();
    try {
      return cache.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the total number of cache hits since creation.
   *
   * @return the total hit count
   */
  public long getTotalHits() {
    return totalHits.get();
  }

  /**
   * Returns the total number of cache misses since creation.
   *
   * @return the total miss count
   */
  public long getTotalMisses() {
    return totalMisses.get();
  }

  /**
   * Returns the total number of evictions due to LRU policy.
   *
   * @return the total eviction count
   */
  public long getTotalEvictions() {
    return totalEvictions.get();
  }

  /**
   * Returns the cache hit rate as a percentage (0-100).
   *
   * @return the hit rate percentage
   */
  public double getHitRate() {
    long hits = totalHits.get();
    long misses = totalMisses.get();
    long total = hits + misses;
    if (total == 0) {
      return 0.0;
    }
    return (hits * 100.0) / total;
  }

  /**
   * Clears all cached entries and resets statistics.
   *
   * <p>This method is primarily intended for testing purposes.
   */
  public void clear() {
    lock.lock();
    try {
      cache.clear();
      totalHits.set(0);
      totalMisses.set(0);
      totalEvictions.set(0);
    } finally {
      lock.unlock();
    }
  }

  /** Internal cache entry holding a deserializer cache and its statistics. */
  private static final class CacheEntry {
    final DeserializerCache deserializerCache;
    final AtomicLong hits;
    final AtomicLong lastAccessTime;

    CacheEntry(DeserializerCache cache) {
      this.deserializerCache = cache;
      this.hits = new AtomicLong(0);
      this.lastAccessTime = new AtomicLong(System.nanoTime());
    }
  }
}
