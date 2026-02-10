package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import java.util.*;

/**
 * Detects ClassLoader instances that should have been garbage collected but are retained.
 *
 * <p>ClassLoader leaks are serious because they prevent entire deployed applications from being
 * unloaded, leading to PermGen/Metaspace errors in application servers.
 *
 * <p>Common causes: static references to application classes, ThreadLocal leaks, JDBC driver
 * registration.
 *
 * <p>MinSize parameter: Minimum retained size in bytes to report (default: 10MB)
 */
public final class ClassLoaderLeakDetector implements LeakDetector {

  @Override
  public List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize) {
    long minRetainedBytes = minSize != null ? minSize : 10 * 1024 * 1024; // 10MB default

    List<Map<String, Object>> results = new ArrayList<>();

    // Find all ClassLoader instances (excluding bootstrap and system loaders)
    dump.getObjects()
        .filter(obj -> obj.getHeapClass() != null && isClassLoader(obj.getHeapClass().getName()))
        .filter(obj -> !isSystemClassLoader(obj))
        .filter(obj -> obj.getRetainedSize() >= minRetainedBytes)
        .forEach(
            obj -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("class", obj.getHeapClass().getName());
              result.put("id", obj.getId());
              result.put("shallow", obj.getShallowSize());
              result.put("retained", obj.getRetainedSize());

              // Try to get the classloader name
              Object nameField = obj.getFieldValue("name");
              if (nameField != null) {
                result.put("loaderName", nameField.toString());
              }

              // Count loaded classes
              long loadedClassCount =
                  dump.getClasses().stream()
                      .filter(cls -> cls.getClassLoaderId() == obj.getId())
                      .count();
              result.put("loadedClasses", loadedClassCount);

              result.put(
                  "suggestion",
                  "ClassLoader may be leaked. Common causes: static references, "
                      + "ThreadLocal leaks, JDBC drivers. Check GC root path.");

              results.add(result);
            });

    // Sort by retained size descending
    results.sort((a, b) -> Long.compare((Long) b.get("retained"), (Long) a.get("retained")));

    return results;
  }

  @Override
  public String getName() {
    return "classloader-leak";
  }

  @Override
  public String getDescription() {
    return "Detect ClassLoader instances that should be GC'd but are retained";
  }

  private boolean isClassLoader(String className) {
    return className.contains("ClassLoader") || className.contains("Loader");
  }

  private boolean isSystemClassLoader(HeapObject obj) {
    String className = obj.getHeapClass().getName();
    // Bootstrap, platform, and app classloaders are expected to be retained
    return className.equals("jdk.internal.loader.ClassLoaders$AppClassLoader")
        || className.equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader")
        || className.equals("sun.misc.Launcher$AppClassLoader")
        || className.equals("sun.misc.Launcher$ExtClassLoader");
  }
}
