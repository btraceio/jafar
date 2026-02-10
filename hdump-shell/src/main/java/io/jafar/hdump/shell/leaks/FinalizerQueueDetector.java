package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects objects stuck in the finalizer queue.
 *
 * <p>Objects with finalizers must be processed by the finalizer thread before being garbage
 * collected. If finalizers are slow or block, objects accumulate in the queue, causing memory
 * leaks.
 *
 * <p>Threshold parameter: Minimum number of objects in queue to report (default: 100)
 */
public final class FinalizerQueueDetector implements LeakDetector {

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    int minQueueSize = threshold != null ? threshold : 100;

    List<Map<String, Object>> results = new ArrayList<>();

    // Look for java.lang.ref.Finalizer instances
    // These represent objects waiting for finalization
    // Use streaming-friendly approach: count as we go, don't store objects
    Map<String, Integer> finalizerCountsByClass = new HashMap<>();
    int[] totalFinalizers = {0}; // Array for capturing in lambda

    dump.getObjectsOfClass("java/lang/ref/Finalizer")
        .forEach(
            finalizer -> {
              totalFinalizers[0]++;

              // Finalizer has a 'referent' field pointing to the object being finalized
              Object referent = finalizer.getFieldValue("referent");
              if (referent instanceof HeapObject) {
                HeapObject referentObj = (HeapObject) referent;
                String className =
                    referentObj.getHeapClass() != null
                        ? referentObj.getHeapClass().getName()
                        : "unknown";
                finalizerCountsByClass.merge(className, 1, Integer::sum);
              }
            });

    if (totalFinalizers[0] >= minQueueSize) {
      // Report classes with many finalizers
      for (Map.Entry<String, Integer> entry : finalizerCountsByClass.entrySet()) {
        String className = entry.getKey();
        int count = entry.getValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", className);
        result.put("queueSize", count);
        result.put(
            "suggestion",
            "Objects waiting for finalization. Consider: (1) Avoid finalizers - use "
                + "Cleaner API instead, (2) Check if finalizers are blocking, (3) Monitor finalizer thread.");

        results.add(result);
      }

      // Add overall summary
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("class", "TOTAL");
      summary.put("queueSize", totalFinalizers[0]);
      summary.put(
          "suggestion",
          "Large finalizer queue detected. Finalization may be slow or blocked. "
              + "Check finalizer thread state and consider eliminating finalizers.");
      results.add(0, summary); // Add at beginning
    }

    // Sort by queue size descending (but keep TOTAL at top)
    if (results.size() > 1) {
      List<Map<String, Object>> withoutTotal = results.subList(1, results.size());
      withoutTotal.sort(
          (a, b) -> Integer.compare((Integer) b.get("queueSize"), (Integer) a.get("queueSize")));
    }

    return results;
  }

  @Override
  public String getName() {
    return "finalizer-queue";
  }

  @Override
  public String getDescription() {
    return "Detect objects stuck in the finalizer queue";
  }
}
