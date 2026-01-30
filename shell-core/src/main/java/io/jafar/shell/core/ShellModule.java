package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.Set;

/**
 * Service Provider Interface (SPI) for shell domain modules. Each module provides support for a
 * specific data format (e.g., JFR recordings, heap dumps) and contributes:
 *
 * <ul>
 *   <li>Session factory for loading files
 *   <li>Query language support (parser and evaluator)
 *   <li>Tab completion providers
 *   <li>Format-specific commands
 * </ul>
 *
 * <p>Modules are discovered via {@link java.util.ServiceLoader}.
 */
public interface ShellModule {

  /** Unique identifier for this module (e.g., "jfr", "hdump"). */
  String getId();

  /** Human-readable name for this module. */
  String getDisplayName();

  /** File extensions this module supports (without the dot, e.g., "jfr", "hprof"). */
  Set<String> getSupportedExtensions();

  /**
   * Checks if this module can handle the given file. By default, checks file extension, but
   * implementations may also check magic bytes for more robust detection.
   *
   * @param path file to check
   * @return true if this module can handle the file
   */
  default boolean canHandle(Path path) {
    String fileName = path.getFileName().toString().toLowerCase();
    return getSupportedExtensions().stream()
        .anyMatch(ext -> fileName.endsWith("." + ext.toLowerCase()));
  }

  /**
   * Creates a session for the given file.
   *
   * @param path file to open
   * @param context module-specific context (may be null)
   * @return a new session
   * @throws Exception if the session cannot be created
   */
  Session createSession(Path path, Object context) throws Exception;

  /**
   * Returns the query evaluator for this module, if query support is available.
   *
   * @return query evaluator, or null if queries are not supported
   */
  default QueryEvaluator getQueryEvaluator() {
    return null;
  }

  /**
   * Returns the priority of this module. Higher priority modules are preferred when multiple
   * modules can handle the same file.
   *
   * @return priority value (default 0)
   */
  default int getPriority() {
    return 0;
  }

  /** Called when the module is loaded. Use for initialization. */
  default void initialize() {}

  /** Called when the shell is shutting down. Use for cleanup. */
  default void shutdown() {}
}
