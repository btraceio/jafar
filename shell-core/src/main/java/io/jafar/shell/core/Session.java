package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Abstraction for an analysis session over a data file (JFR recording, heap dump, etc.). */
public interface Session extends AutoCloseable {
  /** Returns the module type identifier (e.g. "jfr", "hdump"). */
  String getType();

  /** Returns the path to the underlying data file. */
  Path getFilePath();

  /** Returns whether this session has been closed. */
  boolean isClosed();

  /** Returns the available type names discovered in this session. */
  Set<String> getAvailableTypes();

  /** Returns session statistics as key-value pairs. */
  Map<String, Object> getStatistics();

  @Override
  void close() throws Exception;
}
