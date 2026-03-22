package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects duplicate string objects that consume unnecessary memory.
 *
 * <p>String deduplication is often overlooked in Java applications. This detector groups identical
 * string values and reports cases where many instances share the same content.
 *
 * <p>Threshold parameter: Minimum number of duplicate instances to report (default: 100)
 *
 * <p>Memory model: string values are hashed with FNV-1a 64-bit and only the hash (8 bytes) is
 * stored as the map key. The full string content is never retained, keeping peak memory at {@code
 * O(unique_string_count * constant)} regardless of string lengths.
 */
public final class DuplicateStringsDetector implements LeakDetector {

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x00000100000001b3L;

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    int minDuplicates = threshold != null ? threshold : 100;

    // Key: FNV-1a 64-bit hash of the string value.
    // Storing the hash (long) instead of the String object keeps peak memory at 8 bytes per unique
    // string rather than the full string content, which can be hundreds of MB on large heaps.
    Map<Long, StringGroupMetrics> stringsByHash = new HashMap<>();

    dump.getObjectsOfClass("java/lang/String")
        .forEach(
            obj -> {
              String value = obj.getStringValue();
              if (value != null) {
                long hash = fnv1a(value);
                stringsByHash
                    .computeIfAbsent(hash, k -> new StringGroupMetrics(truncate(value, 100)))
                    .addInstance(obj);
              }
            });

    List<Map<String, Object>> results = new ArrayList<>();
    for (StringGroupMetrics metrics : stringsByHash.values()) {
      if (metrics.count >= minDuplicates) {
        long wastedBytes = metrics.totalShallow - metrics.exampleShallow;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", metrics.count);
        result.put("value", metrics.sampleValue);
        result.put("shallow", metrics.totalShallow);
        result.put("retained", metrics.totalRetained);
        result.put("wastedBytes", wastedBytes);
        result.put(
            "suggestion",
            "Consider string deduplication or interning. Potential savings: "
                + formatBytes(wastedBytes));

        results.add(result);
      }
    }

    results.sort((a, b) -> Long.compare((Long) b.get("wastedBytes"), (Long) a.get("wastedBytes")));
    return results;
  }

  @Override
  public String getName() {
    return "duplicate-strings";
  }

  @Override
  public String getDescription() {
    return "Find identical string values with high instance counts";
  }

  private static long fnv1a(String s) {
    long h = FNV_OFFSET;
    for (int i = 0; i < s.length(); i++) {
      h ^= s.charAt(i);
      h *= FNV_PRIME;
    }
    return h;
  }

  private String truncate(String s, int maxLen) {
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen - 3) + "...";
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  private static class StringGroupMetrics {
    /** First-seen value, already truncated to 100 chars. */
    final String sampleValue;

    int count = 0;
    long totalShallow = 0;
    long totalRetained = 0;
    long exampleShallow = 0;

    StringGroupMetrics(String sampleValue) {
      this.sampleValue = sampleValue;
    }

    void addInstance(HeapObject obj) {
      count++;
      long shallow = obj.getShallowSize();
      long retained = obj.getRetainedSizeIfAvailable();
      totalShallow += shallow;
      if (retained >= 0) totalRetained += retained;
      if (count == 1) exampleShallow = shallow;
    }
  }
}
