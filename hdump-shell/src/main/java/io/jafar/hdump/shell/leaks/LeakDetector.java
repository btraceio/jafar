package io.jafar.hdump.shell.leaks;

import io.jafar.hdump.api.HeapDump;
import java.util.List;
import java.util.Map;

/**
 * Interface for built-in leak detectors.
 *
 * <p>Each detector analyzes a heap dump and returns a list of suspicious objects or patterns that
 * may indicate memory leaks.
 *
 * <p>Results are returned as maps with detector-specific fields. Common fields:
 *
 * <ul>
 *   <li>{@code count} - Number of instances or occurrences
 *   <li>{@code retained} - Retained size in bytes
 *   <li>{@code shallow} - Shallow size in bytes
 *   <li>{@code class} - Class name
 *   <li>{@code value} - String value or description
 *   <li>{@code suggestion} - Remediation advice
 * </ul>
 */
public interface LeakDetector {

  /**
   * Detects potential memory leaks in the heap dump.
   *
   * @param dump the heap dump to analyze
   * @param threshold optional numeric threshold (detector-specific interpretation)
   * @param minSize optional minimum size threshold (detector-specific interpretation)
   * @return list of leak suspects as maps
   */
  List<Map<String, Object>> detect(HeapDump dump, Integer threshold, Integer minSize);

  /**
   * Returns the detector name for use in queries.
   *
   * @return detector name (e.g., "duplicate-strings")
   */
  String getName();

  /**
   * Returns a brief description of what this detector finds.
   *
   * @return detector description
   */
  String getDescription();
}
