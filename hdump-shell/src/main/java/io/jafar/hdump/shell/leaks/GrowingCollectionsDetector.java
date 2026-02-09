package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects collections with unexpectedly large retained sizes.
 *
 * <p>Growing collections that aren't properly bounded often indicate memory leaks. This detector
 * finds HashMap, ArrayList, HashSet, and other collections with large retained sizes.
 *
 * <p>MinSize parameter: Minimum retained size in bytes to report (default: 10MB)
 */
public final class GrowingCollectionsDetector implements LeakDetector {

  private static final Set<String> COLLECTION_CLASSES =
      Set.of(
          "java/util/HashMap",
          "java/util/ArrayList",
          "java/util/HashSet",
          "java/util/LinkedHashMap",
          "java/util/TreeMap",
          "java/util/LinkedList",
          "java/util/ConcurrentHashMap",
          "java/util/concurrent/ConcurrentHashMap",
          "java/util/WeakHashMap");

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    long minRetainedBytes = minSize != null ? minSize : 10 * 1024 * 1024; // 10MB default

    List<Map<String, Object>> results = new ArrayList<>();

    for (String className : COLLECTION_CLASSES) {
      dump.getObjectsOfClass(className)
          .filter(obj -> obj.getRetainedSize() >= minRetainedBytes)
          .forEach(
              obj -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("class", className);
                result.put("id", obj.getId());
                result.put("shallow", obj.getShallowSize());
                result.put("retained", obj.getRetainedSize());
                result.put("arrayLength", obj.isArray() ? obj.getArrayLength() : -1);

                // Try to get size field for collections
                Object sizeField = obj.getFieldValue("size");
                if (sizeField instanceof Number) {
                  result.put("elementCount", ((Number) sizeField).intValue());
                }

                result.put(
                    "suggestion",
                    "Review collection growth strategy. Consider size limits, eviction policies, or weak references.");

                results.add(result);
              });
    }

    // Sort by retained size descending
    results.sort((a, b) -> Long.compare((Long) b.get("retained"), (Long) a.get("retained")));

    return results;
  }

  @Override
  public String getName() {
    return "growing-collections";
  }

  @Override
  public String getDescription() {
    return "Detect collections (HashMap, ArrayList, etc.) with large retained sizes";
  }
}
