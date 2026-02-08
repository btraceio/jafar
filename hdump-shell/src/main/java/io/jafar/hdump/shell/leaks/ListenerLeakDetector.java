package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects event listener instances that may not have been deregistered.
 *
 * <p>Listener leaks are common in GUI applications (Swing, JavaFX) and event-driven systems. When
 * listeners aren't removed, they prevent their enclosing objects from being garbage collected.
 *
 * <p>Threshold parameter: Minimum number of listener instances to report (default: 50)
 */
public final class ListenerLeakDetector implements LeakDetector {

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    int minListeners = threshold != null ? threshold : 50;

    // Group listener instances by class - store only metrics to avoid OOME
    Map<String, ListenerGroupMetrics> listenersByClass = new HashMap<>();

    dump.getObjects()
        .filter(obj -> obj.getHeapClass() != null && isListenerClass(obj.getHeapClass().getName()))
        .forEach(
            obj -> {
              String className = obj.getHeapClass().getName();
              listenersByClass
                  .computeIfAbsent(className, k -> new ListenerGroupMetrics())
                  .addInstance(obj);
            });

    List<Map<String, Object>> results = new ArrayList<>();

    for (Map.Entry<String, ListenerGroupMetrics> entry : listenersByClass.entrySet()) {
      ListenerGroupMetrics metrics = entry.getValue();
      if (metrics.count >= minListeners) {
        String className = entry.getKey();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", className);
        result.put("count", metrics.count);
        result.put("shallow", metrics.totalShallow);
        result.put("retained", metrics.totalRetained);
        result.put(
            "suggestion",
            "High listener count may indicate missing deregistration. "
                + "Ensure listeners are removed when no longer needed.");

        results.add(result);
      }
    }

    // Sort by count descending
    results.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));

    return results;
  }

  @Override
  public String getName() {
    return "listener-leak";
  }

  @Override
  public String getDescription() {
    return "Find event listeners that may not have been deregistered";
  }

  private boolean isListenerClass(String className) {
    return className.endsWith("Listener")
        || className.contains("$Listener")
        || className.endsWith("Handler")
        || className.contains("$Handler")
        || className.endsWith("Observer")
        || className.contains("$Observer")
        || className.endsWith("Callback")
        || className.contains("$Callback");
  }

  /**
   * Lightweight metrics tracker for listener groups.
   * Avoids storing full HeapObject references to prevent OOME.
   */
  private static class ListenerGroupMetrics {
    int count = 0;
    long totalShallow = 0;
    long totalRetained = 0;

    void addInstance(HeapObject obj) {
      count++;
      totalShallow += obj.getShallowSize();
      totalRetained += obj.getRetainedSize();
    }
  }
}
