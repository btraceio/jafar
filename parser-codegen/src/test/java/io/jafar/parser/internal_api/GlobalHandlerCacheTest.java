package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for GlobalHandlerCache. */
public class GlobalHandlerCacheTest {

  private GlobalHandlerCache cache;

  @BeforeEach
  public void setup() {
    cache = GlobalHandlerCache.getInstance();
    cache.clear();
  }

  @AfterEach
  public void teardown() {
    cache.clear();
  }

  @Test
  public void testSingletonInstance() {
    GlobalHandlerCache instance1 = GlobalHandlerCache.getInstance();
    GlobalHandlerCache instance2 = GlobalHandlerCache.getInstance();
    assertSame(instance1, instance2, "Should return same singleton instance");
  }

  @Test
  public void testGetOrCreateCache() {
    MetadataFingerprint fp = createFingerprint(1);

    DeserializerCache cache1 = this.cache.getOrCreateCache(fp);
    assertNotNull(cache1);

    DeserializerCache cache2 = this.cache.getOrCreateCache(fp);
    assertSame(cache1, cache2, "Should return same cache for same fingerprint");
  }

  @Test
  public void testCacheHitStatistics() {
    MetadataFingerprint fp = createFingerprint(1);

    long initialMisses = cache.getTotalMisses();
    long initialHits = cache.getTotalHits();

    // First access - cache miss
    cache.getOrCreateCache(fp);
    assertEquals(initialMisses + 1, cache.getTotalMisses(), "Should record cache miss");

    // Second access - cache hit
    cache.getOrCreateCache(fp);
    assertEquals(initialHits + 1, cache.getTotalHits(), "Should record cache hit");
  }

  @Test
  public void testCacheSize() {
    assertEquals(0, cache.size(), "Initial cache should be empty");

    MetadataFingerprint fp1 = createFingerprint(1);
    cache.getOrCreateCache(fp1);
    assertEquals(1, cache.size(), "Cache should have 1 entry");

    // Same fingerprint shouldn't increase size
    cache.getOrCreateCache(fp1);
    assertEquals(1, cache.size(), "Cache size should remain 1 after duplicate access");
  }

  @Test
  public void testHitRate() {
    MetadataFingerprint fp = createFingerprint(1);

    // Initial hit rate should be 0
    double initialRate = cache.getHitRate();
    assertEquals(0.0, initialRate, 0.01, "Initial hit rate should be 0");

    // First access - miss
    cache.getOrCreateCache(fp);

    // Hit rate still 0 (1 miss, 0 hits)
    assertEquals(0.0, cache.getHitRate(), 0.01, "Hit rate after first miss should be 0");

    // Second access - hit
    cache.getOrCreateCache(fp);

    // Hit rate should be 50% (1 miss, 1 hit)
    assertEquals(50.0, cache.getHitRate(), 0.01, "Hit rate should be 50%");

    // Third access - hit
    cache.getOrCreateCache(fp);

    // Hit rate should be ~66.67% (1 miss, 2 hits)
    assertTrue(cache.getHitRate() > 60.0, "Hit rate should increase with more hits");
  }

  @Test
  public void testConcurrentAccess() throws Exception {
    MetadataFingerprint fp = createFingerprint(1);
    int threadCount = 10;
    Thread[] threads = new Thread[threadCount];

    // Launch multiple threads accessing same fingerprint
    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          new Thread(
              () -> {
                DeserializerCache c = cache.getOrCreateCache(fp);
                assertNotNull(c);
              });
      threads[i].start();
    }

    // Wait for all threads
    for (Thread thread : threads) {
      thread.join();
    }

    // Should have exactly 1 cache entry
    assertEquals(1, cache.size(), "Concurrent access should result in single cache entry");

    // Total accesses should be threadCount (1 miss + (threadCount-1) hits)
    long totalAccesses = cache.getTotalHits() + cache.getTotalMisses();
    assertEquals(threadCount, totalAccesses, "Should record all accesses");
  }

  @Test
  public void testClear() {
    MetadataFingerprint fp1 = createFingerprint(1);

    cache.getOrCreateCache(fp1);

    assertTrue(cache.size() > 0);
    assertTrue(cache.getTotalMisses() > 0);

    cache.clear();

    assertEquals(0, cache.size(), "Clear should empty cache");
    assertEquals(0, cache.getTotalHits(), "Clear should reset hit counter");
    assertEquals(0, cache.getTotalMisses(), "Clear should reset miss counter");
    assertEquals(0, cache.getTotalEvictions(), "Clear should reset eviction counter");
  }

  @Test
  public void testSameFingerprint() {
    // Test that same fingerprint returns same cache
    MetadataFingerprint fp1 = createFingerprint(1);
    MetadataFingerprint fp2 = createFingerprint(1); // Same seed = same fingerprint

    DeserializerCache cache1 = cache.getOrCreateCache(fp1);
    DeserializerCache cache2 = cache.getOrCreateCache(fp2);

    assertSame(cache1, cache2, "Same fingerprints should return same cache");
    assertEquals(1, cache.size(), "Should have only 1 cache entry");
  }

  // Helper method to create unique fingerprints
  private MetadataFingerprint createFingerprint(int seed) {
    MutableMetadataLookup lookup = new MutableMetadataLookup();
    // Create a unique reachable set that will produce different hashes
    Set<Long> reachable = new HashSet<>();
    for (int i = 0; i < seed; i++) {
      reachable.add((long) i);
    }
    // Ensure at least one element
    if (reachable.isEmpty()) {
      reachable.add((long) seed);
    }
    return MetadataFingerprint.compute(lookup, reachable);
  }
}
