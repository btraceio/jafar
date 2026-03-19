package io.jafar.hdump.shell;

import io.jafar.hdump.shell.cli.HdumpShellCompleter;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
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
  private final HdumpQueryEvaluator evaluator = new HdumpQueryEvaluator();

  public HdumpTuiAdapter(SessionManager<?> sessions) {
    this.sessions = sessions;
    this.completer = new HdumpShellCompleter(sessions);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void dispatch(String command, CommandIO io) throws Exception {
    Session session = sessions.current().map(ref -> ref.session).orElse(null);
    if (session == null) {
      io.error("No session open");
      return;
    }
    // Strip "show " prefix if present — the dispatcher already handles "show" routing
    // but this method is called as a fallback for unrecognized commands
    String expr = command.trim();
    if (expr.toLowerCase().startsWith("show ")) {
      expr = expr.substring(5).trim();
    }
    Object query = evaluator.parse(expr);
    Object result = evaluator.evaluate(session, query);
    if (result instanceof List<?> list) {
      List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
      for (Map<String, Object> row : rows) {
        io.println(row.toString());
      }
    }
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
    if ("classes".equals(first)) return "classes";
    if ("show".equals(first) && parts.length >= 2 && "classes".equalsIgnoreCase(parts[1])) {
      // Only bare "show classes" triggers browser, not "show classes/..."
      if (parts.length == 2) return "classes";
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
  public String getPromptPrefix() {
    return "hdump";
  }
}
