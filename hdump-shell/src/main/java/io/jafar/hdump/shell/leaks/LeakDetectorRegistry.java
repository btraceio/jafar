package io.jafar.hdump.shell.leaks;

import java.util.*;

/**
 * Registry of built-in leak detectors.
 *
 * <p>Provides access to all available leak detectors by name.
 */
public final class LeakDetectorRegistry {

  private static final Map<String, LeakDetector> DETECTORS;

  static {
    Map<String, LeakDetector> detectors = new LinkedHashMap<>();

    // Register all built-in detectors
    register(detectors, new DuplicateStringsDetector());
    register(detectors, new GrowingCollectionsDetector());
    register(detectors, new ThreadLocalLeakDetector());
    register(detectors, new ClassLoaderLeakDetector());
    register(detectors, new ListenerLeakDetector());
    register(detectors, new FinalizerQueueDetector());

    DETECTORS = Collections.unmodifiableMap(detectors);
  }

  private LeakDetectorRegistry() {}

  private static void register(Map<String, LeakDetector> detectors, LeakDetector detector) {
    detectors.put(detector.getName(), detector);
  }

  /**
   * Gets a detector by name.
   *
   * @param name detector name (e.g., "duplicate-strings")
   * @return the detector, or null if not found
   */
  public static LeakDetector getDetector(String name) {
    return DETECTORS.get(name);
  }

  /**
   * Gets all available detector names.
   *
   * @return set of detector names
   */
  public static Set<String> getDetectorNames() {
    return DETECTORS.keySet();
  }

  /**
   * Gets all registered detectors.
   *
   * @return collection of all detectors
   */
  public static Collection<LeakDetector> getAllDetectors() {
    return DETECTORS.values();
  }
}
