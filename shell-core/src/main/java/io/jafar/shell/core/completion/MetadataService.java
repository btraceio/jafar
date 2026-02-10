package io.jafar.shell.core.completion;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Interface for metadata services that provide completion data. Each module (JFR, hdump) implements
 * this to provide type names, field names, and other metadata for completion.
 */
public interface MetadataService {

  /** Check if there is an active session. */
  boolean hasActiveSession();

  /** Get the current file path, or null if no session. */
  Path getFilePath();

  /** Get available type names (event types for JFR, class names for hdump). */
  Set<String> getAvailableTypes();

  /**
   * Get field names for a given type.
   *
   * @param typeName the type name
   * @return list of field names, or empty list if type not found
   */
  List<String> getFieldNames(String typeName);

  /**
   * Get root types for this module (events/metadata/cp for JFR, objects/classes/gcroots for hdump).
   */
  List<String> getRootTypes();

  /**
   * Get pipeline operator names.
   *
   * @return list of operator names (select, top, groupBy, etc.)
   */
  List<String> getOperators();

  /** Get variable names from the current session. */
  Set<String> getVariableNames();

  /** Invalidate cached metadata. */
  void invalidateCache();
}
