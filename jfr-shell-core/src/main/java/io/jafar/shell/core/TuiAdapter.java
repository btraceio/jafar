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

    /**
     * Renders a list of rows as a tabular result. The TUI will display this as a sortable,
     * scrollable table. Falls back to plain-text output if the runtime does not support tables.
     *
     * @param rows result rows, each a map of column name to value
     */
    default void renderTable(List<Map<String, Object>> rows) {
      if (rows == null || rows.isEmpty()) {
        println("(no rows)");
        return;
      }
      rows.forEach(row -> println(row.toString()));
    }
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
   * Describes the rendering and behavior of a browse category so the TUI framework can display
   * sidebar entries without knowing engine-specific field names.
   *
   * @param category the browser category (from {@link #getBrowsableCategories})
   * @return a descriptor for the category, or null if the category is not recognized
   */
  BrowseCategoryDescriptor describeBrowseCategory(String category);

  /**
   * Returns the default prompt prefix for this module (e.g., "jfr", "hdump"). Used to construct the
   * TUI tab name as "{prefix}>".
   *
   * @return prompt prefix
   */
  String getPromptPrefix();

  /**
   * Looks up a single heap object by its hex ID string. Returns {@code null} if unsupported or not
   * found.
   *
   * @param session the active session
   * @param hexId the object ID as a hex string (no "0x" prefix)
   * @return a row map for the object, or null
   * @throws Exception if lookup fails
   */
  default Map<String, Object> loadObjectById(Session session, String hexId) throws Exception {
    return null;
  }

  /**
   * Returns a confirmation warning if the command would trigger an expensive computation that has
   * not been performed yet, or {@code null} if the command can run immediately without a prompt.
   *
   * <p>The TUI will pause and ask the user to confirm before executing the command if a non-null
   * value is returned.
   *
   * @param command the raw command string
   * @param session the active session, or null if none
   * @return a human-readable warning string, or null if no confirmation is needed
   */
  default String getExpensiveOperationWarning(String command, Session session) {
    return null;
  }

  /**
   * Returns a {@link CommandDescriptor} for the given command word if this adapter exclusively owns
   * it, or {@code null} if the command should be routed through {@link
   * io.jafar.shell.cli.CommandDispatcher} instead. When non-null, the TUI calls {@link #dispatch}
   * directly and uses the descriptor's {@link CommandDescriptor.OutputMode} to select the renderer.
   *
   * @param cmdWord the first token of the command, lowercased
   * @return descriptor, or null if not owned by this adapter
   */
  default CommandDescriptor describeCommand(String cmdWord) {
    return null;
  }
}
