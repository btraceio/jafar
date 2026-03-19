package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.TuiAdapter;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.providers.ConstantPoolProvider;
import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.reader.Completer;

/**
 * TUI adapter for JFR analysis. Provides JFR-specific browser categories (metadata, constants,
 * events), command detection, and data loading for the TUI framework.
 */
public final class JfrTuiAdapter implements TuiAdapter {

  private final SessionManager<?> sessions;
  private final Completer completer;

  @SuppressWarnings("unchecked")
  public JfrTuiAdapter(SessionManager<?> sessions, Object context) {
    this.sessions = sessions;
    CommandDispatcher dispatcher = context instanceof CommandDispatcher cd ? cd : null;
    this.completer =
        new ShellCompleter((SessionManager<JFRSession>) (SessionManager<?>) sessions, dispatcher);
  }

  @Override
  public void dispatch(String command, CommandIO io) throws Exception {
    // JFR commands are handled by CommandDispatcher; this adapter is only used as a fallback
    // when the dispatcher returns false. For JFR sessions, the dispatcher handles everything.
  }

  @Override
  public Completer getCompleter() {
    return completer;
  }

  @Override
  public Set<String> getBrowsableCategories() {
    return Set.of("metadata", "constants", "events");
  }

  @Override
  public String detectBrowserCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return null;

    String first = parts[0].toLowerCase();
    // Metadata browser
    if (isMetadataSummaryCommand(parts, first)) return "metadata";
    // Constants browser
    if (isCpSummaryCommand(parts, first)) return "constants";
    // Events browser
    if (isEventsSummaryCommand(parts, first)) return "events";

    return null;
  }

  @Override
  public List<Map<String, Object>> loadBrowseSummary(Session session, String category)
      throws Exception {
    if (!(session instanceof JFRSession jfr)) return null;
    Path recording = jfr.getRecordingPath();

    return switch (category) {
      case "metadata" -> loadMetadataSummary(jfr);
      case "constants" -> {
        if (!ConstantPoolProvider.isSupported()) yield null;
        yield ConstantPoolProvider.loadSummary(recording);
      }
      case "events" -> loadEventsSummary(recording);
      default -> null;
    };
  }

  @Override
  public Map<String, Map<String, Object>> loadMetadataClasses(Session session) throws Exception {
    if (!(session instanceof JFRSession jfr)) return null;
    if (!MetadataProvider.isSupported()) return null;
    List<Map<String, Object>> allMeta = MetadataProvider.loadAllClasses(jfr.getRecordingPath());
    if (allMeta == null) return null;
    Map<String, Map<String, Object>> byName = new HashMap<>();
    for (Map<String, Object> m : allMeta) {
      Object n = m.get("name");
      if (n != null) byName.put(n.toString(), m);
    }
    return byName;
  }

  @Override
  public List<Map<String, Object>> loadBrowseEntries(
      Session session, String category, String typeName, int limit) throws Exception {
    if (!(session instanceof JFRSession jfr)) return null;

    return switch (category) {
      case "constants" -> ConstantPoolProvider.loadEntries(jfr.getRecordingPath(), typeName);
      case "events" -> {
        var query = JfrPathParser.parse("events/" + typeName);
        int effectiveLimit = limit > 0 ? limit : 500;
        yield new JfrPathEvaluator().evaluateWithLimit(jfr, query, effectiveLimit);
      }
      default -> null;
    };
  }

  @Override
  public boolean isEventsSummaryAsync() {
    return true;
  }

  @Override
  public String getPromptPrefix() {
    return "jfr";
  }

  // ---- private helpers ----

  private List<Map<String, Object>> loadMetadataSummary(JFRSession session) {
    var allTypeNames = session.getNonPrimitiveMetadataTypes();
    var eventNames = session.getAvailableTypes();
    List<String> sortedTypes = new ArrayList<>(allTypeNames);
    sortedTypes.sort(null);

    Map<String, Long> typeIds = session.getMetadataTypeIds();
    List<Map<String, Object>> typeRows = new ArrayList<>();
    for (String t : sortedTypes) {
      Long typeId = typeIds.get(t);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", typeId != null ? typeId : "?");
      row.put("name", t);
      row.put("event", eventNames.contains(t) ? "yes" : "");
      typeRows.add(row);
    }
    return typeRows;
  }

  private List<Map<String, Object>> loadEventsSummary(Path recording) throws Exception {
    Map<String, long[]> counts = new HashMap<>();
    try (var p = ParsingContext.create().newUntypedParser(recording)) {
      p.handle((type, value, ctl) -> counts.computeIfAbsent(type.getName(), k -> new long[1])[0]++);
      p.run();
    }
    List<Map<String, Object>> summary = new ArrayList<>();
    counts.forEach(
        (name, c) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", name);
          row.put("count", c[0]);
          summary.add(row);
        });
    summary.sort(Comparator.comparing(r -> String.valueOf(r.get("name"))));
    return summary;
  }

  // ---- command detection (mirrors TuiBrowserController static methods) ----

  private static boolean isMetadataSummaryCommand(String[] parts, String first) {
    int metaIndex;
    if ("metadata".equals(first) || "types".equals(first)) {
      metaIndex = 0;
    } else if ("show".equals(first)
        && parts.length >= 2
        && ("metadata".equalsIgnoreCase(parts[1]) || "types".equalsIgnoreCase(parts[1]))) {
      metaIndex = 1;
    } else {
      return false;
    }
    for (int i = metaIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if (p.startsWith("--")) {
        if ("--search".equals(p) || "--format".equals(p)) {
          if (i + 1 < parts.length) i++;
        }
        continue;
      }
      return false;
    }
    return true;
  }

  private static boolean isCpSummaryCommand(String[] parts, String first) {
    int cpIndex;
    if ("cp".equals(first) || "constants".equals(first)) {
      cpIndex = 0;
    } else if ("show".equals(first)
        && parts.length >= 2
        && ("cp".equalsIgnoreCase(parts[1]) || "constants".equalsIgnoreCase(parts[1]))) {
      cpIndex = 1;
    } else {
      return false;
    }
    for (int i = cpIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--summary".equals(p) || "--format".equals(p) || "--range".equals(p)) {
        if (("--format".equals(p) || "--range".equals(p)) && i + 1 < parts.length) i++;
        continue;
      }
      return false;
    }
    return true;
  }

  private static boolean isEventsSummaryCommand(String[] parts, String first) {
    int evtIndex;
    if ("events".equals(first)) {
      evtIndex = 0;
    } else if ("show".equals(first) && parts.length >= 2 && "events".equalsIgnoreCase(parts[1])) {
      evtIndex = 1;
    } else {
      return false;
    }
    for (int i = evtIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--format".equals(p) || "--limit".equals(p) || "--range".equals(p)) {
        if (i + 1 < parts.length) i++;
        continue;
      }
      return false;
    }
    return true;
  }
}
