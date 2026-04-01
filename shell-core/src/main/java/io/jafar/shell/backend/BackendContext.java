package io.jafar.shell.backend;

/**
 * Backend-specific context for resource sharing across sessions. Wraps ParsingContext or equivalent
 * for other backends.
 */
public interface BackendContext extends AutoCloseable {

  /** Returns the uptime in nanoseconds since this context was created. */
  long uptime();

  @Override
  void close();
}
