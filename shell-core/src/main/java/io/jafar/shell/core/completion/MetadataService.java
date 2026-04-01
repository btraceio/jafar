package io.jafar.shell.core.completion;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Provides metadata about the current session for tab completion. */
public interface MetadataService {
  /** Returns whether there is an active session. */
  boolean hasActiveSession();

  /** Returns the file path of the active session, or null. */
  Path getFilePath();

  /** Returns the available type names in the active session. */
  Set<String> getAvailableTypes();

  /**
   * Returns the field names for the given type.
   *
   * @param typeName the type name (interpretation is module-specific)
   * @return list of field names
   */
  List<String> getFieldNames(String typeName);

  /** Returns the root types supported by the query language. */
  List<String> getRootTypes();

  /** Returns the pipeline operators supported by the query language. */
  List<String> getOperators();

  /** Returns the names of currently defined variables. */
  Set<String> getVariableNames();

  /** Invalidates any cached metadata. */
  void invalidateCache();
}
