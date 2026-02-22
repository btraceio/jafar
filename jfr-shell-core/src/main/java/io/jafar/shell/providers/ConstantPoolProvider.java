package io.jafar.shell.providers;

import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.ConstantPoolSource;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.UnsupportedCapabilityException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Provider for constant pool browsing and extraction.
 *
 * <p>Delegates to the current backend's {@link ConstantPoolSource} implementation.
 */
public final class ConstantPoolProvider {
  private ConstantPoolProvider() {}

  private static ConstantPoolSource getSource() throws UnsupportedCapabilityException {
    JfrBackend backend = BackendRegistry.getInstance().getCurrent();
    return backend.createConstantPoolSource();
  }

  /**
   * Load summary of all CP types with counts. Returns: name, totalSize (sorted by size desc)
   *
   * @param recording the JFR recording file path
   * @return list of CP type summary rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadSummary(Path recording) throws Exception {
    return getSource().loadSummary(recording);
  }

  /**
   * Load all entries for a specific CP type. Entries include: id, and all fields from the CP entry.
   *
   * @param recording the JFR recording file path
   * @param typeName the CP type name (e.g., "jdk.types.Symbol")
   * @return list of CP entry rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadEntries(Path recording, String typeName)
      throws Exception {
    return getSource().loadEntries(recording, typeName);
  }

  /**
   * Load CP entries matching a filter predicate.
   *
   * @param recording the JFR recording file path
   * @param typeName the CP type name
   * @param rowFilter predicate to filter entries, or null for all entries
   * @return list of matching CP entry rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadEntries(
      Path recording, String typeName, Predicate<Map<String, Object>> rowFilter) throws Exception {
    return getSource().loadEntries(recording, typeName, rowFilter);
  }

  /**
   * Get available CP type names in the recording.
   *
   * @param recording the JFR recording file path
   * @return set of CP type names
   * @throws Exception if parsing fails
   */
  public static Set<String> getAvailableTypes(Path recording) throws Exception {
    return getSource().getAvailableTypes(recording);
  }

  /**
   * Check if constant pool queries are supported by the current backend.
   *
   * @return true if supported
   */
  public static boolean isSupported() {
    return BackendRegistry.getInstance().getCurrent().supports(BackendCapability.CONSTANT_POOLS);
  }
}
