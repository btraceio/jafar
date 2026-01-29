package io.jafar.shell.plugin;

/**
 * Exception thrown when plugin installation fails.
 *
 * <p>This can occur due to:
 *
 * <ul>
 *   <li>Network failures (repository unreachable, download timeout)
 *   <li>Artifact not found in repositories
 *   <li>Checksum verification failure
 *   <li>Invalid JAR structure
 *   <li>ServiceLoader discovery failure
 * </ul>
 */
public final class PluginInstallException extends Exception {

  public PluginInstallException(String message) {
    super(message);
  }

  public PluginInstallException(String message, Throwable cause) {
    super(message, cause);
  }
}
