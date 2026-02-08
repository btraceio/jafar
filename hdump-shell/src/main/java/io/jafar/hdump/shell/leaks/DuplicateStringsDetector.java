package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects duplicate string objects that consume unnecessary memory.
 *
 * <p>String deduplication is often overlooked in Java applications. This detector groups identical
 * string values and reports cases where many instances share the same content.
 *
 * <p>Threshold parameter: Minimum number of duplicate instances to report (default: 100)
 */
public final class DuplicateStringsDetector implements LeakDetector {

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    int minDuplicates = threshold != null ? threshold : 100;

    // Group strings by value - store only metrics to avoid OOME
    Map<String, StringGroupMetrics> stringsByValue = new HashMap<>();

    dump.getObjectsOfClass("java.lang.String")
        .forEach(
            obj -> {
              String value = obj.getStringValue();
              if (value != null) {
                stringsByValue
                    .computeIfAbsent(value, k -> new StringGroupMetrics())
                    .addInstance(obj);
              }
            });

    // Find duplicates above threshold
    List<Map<String, Object>> results = new ArrayList<>();
    for (Map.Entry<String, StringGroupMetrics> entry : stringsByValue.entrySet()) {
      StringGroupMetrics metrics = entry.getValue();
      if (metrics.count >= minDuplicates) {
        String value = entry.getKey();
        long wastedBytes = metrics.totalShallow - metrics.exampleShallow;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", metrics.count);
        result.put("value", truncate(value, 100));
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

    // Sort by wasted bytes descending
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

  /**
   * Lightweight metrics tracker for string groups.
   * Avoids storing full HeapObject references to prevent OOME.
   */
  private static class StringGroupMetrics {
    int count = 0;
    long totalShallow = 0;
    long totalRetained = 0;
    long exampleShallow = 0; // Shallow size of one instance (for waste calculation)

    void addInstance(HeapObject obj) {
      count++;
      long shallow = obj.getShallowSize();
      long retained = obj.getRetainedSize();
      totalShallow += shallow;
      totalRetained += retained;

      // Keep first instance's shallow size as example
      if (count == 1) {
        exampleShallow = shallow;
      }
    }
  }
}
