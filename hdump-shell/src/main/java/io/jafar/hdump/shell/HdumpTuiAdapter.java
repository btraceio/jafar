package io.jafar.hdump.shell;

import io.jafar.hdump.shell.cli.HdumpShellCompleter;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.shell.core.BrowseCategoryDescriptor;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.TuiAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.reader.Completer;

/**
 * TUI adapter for heap dump analysis. Provides HPROF-specific browser categories and command
 * dispatch for the TUI framework.
 */
public final class HdumpTuiAdapter implements TuiAdapter {

  private final SessionManager<?> sessions;
  private final Completer completer;

  public HdumpTuiAdapter(SessionManager<?> sessions) {
    this.sessions = sessions;
    this.completer = new HdumpShellCompleter(sessions);
  }

  @Override
  public void dispatch(String command, CommandIO io) throws Exception {
    // Most queries are now handled by CommandDispatcher via module root-type aliases
    // and the show/cmdShowModule path. This fallback handles any remaining edge cases.
    io.error("Unknown command: " + command.trim());
  }

  @Override
  public Completer getCompleter() {
    return completer;
  }

  @Override
  public Set<String> getBrowsableCategories() {
    return Set.of("classes");
  }

  @Override
  public String detectBrowserCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return null;
    String first = parts[0].toLowerCase();
    // Bare "classes" or "objects" triggers browser (list all classes)
    if ("classes".equals(first) || "objects".equals(first)) return "classes";
    if ("show".equals(first) && parts.length >= 2) {
      String second = parts[1].toLowerCase();
      // Only bare "show classes" / "show objects" triggers browser, not "show classes/..." etc.
      if (parts.length == 2 && ("classes".equals(second) || "objects".equals(second))) {
        return "classes";
      }
    }
    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> loadBrowseSummary(Session session, String category)
      throws Exception {
    if (!(session instanceof HeapSession heap)) return null;
    if (!"classes".equals(category)) return null;

    // Use HdumpPath to query class summary
    var query = HdumpPathParser.parse("classes");
    Object result = HdumpPathEvaluator.evaluate(heap, query);
    if (result instanceof List<?> list) {
      List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
      rows.sort(Comparator.comparing(r -> String.valueOf(r.getOrDefault("name", ""))));
      return rows;
    }
    return null;
  }

  @Override
  public Map<String, Map<String, Object>> loadMetadataClasses(Session session) {
    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> loadBrowseEntries(
      Session session, String category, String typeName, int limit) throws Exception {
    if (!(session instanceof HeapSession heap)) return null;
    if (!"classes".equals(category)) return null;

    int effectiveLimit = limit > 0 ? limit : 100;
    var query = HdumpPathParser.parse("objects/" + typeName);
    Object result = HdumpPathEvaluator.evaluate(heap, query);
    if (result instanceof List<?> list) {
      List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
      if (rows.size() > effectiveLimit) {
        rows = new ArrayList<>(rows.subList(0, effectiveLimit));
      }
      return rows;
    }
    return null;
  }

  @Override
  public BrowseCategoryDescriptor describeBrowseCategory(String category) {
    if ("classes".equals(category)) {
      return new BrowseCategoryDescriptor("Classes", "instanceCount", null, null, false, false);
    }
    return null;
  }

  @Override
  public String getPromptPrefix() {
    return "hdump";
  }
}
