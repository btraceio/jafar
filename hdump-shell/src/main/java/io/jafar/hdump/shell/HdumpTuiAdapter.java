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
import java.util.LinkedHashMap;
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

  /** Stored command for retention paths browser (set by detectBrowserCommand). */
  private volatile String pendingRetentionPathsCommand;

  /** Cached full result rows from retention paths evaluation. */
  private volatile List<Map<String, Object>> cachedRetentionPathRows;

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
    return Set.of("classes", "retentionPaths");
  }

  @Override
  public String detectBrowserCommand(String command) {
    // Retention paths browser: intercept commands containing "| retentionPaths()"
    if (command.contains("| retentionPaths(")) {
      pendingRetentionPathsCommand = command.trim();
      return "retentionPaths";
    }

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

    if ("retentionPaths".equals(category)) {
      return loadRetentionPathsSummary(heap);
    }

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

    if ("retentionPaths".equals(category)) {
      return loadRetentionPathsEntries(typeName, limit);
    }

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
    if ("retentionPaths".equals(category)) {
      return new BrowseCategoryDescriptor("GC Roots", "count", null, null, true, false);
    }
    return null;
  }

  @Override
  public String getPromptPrefix() {
    return "hdump";
  }

  @Override
  public Map<String, Object> loadObjectById(Session session, String hexId) throws Exception {
    if (!(session instanceof HeapSession heap)) return null;
    long id = Long.parseUnsignedLong(hexId, 16);
    return heap.getHeapDump().getObjectById(id).map(HdumpPathEvaluator::objectToRow).orElse(null);
  }

  @Override
  public String getExpensiveOperationWarning(String command, Session session) {
    if (!(session instanceof HeapSession heap)) return null;
    // If retained sizes are already computed, all operations are fast
    if (heap.getHeapDump().hasDominators()) return null;

    String lower = command.toLowerCase().trim();
    // Strip leading "show " prefix
    if (lower.startsWith("show ")) lower = lower.substring(5).trim();

    boolean isExpensive =
        lower.startsWith("clusters")
            || lower.startsWith("ages")
            || lower.contains("| dominators")
            || lower.contains("| estimateage")
            || lower.contains("| age(")
            || lower.contains("| threadowner")
            || lower.contains("| dominatedsize")
            || lower.contains("| pathtoroot")
            || lower.contains("| retentionpaths")
            || lower.contains("| retainedbreakdown")
            || lower.contains("| checkleaks");

    if (!isExpensive) return null;

    long objectCount = heap.getHeapDump().getObjectCount();
    return String.format(
        "Requires computing retained sizes for %,d objects — may take several minutes.",
        objectCount);
  }

  // ---- retention paths browser helpers ----

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> loadRetentionPathsSummary(HeapSession heap) throws Exception {
    String cmd = pendingRetentionPathsCommand;
    if (cmd == null) return null;
    pendingRetentionPathsCommand = null;

    // Strip "show " prefix if present — the parser expects the query part only
    String queryStr = cmd;
    if (queryStr.toLowerCase().startsWith("show ")) {
      queryStr = queryStr.substring(5).trim();
    }

    var query = HdumpPathParser.parse(queryStr);
    List<Map<String, Object>> allRows =
        (List<Map<String, Object>>) (List<?>) HdumpPathEvaluator.evaluate(heap, query);
    cachedRetentionPathRows = allRows;

    // Group by gcRoot: aggregate count and retainedSize
    Map<String, long[]> grouped = new LinkedHashMap<>();
    for (Map<String, Object> row : allRows) {
      String gcRoot = String.valueOf(row.getOrDefault("gcRoot", ""));
      long count = toLong(row.get("count"));
      long retained = toLong(row.get("retainedSize"));
      long[] agg = grouped.computeIfAbsent(gcRoot, k -> new long[2]);
      agg[0] += count;
      agg[1] += retained;
    }

    // Build sidebar rows sorted by retainedSize descending
    List<Map<String, Object>> sidebar = new ArrayList<>();
    for (Map.Entry<String, long[]> e : grouped.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", e.getKey());
      row.put("count", e.getValue()[0]);
      row.put("retainedSize", e.getValue()[1]);
      sidebar.add(row);
    }
    sidebar.sort(
        (a, b) -> Long.compare(toLong(b.get("retainedSize")), toLong(a.get("retainedSize"))));
    return sidebar;
  }

  private List<Map<String, Object>> loadRetentionPathsEntries(String typeName, int limit) {
    if (cachedRetentionPathRows == null) return null;

    int effectiveLimit = limit > 0 ? limit : 500;
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Map<String, Object> row : cachedRetentionPathRows) {
      if (!typeName.equals(String.valueOf(row.getOrDefault("gcRoot", "")))) continue;
      Map<String, Object> entry = new LinkedHashMap<>(row);
      entry.put("name", row.getOrDefault("leaf", ""));
      entry.remove("gcRoot");
      entry.remove("leaf");
      entry.remove("path");
      entries.add(entry);
      if (entries.size() >= effectiveLimit) break;
    }
    return entries;
  }

  private static long toLong(Object v) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        return 0L;
      }
    }
    return 0L;
  }
}
