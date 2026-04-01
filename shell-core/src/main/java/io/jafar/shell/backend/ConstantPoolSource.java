package io.jafar.shell.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Abstraction for constant pool queries against a JFR recording. */
public interface ConstantPoolSource {

  /**
   * Load summary of all constant pool types with counts.
   *
   * @param recording path to the JFR recording file
   * @return list of CP type summary rows with name and totalSize, sorted by size descending
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadSummary(Path recording) throws Exception;

  /**
   * Load all entries for a specific constant pool type.
   *
   * @param recording path to the JFR recording file
   * @param typeName the CP type name (e.g., "jdk.types.Symbol")
   * @return list of CP entry rows with id and all fields
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception;

  /**
   * Load constant pool entries matching a filter predicate.
   *
   * @param recording path to the JFR recording file
   * @param typeName the CP type name
   * @param filter predicate to filter entries, or null for all entries
   * @return list of matching CP entry rows
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadEntries(
      Path recording, String typeName, Predicate<Map<String, Object>> filter) throws Exception;

  /**
   * Get available constant pool type names in the recording.
   *
   * @param recording path to the JFR recording file
   * @return set of CP type names
   * @throws Exception if parsing fails
   */
  Set<String> getAvailableTypes(Path recording) throws Exception;

  /**
   * Crosscheck constant pool entries against event references for a given type.
   *
   * <p>Counts total CP entries for the type and how many are actually referenced by events,
   * returning counts and percentages.
   *
   * @param recording path to the JFR recording file
   * @param typeName the CP type name (e.g., "jdk.types.StackTrace")
   * @return map with keys: type, cpTotal, cpReferenced, cpUnused, usedPercent, unusedPercent
   * @throws Exception if parsing fails
   */
  default Map<String, Object> crossref(Path recording, String typeName) throws Exception {
    throw new UnsupportedOperationException("crossref not supported by this backend");
  }
}
