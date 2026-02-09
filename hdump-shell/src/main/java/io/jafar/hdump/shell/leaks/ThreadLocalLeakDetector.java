package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects ThreadLocal instances with large retained sizes still attached to threads.
 *
 * <p>ThreadLocal leaks are common when ThreadLocal.remove() isn't called in finally blocks,
 * especially in thread pool environments where threads are reused.
 *
 * <p>MinSize parameter: Minimum retained size in bytes to report (default: 1MB)
 */
public final class ThreadLocalLeakDetector implements LeakDetector {

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    long minRetainedBytes = minSize != null ? minSize : 1024 * 1024; // 1MB default

    List<Map<String, Object>> results = new ArrayList<>();

    // Find ThreadLocal instances
    dump.getObjectsOfClass("java/lang/ThreadLocal")
        .filter(obj -> obj.getRetainedSize() >= minRetainedBytes)
        .forEach(
            obj -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("class", "java.lang.ThreadLocal");
              result.put("id", obj.getId());
              result.put("shallow", obj.getShallowSize());
              result.put("retained", obj.getRetainedSize());

              // Try to determine if it's still attached to a thread
              List<HeapObject> path = dump.findPathToGcRoot(obj);
              boolean attachedToThread =
                  path.stream()
                      .anyMatch(
                          p ->
                              p.getHeapClass() != null
                                  && "java.lang.Thread".equals(p.getHeapClass().getName()));

              result.put("attachedToThread", attachedToThread);
              result.put(
                  "suggestion",
                  "Ensure ThreadLocal.remove() is called in finally blocks. "
                      + "ThreadLocals in thread pools can cause leaks if not removed.");

              results.add(result);
            });

    // Also check ThreadLocal$ThreadLocalMap$Entry
    dump.getObjectsOfClass("java.lang.ThreadLocal$ThreadLocalMap$Entry")
        .filter(obj -> obj.getRetainedSize() >= minRetainedBytes)
        .forEach(
            obj -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("class", "java.lang.ThreadLocal$ThreadLocalMap$Entry");
              result.put("id", obj.getId());
              result.put("shallow", obj.getShallowSize());
              result.put("retained", obj.getRetainedSize());
              result.put("attachedToThread", true); // Entries are always in ThreadLocalMap

              Object value = obj.getFieldValue("value");
              if (value instanceof HeapObject) {
                HeapObject valueObj = (HeapObject) value;
                result.put(
                    "valueClass",
                    valueObj.getHeapClass() != null
                        ? valueObj.getHeapClass().getName()
                        : "unknown");
              }

              result.put(
                  "suggestion",
                  "ThreadLocal entry still retained. Call ThreadLocal.remove() when done.");

              results.add(result);
            });

    // Sort by retained size descending
    results.sort((a, b) -> Long.compare((Long) b.get("retained"), (Long) a.get("retained")));

    return results;
  }

  @Override
  public String getName() {
    return "threadlocal-leak";
  }

  @Override
  public String getDescription() {
    return "Find ThreadLocal instances with large retained sizes attached to threads";
  }
}
