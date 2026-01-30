package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.Set;

/**
 * Base interface for analysis sessions. Both JFR recording sessions and heap dump sessions
 * implement this interface, allowing the shell infrastructure to manage them uniformly.
 */
public interface Session extends AutoCloseable {

  /** Session type identifier (e.g., "jfr", "hdump"). */
  String getType();

  /** The file path being analyzed. */
  Path getFilePath();

  /** Display name for this session (typically the filename). */
  default String getDisplayName() {
    return getFilePath().getFileName().toString();
  }

  /** Whether this session has been closed. */
  boolean isClosed();

  /**
   * Get available queryable type names in this session. For JFR these are event types; for heap
   * dumps these are class names.
   */
  Set<String> getAvailableTypes();

  /**
   * Refresh the session's type discovery by rescanning the file. Useful when metadata may have
   * changed.
   */
  default void refreshTypes() throws Exception {
    // Default no-op; implementations can override
  }

  /**
   * Get session-specific statistics as key-value pairs. Used for the 'info' command.
   *
   * @return map of statistic name to value
   */
  java.util.Map<String, Object> getStatistics();
}
