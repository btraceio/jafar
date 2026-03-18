package io.jafar.shell.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.reader.Completer;

/**
 * Adapter interface that modules implement to provide TUI-specific capabilities. Abstracts over
 * module-specific command dispatch, browsing, and completion so the TUI framework can work with any
 * module.
 *
 * <p>Modules that don't support TUI mode return {@code null} from {@link
 * ShellModule#createTuiAdapter}.
 */
public interface TuiAdapter {

  /** I/O callback for command output. */
  interface CommandIO {
    void println(String s);

    void printf(String fmt, Object... args);

    void error(String s);
  }

  // ---- Command dispatch ----

  /**
   * Dispatches a command string, writing output via the IO callback.
   *
   * @param command the raw command string
   * @param io output callback
   * @throws Exception if command execution fails
   */
  void dispatch(String command, CommandIO io) throws Exception;

  /**
   * Returns a JLine completer for the TUI input line.
   *
   * @return a completer, or null if completion is not supported
   */
  Completer getCompleter();

  // ---- Browser mode detection ----

  /**
   * Returns the set of browsable categories this module supports (e.g., "metadata", "constants",
   * "events" for JFR; "classes", "gcroots" for heap dumps). Used to determine which browser modes
   * are available.
   *
   * @return set of category names, never null
   */
  Set<String> getBrowsableCategories();

  /**
   * Checks whether a command should trigger a browser summary mode. For example, "metadata" or
   * "types" might trigger a metadata browser, "constants" or "cp" might trigger a constant pool
   * browser.
   *
   * @param command the raw command string
   * @return the browser category to enter, or null if this is not a browser command
   */
  String detectBrowserCommand(String command);

  // ---- Browser data loading ----

  /**
   * Loads summary data for a browser category. Returns structured rows suitable for the TUI sidebar
   * and table display.
   *
   * <p>For JFR "metadata": rows with id, name, event columns. For JFR "constants": rows with type,
   * count columns. For JFR "events": rows with name, count columns (may be expensive — see {@link
   * #loadEventsSummaryAsync}).
   *
   * @param session the active session
   * @param category the browser category (from {@link #getBrowsableCategories})
   * @return summary rows, or null if the category is not supported
   * @throws Exception if loading fails
   */
  List<Map<String, Object>> loadBrowseSummary(Session session, String category) throws Exception;

  /**
   * Returns metadata class details for the metadata browser. Keyed by class name, each value is a
   * map of class metadata (fields, superType, etc.). Returns null if not applicable.
   *
   * @param session the active session
   * @return metadata class map, or null
   * @throws Exception if loading fails
   */
  Map<String, Map<String, Object>> loadMetadataClasses(Session session) throws Exception;

  /**
   * Loads entries for a specific type within a browser category. For JFR constants: loads all
   * constant pool entries of the given type. For JFR events: loads event instances of the given
   * type.
   *
   * @param session the active session
   * @param category the browser category
   * @param typeName the specific type to load entries for
   * @param limit maximum number of entries to return, or -1 for no limit
   * @return entry rows, or null if not supported
   * @throws Exception if loading fails
   */
  List<Map<String, Object>> loadBrowseEntries(
      Session session, String category, String typeName, int limit) throws Exception;

  /**
   * Whether the events summary should be loaded asynchronously. Event scanning can be expensive
   * (full file parse), so the TUI runs it in a background thread.
   *
   * @return true if {@link #loadBrowseSummary} for the "events" category is expensive
   */
  default boolean isEventsSummaryAsync() {
    return false;
  }

  /**
   * Returns the default prompt prefix for this module (e.g., "jfr", "hdump"). Used to construct the
   * TUI tab name as "{prefix}>".
   *
   * @return prompt prefix
   */
  String getPromptPrefix();
}
