package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jline.reader.Completer;

/**
 * Pluggable shell module that provides support for a specific data format. Discovered via
 * ServiceLoader.
 */
public interface ShellModule {
  /** Returns a unique module identifier (e.g. "jfr", "hdump"). */
  String getId();

  /** Returns a human-readable display name. */
  String getDisplayName();

  /** Returns the set of file extensions this module supports (without leading dot). */
  Set<String> getSupportedExtensions();

  /**
   * Checks whether this module can handle the given file. The default implementation checks the
   * file extension against {@link #getSupportedExtensions()}.
   */
  default boolean canHandle(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    for (String ext : getSupportedExtensions()) {
      if (name.endsWith("." + ext.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Creates a new session for the given file.
   *
   * @param path path to the data file
   * @param context optional context object (module-specific)
   * @return a new session
   * @throws Exception if the file cannot be opened
   */
  Session createSession(Path path, Object context) throws Exception;

  /** Returns the query evaluator for this module's query language. */
  QueryEvaluator getQueryEvaluator();

  /**
   * Returns a JLine completer for this module's query syntax.
   *
   * @param sessions the session manager
   * @param context optional context object (module-specific)
   * @return a completer, or null if not supported
   */
  Completer getCompleter(SessionManager<?> sessions, Object context);

  /** Returns example queries for help display. */
  List<String> getExamples();

  /** Returns the module priority (higher = preferred when multiple modules match). */
  int getPriority();

  /**
   * Creates a TUI adapter for this module, enabling full-screen TUI mode. Modules that don't
   * support TUI mode should return null (the default).
   *
   * @param sessions the session manager
   * @param context optional context object (module-specific)
   * @return a TUI adapter, or null if TUI mode is not supported
   */
  default TuiAdapter createTuiAdapter(SessionManager<?> sessions, Object context) {
    return null;
  }

  /** Called once during module initialization. */
  void initialize();

  /** Called once during module shutdown. */
  void shutdown();
}
