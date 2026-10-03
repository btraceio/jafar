package io.jafar.mcp.lifecycle;

import java.nio.file.Path;

/**
 * Resolves the directory holding the daemon's state files: the port marker, the auth token and the
 * persisted-sessions store.
 *
 * <p>Defaults to {@code ~/.jafar}. {@code -Djafar.state.dir=<dir>} redirects all of it, which is
 * what lets tests and side-by-side runs avoid touching the real daemon's files.
 */
public final class StateDir {

  /** System property that overrides the state directory. */
  public static final String PROPERTY = "jafar.state.dir";

  private StateDir() {}

  public static Path resolve() {
    String override = System.getProperty(PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home"), ".jafar");
  }
}
