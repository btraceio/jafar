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

    // Group listener instances by class
    Map<String, List<HeapObject>> listenersByClass = new HashMap<>();

    dump.getObjects()
        .filter(obj -> obj.getHeapClass() != null && isListenerClass(obj.getHeapClass().getName()))
        .forEach(
            obj -> {
              String className = obj.getHeapClass().getName();
              listenersByClass.computeIfAbsent(className, k -> new ArrayList<>()).add(obj);
            });

    List<Map<String, Object>> results = new ArrayList<>();

    for (Map.Entry<String, List<HeapObject>> entry : listenersByClass.entrySet()) {
      List<HeapObject> instances = entry.getValue();
      if (instances.size() >= minListeners) {
        String className = entry.getKey();
        int count = instances.size();
        long totalRetained = instances.stream().mapToLong(HeapObject::getRetainedSize).sum();
        long totalShallow = instances.stream().mapToLong(HeapObject::getShallowSize).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", className);
        result.put("count", count);
        result.put("shallow", totalShallow);
        result.put("retained", totalRetained);
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
}
