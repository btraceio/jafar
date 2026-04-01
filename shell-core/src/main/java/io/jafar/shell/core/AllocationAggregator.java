package io.jafar.shell.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates JFR {@code ObjectAllocationSample} event rows into per-class allocation statistics.
 *
 * <p>Input rows are expected to contain at least an {@code objectClass.name} (or {@code
 * objectClass}) field. Optional fields: {@code weight} (allocation size in bytes), {@code
 * startTime} (event timestamp in nanoseconds), {@code stackTrace} (stack trace string or map).
 *
 * <p>Output is a map keyed by normalized class name (dots, not slashes) with values containing:
 * {@code allocCount}, {@code allocWeight}, {@code allocRate}, {@code topAllocSite}.
 */
public final class AllocationAggregator {

  private AllocationAggregator() {}

  /**
   * Aggregates raw JFR event rows into per-class allocation statistics.
   *
   * @param rows raw event rows from a JFR query
   * @return map from normalized class name to aggregation columns
   */
  public static Map<String, Map<String, Object>> aggregate(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }

    Map<String, long[]> countAndWeight = new HashMap<>(); // [count, weight]
    Map<String, Map<String, long[]>> siteCounts = new HashMap<>(); // class -> site -> [count]
    long minTime = Long.MAX_VALUE;
    long maxTime = Long.MIN_VALUE;

    for (Map<String, Object> row : rows) {
      String className = extractClassName(row);
      if (className == null) {
        continue;
      }
      className = normalizeClassName(className);

      long weight = extractLong(row, "weight");

      long[] cw = countAndWeight.computeIfAbsent(className, k -> new long[2]);
      cw[0]++;
      cw[1] += weight;

      // Track timestamps for rate computation
      long startTime = extractLong(row, "startTime");
      if (startTime > 0) {
        if (startTime < minTime) minTime = startTime;
        if (startTime > maxTime) maxTime = startTime;
      }

      // Track allocation sites
      String site = extractTopFrame(row);
      if (site != null) {
        siteCounts.computeIfAbsent(className, k -> new HashMap<>())
            .computeIfAbsent(site, k -> new long[1])[0]++;
      }
    }

    double durationSeconds = (minTime < maxTime) ? (maxTime - minTime) / 1_000_000_000.0 : 0.0;

    Map<String, Map<String, Object>> result = new HashMap<>();
    for (Map.Entry<String, long[]> entry : countAndWeight.entrySet()) {
      String className = entry.getKey();
      long count = entry.getValue()[0];
      long weight = entry.getValue()[1];

      Map<String, Object> stats = new HashMap<>();
      stats.put("allocCount", count);
      stats.put("allocWeight", weight);
      stats.put("allocRate", durationSeconds > 0 ? count / durationSeconds : null);
      stats.put("topAllocSite", findTopSite(siteCounts.get(className)));
      result.put(className, stats);
    }

    return result;
  }

  private static String extractClassName(Map<String, Object> row) {
    // Try objectClass.name first (flattened field from JFR)
    Object v = row.get("objectClass.name");
    if (v instanceof String s && !s.isEmpty()) {
      return s;
    }
    // Try objectClass as a direct string
    v = row.get("objectClass");
    if (v instanceof String s && !s.isEmpty()) {
      return s;
    }
    // Try objectClass as a map with a "name" key
    if (v instanceof Map<?, ?> m) {
      Object name = m.get("name");
      if (name instanceof String s && !s.isEmpty()) {
        return s;
      }
    }
    return null;
  }

  private static long extractLong(Map<String, Object> row, String key) {
    Object v = row.get(key);
    if (v instanceof Number n) {
      return n.longValue();
    }
    return 0;
  }

  private static String extractTopFrame(Map<String, Object> row) {
    Object v = row.get("stackTrace");
    if (v instanceof String s && !s.isEmpty()) {
      // Take first line as top frame
      int nl = s.indexOf('\n');
      return nl > 0 ? s.substring(0, nl).trim() : s.trim();
    }
    if (v instanceof Map<?, ?> m) {
      // stackTrace may be a structured object; try "frames" list
      Object frames = m.get("frames");
      if (frames instanceof List<?> list && !list.isEmpty()) {
        Object top = list.get(0);
        if (top instanceof String s) return s;
        if (top instanceof Map<?, ?> fm) {
          Object method = fm.get("method");
          if (method instanceof String s) return s;
          if (method instanceof Map<?, ?> mm) {
            Object mName = mm.get("name");
            Object mType = mm.get("type");
            if (mName != null && mType != null) {
              return mType + "." + mName;
            }
            if (mName != null) return String.valueOf(mName);
          }
        }
      }
    }
    return null;
  }

  /**
   * Normalizes a JVM class name to human-readable Java form. Handles internal format ({@code
   * java/lang/String}), descriptor format ({@code Ljava/lang/String;}), and array descriptors
   * ({@code [Ljava/lang/String;} → {@code java.lang.String[]}).
   */
  static String normalizeClassName(String name) {
    if (name == null) return null;

    // Count leading '[' for array dimensions
    int dims = 0;
    while (dims < name.length() && name.charAt(dims) == '[') {
      dims++;
    }

    String base = dims > 0 ? name.substring(dims) : name;

    // Strip L...;  descriptor wrapper
    if (base.startsWith("L") && base.endsWith(";")) {
      base = base.substring(1, base.length() - 1);
    }

    // Handle primitive array descriptors
    if (dims > 0 && base.length() == 1) {
      base =
          switch (base.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            default -> base;
          };
    }

    base = base.replace('/', '.');

    if (dims > 0) {
      base = base + "[]".repeat(dims);
    }
    return base;
  }

  private static String findTopSite(Map<String, long[]> sites) {
    if (sites == null || sites.isEmpty()) return null;
    String top = null;
    long topCount = 0;
    for (Map.Entry<String, long[]> e : sites.entrySet()) {
      if (e.getValue()[0] > topCount) {
        topCount = e.getValue()[0];
        top = e.getKey();
      }
    }
    return top;
  }
}
