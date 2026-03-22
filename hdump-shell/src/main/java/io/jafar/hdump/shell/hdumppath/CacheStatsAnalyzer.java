package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.HeapObject;
import java.util.Map;

/**
 * Analyzes Map-based objects for cache-relevant statistics. Reads internal fields of known Map
 * types to compute entry count, fill ratio, cost-per-entry, and LRU mode.
 *
 * <p>Supported types: HashMap, LinkedHashMap, WeakHashMap, and their subclasses via the {@code
 * "table"} field heuristic.
 */
final class CacheStatsAnalyzer {

  private CacheStatsAnalyzer() {}

  /**
   * Analyzes a heap object for cache statistics and enriches the row with cache columns.
   *
   * @param obj the heap object to analyze
   * @param row the result row to enrich with cache columns
   * @param retainedSize the retained size of the object in bytes (from the incoming row)
   * @return true if the object was a recognized Map type, false otherwise
   */
  static boolean analyze(HeapObject obj, Map<String, Object> row, long retainedSize) {
    if (obj.getHeapClass() == null) {
      return false;
    }
    String className = obj.getHeapClass().getName();
    return switch (className) {
      case "java/util/HashMap", "java/util/LinkedHashMap", "java/util/WeakHashMap" ->
          analyzeHashMap(obj, row, retainedSize, className);
      default -> analyzeGenericMap(obj, row, retainedSize);
    };
  }

  private static boolean analyzeHashMap(
      HeapObject obj, Map<String, Object> row, long retainedSize, String className) {
    Object sizeObj = obj.getFieldValue("size");
    int entryCount = sizeObj instanceof Number ? ((Number) sizeObj).intValue() : 0;

    Object tableObj = obj.getFieldValue("table");
    int capacity = -1;
    if (tableObj instanceof HeapObject tableArr && tableArr.isArray()) {
      capacity = tableArr.getArrayLength();
    }

    double fillRatio = capacity > 0 ? (double) entryCount / capacity : 0.0;
    long costPerEntry = entryCount > 0 ? retainedSize / entryCount : 0L;

    boolean isLruMode = false;
    if ("java/util/LinkedHashMap".equals(className)) {
      Object accessOrderObj = obj.getFieldValue("accessOrder");
      isLruMode = accessOrderObj instanceof Number n && n.intValue() != 0;
    }

    row.put("entryCount", entryCount);
    row.put("maxSize", capacity);
    row.put("fillRatio", fillRatio);
    row.put("costPerEntry", costPerEntry);
    row.put("isLruMode", isLruMode);
    return true;
  }

  /**
   * Attempts to analyze an arbitrary object as a Map by probing the {@code "table"} and {@code
   * "size"} fields. Returns false if neither field is found.
   */
  private static boolean analyzeGenericMap(
      HeapObject obj, Map<String, Object> row, long retainedSize) {
    Object sizeObj = obj.getFieldValue("size");
    Object tableObj = obj.getFieldValue("table");
    if (sizeObj == null && tableObj == null) {
      return false;
    }
    int entryCount = sizeObj instanceof Number ? ((Number) sizeObj).intValue() : 0;
    int capacity = -1;
    if (tableObj instanceof HeapObject tableArr && tableArr.isArray()) {
      capacity = tableArr.getArrayLength();
    }
    double fillRatio = capacity > 0 ? (double) entryCount / capacity : 0.0;
    long costPerEntry = entryCount > 0 ? retainedSize / entryCount : 0L;

    row.put("entryCount", entryCount);
    row.put("maxSize", capacity);
    row.put("fillRatio", fillRatio);
    row.put("costPerEntry", costPerEntry);
    row.put("isLruMode", false);
    return true;
  }

  static void addNullColumns(Map<String, Object> row) {
    row.put("entryCount", null);
    row.put("maxSize", null);
    row.put("fillRatio", null);
    row.put("costPerEntry", null);
    row.put("isLruMode", null);
  }
}
