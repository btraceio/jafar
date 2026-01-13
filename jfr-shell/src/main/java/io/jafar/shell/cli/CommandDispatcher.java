package io.jafar.shell.cli;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.LazyQueryValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.llm.ContextBuilder;
import io.jafar.shell.llm.ConversationHistory;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;
import io.jafar.shell.llm.LLMProvider;
import io.jafar.shell.llm.QueryTranslator;
import io.jafar.shell.llm.TranslationResult;
import io.jafar.shell.llm.privacy.AuditLogger;
import io.jafar.shell.providers.ChunkProvider;
import io.jafar.shell.providers.ConstantPoolProvider;
import io.jafar.shell.providers.MetadataProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Minimal command dispatcher for M1 session commands. */
public class CommandDispatcher {
  public interface IO {
    void println(String s);

    void printf(String fmt, Object... args);

    void error(String s);
  }

  public interface SessionChangeListener {
    void onCurrentSessionChanged(SessionManager.SessionRef current);
  }

  private final SessionManager sessions;
  private final IO io;
  private final SessionChangeListener listener;
  private final VariableStore globalStore;
  private final ConditionalState conditionalState = new ConditionalState();
  private final ChatState chatState = new ChatState();
  private final boolean verbose;

  @FunctionalInterface
  public interface JfrSelector {
    List<java.util.Map<String, Object>> select(JFRSession session, String expr) throws Exception;
  }

  private final JfrSelector selector;

  public CommandDispatcher(SessionManager sessions, IO io, SessionChangeListener listener) {
    this(sessions, io, listener, null, null, true);
  }

  public CommandDispatcher(
      SessionManager sessions, IO io, SessionChangeListener listener, JfrSelector selector) {
    this(sessions, io, listener, selector, null, true);
  }

  public CommandDispatcher(
      SessionManager sessions,
      IO io,
      SessionChangeListener listener,
      JfrSelector selector,
      VariableStore globalStore) {
    this(sessions, io, listener, selector, globalStore, true);
  }

  public CommandDispatcher(
      SessionManager sessions,
      IO io,
      SessionChangeListener listener,
      JfrSelector selector,
      VariableStore globalStore,
      boolean verbose) {
    this.sessions = sessions;
    this.io = io;
    this.listener = listener;
    this.selector = selector;
    this.globalStore = globalStore != null ? globalStore : new VariableStore();
    this.verbose = verbose || isVerboseEnabled();
  }

  /**
   * Check if verbose mode is enabled via system property or environment variable. This allows
   * runtime control of verbosity for debugging.
   */
  private static boolean isVerboseEnabled() {
    String prop = System.getProperty("jfr.shell.verbose");
    if (prop != null) {
      String v = prop.trim().toLowerCase();
      return v.equals("1") || v.equals("on") || v.equals("true");
    }
    String env = System.getenv("JFR_SHELL_VERBOSE");
    if (env != null) {
      String v = env.trim().toLowerCase();
      return v.equals("1") || v.equals("on") || v.equals("true");
    }
    return false;
  }

  /** Returns the global variable store. */
  public VariableStore getGlobalStore() {
    return globalStore;
  }

  /** Returns the conditional state for tracking if-blocks. */
  public ConditionalState getConditionalState() {
    return conditionalState;
  }

  /** Returns the chat state for tracking conversational mode. */
  public ChatState getChatState() {
    return chatState;
  }

  public boolean dispatch(String line) {
    String[] parts = line.trim().split("\\s+");
    if (parts.length == 0 || parts[0].isEmpty()) return true;

    String cmd = parts[0].toLowerCase(Locale.ROOT);
    List<String> args = Arrays.asList(parts).subList(1, parts.length);

    try {
      // Handle conditional keywords - these must be processed even in inactive branches
      if ("if".equals(cmd)) {
        return handleIf(line);
      }
      if ("elif".equals(cmd)) {
        return handleElif(line);
      }
      if ("else".equals(cmd)) {
        conditionalState.handleElse();
        return true;
      }
      if ("endif".equals(cmd)) {
        conditionalState.exitIf();
        return true;
      }

      // Skip command if in inactive conditional branch
      if (!conditionalState.isActive()) {
        return true; // Command "handled" (skipped)
      }

      switch (cmd) {
        case "open":
          cmdOpen(args);
          return true;
        case "sessions":
          cmdSessions();
          return true;
        case "use":
          cmdUse(args);
          return true;
        case "close":
          cmdClose(args);
          return true;
        case "info":
          cmdInfo(args);
          return true;
        case "show":
          cmdShow(args, line);
          return true;
        case "select":
          if (verbose) {
            io.println("Note: 'select' is deprecated. Use 'show' instead.");
          }
          cmdShow(args, line);
          return true;
        case "help":
          cmdHelp(args);
          return true;
        case "metadata":
          // Support: 'metadata class <name> [--tree|--json] [--fields] [--annotations]' and listing
          if (!args.isEmpty() && "class".equalsIgnoreCase(args.get(0))) {
            cmdMetadataClass(args.subList(1, args.size()));
          } else {
            cmdTypes(args);
          }
          return true;
        case "types":
          if (verbose) {
            io.println("Note: 'types' is deprecated. Use 'metadata' instead.");
          }
          cmdTypes(args);
          return true;
        case "chunks":
          cmdChunks(args);
          return true;
        case "chunk":
          cmdChunk(args);
          return true;
        case "cp":
          cmdCp(args);
          return true;
        case "set":
        case "let":
          cmdSet(args, line);
          return true;
        case "vars":
          cmdVars(args);
          return true;
        case "unset":
          cmdUnset(args);
          return true;
        case "echo":
          cmdEcho(args, line);
          return true;
        case "invalidate":
          cmdInvalidate(args);
          return true;
        case "chat":
          cmdChat(args);
          return true;
        case "llm":
          cmdLLM(args);
          return true;
        default:
          return false;
      }
    } catch (Exception e) {
      io.error("Error: " + e.getMessage());
      return true;
    }
  }

  private void cmdOpen(List<String> args) throws Exception {
    if (args.isEmpty()) {
      io.error("Usage: open <path> [--alias NAME]");
      return;
    }
    String pathStr = args.get(0);
    String alias = null;
    if (args.size() >= 3 && "--alias".equals(args.get(1))) {
      alias = args.get(2);
    }

    // Expand ~ to user home directory
    if (pathStr.startsWith("~/")) {
      pathStr = System.getProperty("user.home") + pathStr.substring(1);
    } else if ("~".equals(pathStr)) {
      pathStr = System.getProperty("user.home");
    }

    Path path = Paths.get(pathStr);
    SessionManager.SessionRef ref = sessions.open(path, alias);
    if (verbose) {
      io.println(
          "Opened session #"
              + ref.id
              + (ref.alias != null ? " (" + ref.alias + ")" : "")
              + ": "
              + ref.session.getRecordingPath());
    }
    listener.onCurrentSessionChanged(ref);
  }

  private void cmdSessions() {
    List<SessionManager.SessionRef> list = sessions.list();
    if (list.isEmpty()) {
      io.println("No sessions.");
      return;
    }
    Optional<SessionManager.SessionRef> current = sessions.current();
    for (SessionManager.SessionRef ref : list) {
      boolean isCurrent = current.isPresent() && current.get().id == ref.id;
      io.printf(
          "%s#%d %s - %s%n",
          isCurrent ? "*" : " ",
          ref.id,
          ref.alias != null ? ref.alias : "",
          String.valueOf(ref.session.getRecordingPath()));
    }
  }

  private void cmdUse(List<String> args) {
    if (args.isEmpty()) {
      io.error("Usage: use <id|alias>");
      return;
    }
    String key = args.get(0);
    boolean ok = sessions.use(key);
    if (!ok) {
      io.error("No such session: " + key);
    } else {
      sessions.current().ifPresent(listener::onCurrentSessionChanged);
    }
  }

  private void cmdClose(List<String> args) throws Exception {
    if (args.isEmpty()) {
      // close current
      Optional<SessionManager.SessionRef> cur = sessions.current();
      if (cur.isEmpty()) {
        io.error("No session to close");
        return;
      }
      sessions.close(String.valueOf(cur.get().id));
      if (verbose) {
        io.println("Closed session #" + cur.get().id);
      }
    } else if (args.size() == 1 && "--all".equals(args.get(0))) {
      sessions.closeAll();
      if (verbose) {
        io.println("Closed all sessions");
      }
    } else {
      String key = args.get(0);
      boolean ok = sessions.close(key);
      if (!ok) io.error("No such session: " + key);
      else if (verbose) io.println("Closed session " + key);
    }
    sessions.current().ifPresent(listener::onCurrentSessionChanged);
  }

  private void cmdInfo(List<String> args) {
    Optional<SessionManager.SessionRef> ref;
    if (args.isEmpty()) {
      ref = sessions.current();
      if (ref.isEmpty()) {
        io.error("No session open");
        return;
      }
    } else {
      ref = sessions.get(args.get(0));
      if (ref.isEmpty()) {
        io.error("No such session: " + args.get(0));
        return;
      }
    }
    JFRSession s = ref.get().session;
    io.println("Session Information:");
    io.println("  Recording: " + s.getRecordingPath());
    io.println("  Event Types: " + s.getAvailableEventTypes().size());
    io.println("  Handlers: " + s.getHandlerCount());
    io.println("  Has Run: " + s.hasRun());
    if (s.hasRun()) {
      io.println("  Total Events Processed: " + s.getTotalEvents());
      io.println("  Uptime: " + (s.getUptime() / 1_000_000) + "ms");
    }
  }

  private void cmdShow(List<String> args, String fullLine) throws Exception {
    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open");
      return;
    }
    // parse options: support --limit N, --format json, and metadata-only: --tree [--depth N]
    Integer limit = null;
    String format = cur.get().outputFormat;
    boolean tree = false;
    Integer depth = null;
    JfrPath.MatchMode listMatchMode = JfrPath.MatchMode.ANY;
    List<String> tokens = args;
    for (int i = 0; i < tokens.size(); i++) {
      String t = tokens.get(i);
      if ("--limit".equals(t) && i + 1 < tokens.size()) {
        limit = Integer.parseInt(tokens.get(i + 1));
        tokens = new java.util.ArrayList<>(tokens);
        tokens.remove(i + 1);
        tokens.remove(i);
        i -= 1;
        continue;
      }
      if ("--format".equals(t) && i + 1 < tokens.size()) {
        format = tokens.get(i + 1);
        tokens = new java.util.ArrayList<>(tokens);
        tokens.remove(i + 1);
        tokens.remove(i);
        i -= 1;
        continue;
      }
      if ("--tree".equals(t)) {
        tree = true;
        tokens = new java.util.ArrayList<>(tokens);
        tokens.remove(i);
        i -= 1;
        continue;
      }
      if ("--depth".equals(t) && i + 1 < tokens.size()) {
        try {
          depth = Integer.parseInt(tokens.get(i + 1));
        } catch (NumberFormatException nfe) {
          io.error("Invalid --depth value: " + tokens.get(i + 1));
          return;
        }
        tokens = new java.util.ArrayList<>(tokens);
        tokens.remove(i + 1);
        tokens.remove(i);
        i -= 1;
        continue;
      }
      if ("--list-match".equals(t) && i + 1 < tokens.size()) {
        String mm = tokens.get(i + 1).toLowerCase(java.util.Locale.ROOT);
        switch (mm) {
          case "any" -> listMatchMode = JfrPath.MatchMode.ANY;
          case "all" -> listMatchMode = JfrPath.MatchMode.ALL;
          case "none" -> listMatchMode = JfrPath.MatchMode.NONE;
          default -> {
            io.error("Invalid --list-match value: " + tokens.get(i + 1));
            return;
          }
        }
        tokens = new java.util.ArrayList<>(tokens);
        tokens.remove(i + 1);
        tokens.remove(i);
        i -= 1;
        continue;
      }
    }
    if (tokens.isEmpty()) {
      io.error(
          "Usage: show <expr> [--limit N] [--format table|json|csv] [--tree] [--depth N] [--list-match any|all|none]");
      return;
    }
    // Extract expression from fullLine to preserve pipe operators that get lost in tokenization
    String expr;
    int showIdx = fullLine.indexOf("show");
    if (showIdx >= 0) {
      String afterShow = fullLine.substring(showIdx + 4).trim();
      // Remove options from the beginning/end while preserving the core expression
      expr =
          afterShow
              .replaceAll("--limit\\s+\\d+", "")
              .replaceAll("--format\\s+\\S+", "")
              .replaceAll("--tree", "")
              .replaceAll("--depth\\s+\\d+", "")
              .replaceAll("--list-match\\s+\\S+", "")
              .trim();
    } else {
      expr = String.join(" ", tokens);
    }

    // Check for piping from lazy variable: ${var} | pipeline
    java.util.regex.Pattern varPipePattern =
        java.util.regex.Pattern.compile("^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\s*\\|(.+)$");
    java.util.regex.Matcher varPipeMatcher = varPipePattern.matcher(expr.trim());
    if (varPipeMatcher.matches()) {
      String varName = varPipeMatcher.group(1);
      String pipelinePart = varPipeMatcher.group(2).trim();

      // Look up the variable
      Value val = null;
      if (getSessionStore() != null && getSessionStore().contains(varName)) {
        val = getSessionStore().get(varName);
      } else if (globalStore.contains(varName)) {
        val = globalStore.get(varName);
      }

      if (val instanceof LazyQueryValue lqv) {
        try {
          // Get cached result
          Object cached = lqv.get();
          if (cached instanceof List<?> cachedList) {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> rows =
                (List<java.util.Map<String, Object>>) cachedList;

            // Parse the pipeline part using a dummy expression
            String dummyExpr = "events/_dummy | " + pipelinePart;
            var dummyQuery = JfrPathParser.parse(dummyExpr);
            if (dummyQuery.pipeline != null && !dummyQuery.pipeline.isEmpty()) {
              var eval = new JfrPathEvaluator(listMatchMode);
              rows = eval.applyToRows(rows, dummyQuery.pipeline);
              if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
              if ("json".equalsIgnoreCase(format)) {
                printJson(rows, io);
              } else if ("csv".equalsIgnoreCase(format)) {
                CsvRenderer.render(rows, io);
              } else {
                TableRenderer.render(rows, io);
              }
              return;
            }
          }
        } catch (Exception e) {
          io.error("Error piping from variable: " + e.getMessage());
          return;
        }
      }
    }

    // Substitute variables if present
    if (VariableSubstitutor.hasVariables(expr)) {
      VariableSubstitutor sub = new VariableSubstitutor(getSessionStore(), globalStore);
      expr = sub.substitute(expr);
    }

    // Evaluate
    if (selector != null) {
      List<java.util.Map<String, Object>> rows = selector.select(cur.get().session, expr);
      if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
      if ("json".equalsIgnoreCase(format)) {
        printJson(rows, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(rows, io);
      } else {
        TableRenderer.render(rows, io);
      }
      return;
    }
    var q = JfrPathParser.parse(expr);
    var eval = new JfrPathEvaluator(listMatchMode);
    // If aggregation pipeline present, always evaluate as rows (preempts other handlers)
    if (q.pipeline != null && !q.pipeline.isEmpty()) {
      var rows = eval.evaluate(cur.get().session, q);
      if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
      if ("json".equalsIgnoreCase(format)) {
        printJson(rows, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(rows, io);
      } else {
        TableRenderer.render(rows, io);
      }
      return;
    }

    // metadata tree rendering via --tree
    if (tree && q.root == JfrPath.Root.METADATA) {
      if (q.segments.isEmpty()) {
        io.error("--tree requires 'metadata/<type>' expression");
        return;
      }
      String typeName = q.segments.get(0);
      int maxDepth = depth != null ? Math.max(0, depth) : 10;
      // If path targets a specific field, render field-focused tree
      if (q.segments.size() >= 2) {
        String seg1 = q.segments.get(1);
        String fieldName = null;
        if ("fields".equals(seg1) || "fieldsByName".equals(seg1)) {
          if (q.segments.size() >= 3) {
            fieldName = q.segments.get(2);
          }
        } else if (seg1.startsWith("fields.")) {
          fieldName = seg1.substring("fields.".length());
        } else if (seg1.startsWith("fieldsByName.")) {
          fieldName = seg1.substring("fieldsByName.".length());
        }
        if (fieldName != null && !fieldName.isEmpty()) {
          TreeRenderer.renderFieldRecursive(
              cur.get().session.getRecordingPath(), typeName, fieldName, io, maxDepth);
          return;
        }
      }
      // Otherwise, render the class tree for the requested type
      TreeRenderer.renderMetadataRecursive(
          cur.get().session.getRecordingPath(), typeName, io, maxDepth);
      return;
    }
    if (q.root == JfrPath.Root.METADATA && q.segments.isEmpty()) {
      // Equivalent of 'types'
      cmdTypes(java.util.List.of());
      return;
    }
    if (q.root == JfrPath.Root.METADATA && q.segments.size() == 2) {
      String typeName = q.segments.get(0);
      String fieldName = q.segments.get(1);
      if ("fields".equals(fieldName) || "fieldsByName".equals(fieldName)) {
        // Delegate to generic evaluator to support 'fields' and 'fieldsByName'
        var values = eval.evaluateValues(cur.get().session, q);
        if (limit != null && limit < values.size()) values = values.subList(0, limit);
        if ("json".equalsIgnoreCase(format)) {
          printJson(values, io);
        } else if ("csv".equalsIgnoreCase(format)) {
          CsvRenderer.renderValues(values, io);
        } else {
          TableRenderer.renderValues(values, io);
        }
        return;
      }
      var evalMeta = new JfrPathEvaluator();
      Map<String, Object> fm =
          evalMeta.loadFieldMetadata(cur.get().session.getRecordingPath(), typeName, fieldName);
      if (fm == null) {
        io.println("(no rows)");
      } else {
        if ("json".equalsIgnoreCase(format)) {
          printJson(fm, io);
        } else if ("csv".equalsIgnoreCase(format)) {
          java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(fm);
          copy.remove("annotationsFull");
          CsvRenderer.render(java.util.List.of(copy), io);
        } else {
          // Hide internal columns for field-level table view
          java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(fm);
          copy.remove("annotationsFull");
          TableRenderer.render(java.util.List.of(copy), io);
        }
      }
      return;
    }
    if (q.segments.size() > 1) {
      var values =
          (q.root == JfrPath.Root.EVENTS)
              ? eval.evaluateValuesWithLimit(cur.get().session, q, limit)
              : eval.evaluateValues(cur.get().session, q);
      if (limit != null && limit < values.size()) values = values.subList(0, limit);
      if ("json".equalsIgnoreCase(format)) {
        printJson(values, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.renderValues(values, io);
      } else {
        TableRenderer.renderValues(values, io);
      }
    } else {
      var rows =
          (q.root == JfrPath.Root.EVENTS)
              ? eval.evaluateWithLimit(cur.get().session, q, limit)
              : eval.evaluate(cur.get().session, q);
      if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
      if ("json".equalsIgnoreCase(format)) {
        printJson(rows, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        // Hide internal columns for metadata tables (CSV view)
        if (q.root == JfrPath.Root.METADATA && !rows.isEmpty()) {
          java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(rows.get(0));
          copy.remove("fieldsByName");
          copy.remove("classAnnotations");
          copy.remove("classAnnotationsFull");
          copy.remove("settings");
          copy.remove("settingsByName");
          copy.remove("fieldCount");
          rows = java.util.List.of(copy);
        }
        CsvRenderer.render(rows, io);
      } else {
        // Hide internal columns for metadata tables (default table view)
        if (q.root == JfrPath.Root.METADATA && !rows.isEmpty()) {
          java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(rows.get(0));
          copy.remove("fieldsByName");
          copy.remove("classAnnotations");
          copy.remove("classAnnotationsFull");
          copy.remove("settings");
          copy.remove("settingsByName");
          copy.remove("fieldCount");
          rows = java.util.List.of(copy);
        }
        TableRenderer.render(rows, io);
      }
    }
  }

  private void cmdHelp(List<String> args) {
    if (args.isEmpty()) {
      io.println("Available commands:");
      io.println("  show      - Execute JfrPath queries (events, metadata, chunks, cp)");
      io.println("  metadata  - List and inspect metadata types");
      io.println("  chunks    - List chunk information");
      io.println("  chunk     - Show specific chunk details");
      io.println("  cp        - Browse constant pool entries");
      io.println("");
      io.println("Natural language queries:");
      io.println("  chat      - Enter conversational mode for LLM-powered analysis");
      io.println("  llm       - LLM configuration and status commands");
      io.println("");
      io.println("Variable commands:");
      io.println("  set       - Assign variable or set session options");
      io.println("  vars      - List all defined variables");
      io.println("  unset     - Remove a variable");
      io.println("  echo      - Print text with variable substitution");
      io.println("  invalidate - Clear cached result for lazy variable");
      io.println("");
      io.println("Conditionals:");
      io.println("  if        - Start conditional block");
      io.println("  elif      - Else-if branch");
      io.println("  else      - Else branch");
      io.println("  endif     - End conditional block");
      io.println("");
      io.println("Tab completion:");
      io.println("  Press Tab at any point for context-aware suggestions:");
      io.println("  - Query roots: events/, metadata/, cp/, chunks/");
      io.println("  - Event types, field paths, and nested fields");
      io.println("  - Filter operators, functions, and logical operators");
      io.println("  - Pipeline operators and function parameters");
      io.println("  - Command options (--limit, --format, etc.)");
      io.println("");
      io.println("Type 'help <command>' for detailed usage (e.g., 'help show', 'help ask')");
      return;
    }
    String sub = args.get(0).toLowerCase(Locale.ROOT);
    if ("show".equals(sub) || "select".equals(sub)) {
      io.println(
          "Usage: show <expr> [--limit N] [--format table|json|csv] [--tree] [--depth N] [--list-match any|all|none]");
      io.println("Where <expr> is a JfrPath like:");
      io.println("  events/<type>[field/path op literal][...]");
      io.println("  metadata/<type>[/field][...]   (use --tree [--depth N] for recursive tree)");
      io.println("    Aliases for fields: fields.<name>, fieldsByName.<name>");
      io.println("  chunks[/field]");
      io.println("  cp[/<type>][/field]");
      io.println("Operators: = != > >= < <= ~ (regex)");
      io.println("Filter functions (inside [ ... ]):");
      io.println("  contains(path, \"s\"), starts_with(path, \"pre\"), ends_with(path, \"suf\")");
      io.println(
          "  matches(path, \"re\"[, \"i\"]), exists(path), empty(path), between(path, a, b)");
      io.println("  len(path) usable in comparisons (e.g., len(frames)>10)");
      io.println("Pipeline functions (append with '|'):");
      io.println("  Aggregations:");
      io.println("    | count()                  → number of rows/events");
      io.println("    | sum([path])              → sum of numeric values");
      io.println("    | stats([path])            → min,max,avg,stddev for numeric values");
      io.println("    | quantiles(q1,q2[,path=]) → pXX columns at requested quantiles");
      io.println("    | sketch([path])           → stats + p50,p90,p99");
      io.println(
          "    | groupBy(key[, agg=count|sum|avg|min|max, value=path]) → group by key and aggregate");
      io.println("    | top(n[, by=path, asc=false]) → top N rows sorted by path");
      io.println("  Field projection:");
      io.println("    | select(field1, field2, ...) → project specific fields");
      io.println("    | select(field as alias)      → rename fields");
      io.println("    | select(expr as alias)       → computed expressions (alias required)");
      io.println("      Expressions support:");
      io.println("        - Arithmetic: +, -, *, /");
      io.println("        - String concat: + (when operand is string)");
      io.println("        - String templates: \"text ${expr} more text\"");
      io.println("        - Functions: if(), upper(), lower(), substring(), length(), coalesce()");
      io.println("      Examples:");
      io.println("        | select(path, bytes / 1024 as kb)");
      io.println("        | select(path + ' (' + bytes + ')' as description)");
      io.println("        | select(\"${path} (${bytes} bytes)\" as description)");
      io.println("        | select(if(bytes > 1000, 'large', 'small') as size)");
      io.println(
          "    | toMap(keyField, valueField) → convert rows to map (last value wins for duplicates)");
      io.println("      Examples:");
      io.println(
          "        set config = events/jdk.ActiveSetting | select(name, value) | toMap(name, value)");
      io.println("        echo CPU engine: ${config.cpuEngine}");
      io.println(
          "    merge(${map1}, ${map2}, ...) → merge multiple maps (last wins for duplicates)");
      io.println("      Examples:");
      io.println(
          "        set config = events/jdk.ActiveSetting | select(name, value) | toMap(name, value)");
      io.println(
          "        set stats = events/jdk.GCHeapSummary | select(when, heapUsed) | toMap(when, heapUsed)");
      io.println("        set report = merge(${config}, ${stats}, {\"date\": \"2024-01-15\"})");
      io.println("        echo Date: ${report.date}, CPU: ${report.cpuEngine}");
      io.println("  Value transforms (also usable in filters where applicable):");
      io.println("    | len([path])              → length of string or list/array attribute");
      io.println("    | uppercase([path])        → string uppercased");
      io.println("    | lowercase([path])        → string lowercased");
      io.println("    | trim([path])             → string trimmed");
      io.println("    | abs([path])              → absolute value of number");
      io.println("    | round([path])            → round to nearest integer");
      io.println("    | floor([path])            → floor for numbers");
      io.println("    | ceil([path])             → ceil for numbers");
      io.println("    | contains([path], \"s\")  → boolean per row (string contains substring)");
      io.println("    | replace([path], \"a\", \"b\") → string replace occurrences");
      io.println("List match modes (for array/list fields):");
      io.println("  Prefix filter with any: (default), all:, or none: to control matching");
      io.println("  Example: [any:stackTrace/frames[matches(method/name/string, \".*Foo.*\")]]");
      io.println("");
      io.println("Examples (grouped by use case):");
      io.println("  Basic event queries:");
      io.println("    show events/jdk.FileRead[bytes>=1000] --limit 5");
      io.println("    show events/jdk.ExecutionSample[thread/name~\"main\"] --limit 10");
      io.println("  Aggregations:");
      io.println("    show events/jdk.FileRead | count()");
      io.println("    show events/jdk.FileRead/bytes | sum()");
      io.println("    show events/jdk.FileRead/bytes | stats()");
      io.println("    show events/jdk.ExecutionSample | groupBy(thread/name)");
      io.println("    show events/jdk.FileRead | top(10, by=bytes)");
      io.println("  Field projection:");
      io.println("    show events/jdk.FileRead | select(path, bytes / 1024 as kilobytes)");
      io.println("    show events/jdk.FileRead | select(\"${path} (${bytes} bytes)\" as info)");
      io.println("  Metadata:");
      io.println("    show metadata/java.lang.Thread");
      io.println("    show metadata/jdk.types.StackTrace --tree --depth 2");
      io.println("  Constant pools:");
      io.println("    show cp/jdk.types.Symbol[string~\"java/.*\"]");
      io.println("  Advanced (interleaved filters, list matching):");
      io.println(
          "    show events/jdk.GCHeapSummary[when/when=\"After GC\"]/heapSpace[committedSize>1000000]");
      io.println(
          "    show events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/name/string, \".*Foo.*\")]]");
      return;
    }
    if ("metadata".equals(sub) || "types".equals(sub)) {
      io.println("Usage: metadata [--search <glob>|--regex <pattern>] [--refresh]");
      io.println("Lists available metadata types (all classes) in the current session.");
      io.println("Options:");
      io.println("  --search <glob>   Glob match on full type name (e.g., 'jdk.*')");
      io.println("  --regex  <pat>    Regex match on full type name");
      io.println("  --refresh         Rescan metadata to update types");
      io.println("  --events-only     Show only event types");
      io.println("  --non-events-only Show only non-event types");
      io.println("  --primitives      Show only primitive metadata types (debug)");
      io.println("  --summary         Print summary counts only");
      io.println("Examples:");
      io.println("  metadata");
      io.println("  metadata --search jdk.*");
      io.println("  metadata --regex ^custom\\..*");
      io.println("");
      io.println("Metadata class details:");
      io.println(
          "  Usage: metadata class <name> [--tree|--json] [--fields] [--annotations] [--depth N]");
      io.println("  Flags:");
      io.println(
          "    --tree         Hierarchical view (class → fields → annotations/settings); use --depth to limit");
      io.println("    --json         Full JSON with all properties");
      io.println("    --fields       Tabular list of fields (name, type, dimension, annotations)");
      io.println("    --annotations  Class-level annotations only");
      io.println("    --depth N      Max recursion depth (default 10)");
      io.println("  Examples:");
      io.println("    metadata class java.lang.Thread");
      io.println("    metadata class java.lang.Thread --tree --depth 3");
      io.println("    metadata class jdk.types.Method --fields");
      return;
    }
    if ("chunks".equals(sub)) {
      io.println("Usage: chunks [--summary] [--range N-M] [--format table|json|csv]");
      io.println("List chunks in the current recording.");
      io.println("Options:");
      io.println(
          "  --summary      Show aggregate statistics (totalChunks, totalSize, avgSize, minSize, maxSize, compressedCount)");
      io.println("  --range N-M    Show chunks from index N to M (inclusive)");
      io.println("Examples:");
      io.println("  chunks");
      io.println("  chunks --summary");
      io.println("  chunks --range 0-5");
      io.println("");
      io.println(
          "Chunks are indexed starting from 0. Each chunk contains a portion of the recording data.");
      return;
    }
    if ("chunk".equals(sub)) {
      io.println("Usage: chunk <index> show");
      io.println("Show detailed information about a specific chunk.");
      io.println("Examples:");
      io.println("  chunk 0 show");
      io.println("  chunk 2 show");
      io.println("");
      io.println("Displays: index, offset, size, startNanos, duration, compressed");
      return;
    }
    if ("cp".equals(sub)) {
      io.println("Usage: cp [--summary] [<type>] [--range N-M] [--format table|json|csv]");
      io.println("Browse constant pools in the current recording.");
      io.println("Options:");
      io.println("  --summary      Show per-type counts only (default when no type specified)");
      io.println("  --range N-M    Show entries from N to M (inclusive, for specific type)");
      io.println("Examples:");
      io.println("  cp");
      io.println("  cp jdk.types.Symbol");
      io.println("  cp jdk.types.Method --range 0-100");
      io.println("");
      io.println(
          "Constant pools contain indexed reference data like symbols, methods, classes, and threads.");
      io.println("Use 'show cp/<type>' for more advanced filtering with JfrPath expressions.");
      return;
    }
    if ("set".equals(sub) || "let".equals(sub)) {
      io.println("Usage: set [--global] <name> = <value|expression>");
      io.println("       set output <format>");
      io.println("");
      io.println("Variable Assignment:");
      io.println("Store a variable value. Values can be:");
      io.println("  - Literal strings: set name = \"hello\"");
      io.println("  - Numbers: set count = 42");
      io.println("  - Map literals: set config = {\"key\": \"value\", \"count\": 42}");
      io.println("  - JfrPath queries (lazy): set reads = events/jdk.FileRead[bytes>1000]");
      io.println("");
      io.println("Options:");
      io.println("  --global  Store in global scope (persists across sessions)");
      io.println("");
      io.println("Map Variables:");
      io.println("Maps use JSON-like syntax and support:");
      io.println("  - String values: {\"name\": \"test\"}");
      io.println("  - Numbers: {\"count\": 42, \"ratio\": 3.14}");
      io.println("  - Booleans: {\"enabled\": true}");
      io.println("  - Null values: {\"optional\": null}");
      io.println("  - Nested maps: {\"db\": {\"host\": \"localhost\", \"port\": 5432}}");
      io.println("  - Access nested fields: ${config.db.host}");
      io.println("  - Get map size: ${config.size}");
      io.println("");
      io.println("Lazy queries are not evaluated until accessed via ${var} substitution.");
      io.println("Results are cached after first evaluation. Use 'invalidate' to clear cache.");
      io.println("");
      io.println("Output Format:");
      io.println("  set output <format>    Set default output format for this session");
      io.println("  Valid formats: table, json, csv");
      io.println("  The --format flag on commands overrides this setting");
      io.println("");
      io.println("Examples:");
      io.println("  set threshold = 1000");
      io.println("  set bigReads = events/jdk.FileRead[bytes>${threshold}]");
      io.println("  set config = {\"threshold\": 1000, \"pattern\": \".*Error.*\"}");
      io.println("  echo Threshold: ${config.threshold}");
      io.println("  set --global myPattern = \".*Exception.*\"");
      io.println("  set output json        # Switch to JSON output");
      io.println("  set output csv         # Export-friendly CSV");
      return;
    }
    if ("vars".equals(sub)) {
      io.println("Usage: vars [--global|--session|--all]");
      io.println("List defined variables.");
      io.println("");
      io.println("Options:");
      io.println("  --global   Show only global variables");
      io.println("  --session  Show only session variables");
      io.println("  --all      Show all (default)");
      return;
    }
    if ("unset".equals(sub)) {
      io.println("Usage: unset <name> [--global]");
      io.println("Remove a variable.");
      io.println("");
      io.println("Options:");
      io.println("  --global  Remove from global scope specifically");
      return;
    }
    if ("echo".equals(sub)) {
      io.println("Usage: echo <text>");
      io.println("Print text with variable substitution.");
      io.println("");
      io.println("Variable syntax:");
      io.println("  ${var}              - Scalar value or first value from result set");
      io.println("  ${var.size}         - Row count for lazy queries, entry count for maps");
      io.println("  ${var.field}        - Field from first row or map value");
      io.println("  ${var.a.b.c}        - Nested field access (multi-level)");
      io.println("  ${var[N].field}     - Field from row N (0-indexed)");
      io.println("  ${var[N].a.b}       - Nested field from specific row");
      io.println("");
      io.println("Examples:");
      io.println("  echo Found ${bigReads.size} large file reads");
      io.println("  echo First read was ${bigReads[0].bytes} bytes");
      io.println("  echo Database: ${config.db.host}:${config.db.port}");
      return;
    }
    if ("invalidate".equals(sub)) {
      io.println("Usage: invalidate <name>");
      io.println("Clear the cached result for a lazy variable.");
      io.println("Next access will re-evaluate the query.");
      return;
    }
    if ("if".equals(sub) || "elif".equals(sub) || "else".equals(sub) || "endif".equals(sub)) {
      io.println("Conditional execution with if/elif/else/endif blocks.");
      io.println("");
      io.println("Syntax:");
      io.println("  if <condition>");
      io.println("    <commands>");
      io.println("  elif <condition>");
      io.println("    <commands>");
      io.println("  else");
      io.println("    <commands>");
      io.println("  endif");
      io.println("");
      io.println("Condition expressions:");
      io.println("  Comparisons: ==, !=, >, >=, <, <=");
      io.println("  Logical: && (and), || (or), ! (not)");
      io.println("  Arithmetic: +, -, *, /");
      io.println("  Functions: exists(var), empty(var)");
      io.println("  Grouping: parentheses ( )");
      io.println("");
      io.println("Examples:");
      io.println("  if ${count.count} > 0");
      io.println("    echo Found ${count.count} events");
      io.println("  endif");
      io.println("");
      io.println("  if exists(myVar) && ${myVar.size} > 100");
      io.println("    echo Large result set");
      io.println("  elif ${myVar.size} > 0");
      io.println("    echo Small result set");
      io.println("  else");
      io.println("    echo Empty result");
      io.println("  endif");
      return;
    }
    if ("chat".equals(sub)) {
      io.println("Usage: chat [query]");
      io.println("       chat /exit");
      io.println("       chat /help");
      io.println("       chat /clear");
      io.println("");
      io.println("Enters conversational mode for natural language JFR analysis.");
      io.println("In chat mode, type questions directly without command prefix.");
      io.println("");
      io.println("The LLM understands various query patterns including:");
      io.println("  - Memory analysis");
      io.println("  - CPU profiling");
      io.println("  - File I/O analysis");
      io.println("  - GC analysis");
      io.println("  - Event decoration and correlation");
      io.println("");
      io.println("Natural language keywords for event decoration:");
      io.println("  - 'decorated' / 'embellished' / 'extended' - Add context from related events");
      io.println("");
      io.println("Examples:");
      io.println("  jfr> chat");
      io.println("  jafar> which threads allocated the most memory?");
      io.println("  jafar> show me only thread 'main'");
      io.println("  jafar> /exit");
      io.println("");
      io.println("Commands while in chat:");
      io.println("  /exit, /quit  - Return to normal CLI");
      io.println("  /clear        - Clear conversation history");
      io.println("  /help         - Show chat tips");
      io.println("");
      io.println("See 'help llm' for LLM configuration.");
      return;
    }
    if ("llm".equals(sub)) {
      io.println("LLM (Large Language Model) commands for natural language query translation.");
      io.println("");
      io.println("Commands:");
      io.println("  chat [query]    - Enter conversational mode for natural language queries");
      io.println("  llm status      - Check LLM configuration and availability");
      io.println("  llm config      - Display configuration options");
      io.println("  llm test        - Test LLM connection");
      io.println("  llm clear       - Clear conversation history");
      io.println("  llm audit       - View recent LLM interactions");
      io.println("");
      io.println("Configuration:");
      io.println("  Config file: ~/.jfr-shell/llm-config.properties");
      io.println("");
      io.println("  Default (Local Ollama):");
      io.println("    provider=LOCAL");
      io.println("    model=llama3.1:8b");
      io.println("    privacy.mode=LOCAL_ONLY");
      io.println("");
      io.println("Supported providers:");
      io.println("  - LOCAL (Ollama) - Privacy-first, runs locally");
      io.println("  - OPENAI - Cloud-based (requires API key)");
      io.println("  - ANTHROPIC - Cloud-based (requires API key)");
      io.println("");
      io.println("Privacy modes:");
      io.println("  - LOCAL_ONLY - Only local provider allowed (default)");
      io.println("  - CLOUD_WITH_CONFIRM - Cloud providers with confirmation");
      io.println("");
      io.println("See 'help ask' for query examples.");
      io.println("Documentation: doc/llm-integration.md");
      return;
    }
    io.println("No specific help for '" + sub + "'. Try 'help show'.");
  }

  // Very small JSON pretty-printer for Maps/Lists/values
  private static void printJson(Object obj, IO io) {
    String json = toJson(obj, 0);
    PagedPrinter pager = PagedPrinter.forIO(io);
    for (String line : json.split("\n", -1)) {
      pager.println(line);
    }
  }

  private static String toJson(Object obj, int indent) {
    String ind = "  ".repeat(indent);
    if (obj == null) return "null";
    if (obj instanceof String s) return '"' + escapeJson(s) + '"';
    if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
    if (obj instanceof java.util.Map<?, ?> m) {
      StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      boolean first = true;
      for (var e : m.entrySet()) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(ind)
            .append("  ")
            .append('"')
            .append(escapeJson(String.valueOf(e.getKey())))
            .append('"')
            .append(": ");
        sb.append(toJson(e.getValue(), indent + 1));
      }
      sb.append("\n").append(ind).append("}");
      return sb.toString();
    }
    if (obj instanceof java.util.Collection<?> coll) {
      StringBuilder sb = new StringBuilder();
      sb.append("[\n");
      boolean first = true;
      for (Object v : coll) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(ind).append("  ").append(toJson(v, indent + 1));
      }
      sb.append("\n").append(ind).append("]");
      return sb.toString();
    }
    return '"' + escapeJson(String.valueOf(obj)) + '"';
  }

  private static String escapeJson(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  private void cmdTypes(List<String> args) {
    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open");
      return;
    }
    boolean refresh = false;
    Boolean eventsOnly = null; // null=all, true=only events, false=only non-events
    boolean primitives = false;
    boolean summaryOnly = false;
    String search = null;
    boolean regex = false;
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      switch (a) {
        case "--refresh":
          refresh = true;
          break;
        case "--search":
          if (i + 1 < args.size()) {
            search = args.get(++i);
          }
          break;
        case "--regex":
          regex = true;
          break;
        case "--events-only":
          eventsOnly = Boolean.TRUE;
          break;
        case "--non-events-only":
          eventsOnly = Boolean.FALSE;
          break;
        case "--primitives":
          primitives = true;
          break;
        case "--summary":
          summaryOnly = true;
          break;
        default:
          if (search == null) search = a; // allow 'types pattern'
      }
    }
    try {
      if (refresh) {
        cur.get().session.refreshTypes();
      }
    } catch (Exception e) {
      io.error("Failed to refresh types: " + e.getMessage());
      return;
    }
    var sess = cur.get().session;
    java.util.Set<String> all =
        primitives ? sess.getPrimitiveMetadataTypes() : sess.getNonPrimitiveMetadataTypes();
    java.util.Set<String> events = sess.getAvailableEventTypes();
    java.util.List<String> types = new java.util.ArrayList<>();
    if (primitives) {
      // primitives: ignore eventsOnly flag and just show primitives
      types.addAll(all);
    } else if (eventsOnly == null) {
      types.addAll(all);
    } else if (eventsOnly.booleanValue()) {
      types.addAll(events);
    } else {
      for (String t : all) if (!events.contains(t)) types.add(t);
    }
    java.util.Collections.sort(types);
    if (search != null) {
      if (regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(search);
        types.removeIf(t -> !p.matcher(t).find());
      } else {
        // Convert glob pattern to regex for string matching (not filesystem paths)
        String globRegex =
            search
                .replace(".", "\\.") // Escape dots
                .replace("*", ".*") // * matches any characters
                .replace("?", "."); // ? matches single character
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(globRegex);
        types.removeIf(t -> !p.matcher(t).matches());
      }
    }
    int total = types.size();
    String scopeLabel =
        primitives
            ? "primitives"
            : ((eventsOnly == null) ? "all" : (eventsOnly ? "events only" : "non-events only"));
    long eventsCnt = types.stream().filter(events::contains).count();
    long nonEventsCnt = total - eventsCnt;
    if (summaryOnly) {
      int primitivesCnt = sess.getPrimitiveMetadataTypes().size();
      int nonPrimitiveCnt = sess.getNonPrimitiveMetadataTypes().size();
      io.println("Types Summary:");
      io.println(
          "  Non-primitive: "
              + nonPrimitiveCnt
              + " (events="
              + events.size()
              + ", non-events="
              + (nonPrimitiveCnt - events.size())
              + ")");
      io.println("  Primitives:    " + primitivesCnt);
      io.println("  All metadata:  " + (nonPrimitiveCnt + primitivesCnt));
      return;
    }
    io.println(
        "Available Types ("
            + total
            + ") ["
            + scopeLabel
            + "; events="
            + eventsCnt
            + ", non-events="
            + nonEventsCnt
            + "]:");
    for (String t : types) {
      Long typeId = cur.get().session.getMetadataTypeIds().get(t);
      String idStr = typeId != null ? String.format("%5d", typeId) : "    ?";
      io.println("  " + idStr + " - " + t);
    }
  }

  private void cmdMetadataClass(List<String> args) {
    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open");
      return;
    }
    if (args.isEmpty()) {
      io.error("Usage: metadata class <name> [--tree|--json] [--fields] [--annotations]");
      return;
    }
    String typeName = args.get(0);
    boolean tree = false;
    boolean json = false;
    boolean fields = false;
    boolean annotations = false;
    int maxDepth = 10;
    for (int i = 1; i < args.size(); i++) {
      switch (args.get(i)) {
        case "--tree":
          tree = true;
          break;
        case "--json":
          json = true;
          break;
        case "--fields":
          fields = true;
          break;
        case "--annotations":
          annotations = true;
          break;
        case "--depth":
          if (i + 1 < args.size()) {
            try {
              maxDepth = Math.max(0, Integer.parseInt(args.get(++i)));
            } catch (NumberFormatException nfe) {
              io.error("Invalid --depth value: " + args.get(i));
              return;
            }
          } else {
            io.error("--depth requires a number");
            return;
          }
          break;
        default:
          io.error("Unknown option: " + args.get(i));
          return;
      }
    }
    Map<String, Object> meta;
    try {
      meta = MetadataProvider.loadClass(cur.get().session.getRecordingPath(), typeName);
    } catch (Exception e) {
      io.error("Failed to load metadata: " + e.getMessage());
      return;
    }
    if (meta == null) {
      io.error("Type not found: " + typeName);
      return;
    }

    if (json) {
      printJson(meta, io);
      return;
    }
    if (tree) {
      // For --tree, render recursively starting from the requested type
      TreeRenderer.renderMetadataRecursive(
          cur.get().session.getRecordingPath(), typeName, io, maxDepth);
      return;
    }
    if (fields) {
      Object fbn = meta.get("fieldsByName");
      if (!(fbn instanceof Map<?, ?> m) || m.isEmpty()) {
        io.println("(no fields)");
        return;
      }
      java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
      java.util.List<String> names = new java.util.ArrayList<>();
      for (Object k : m.keySet()) names.add(String.valueOf(k));
      java.util.Collections.sort(names);
      for (String fn : names) {
        Object v = m.get(fn);
        if (v instanceof Map<?, ?> fm) {
          java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
          row.put("name", fn);
          row.put("type", fm.get("type"));
          row.put("dimension", fm.get("dimension"));
          Object a = fm.get("annotations");
          row.put(
              "annotations",
              (a instanceof java.util.List<?> l)
                  ? String.join(" ", l.stream().map(String::valueOf).toList())
                  : "");
          rows.add(row);
        }
      }
      TableRenderer.render(rows, io);
      return;
    }
    if (annotations) {
      Object ca = meta.get("classAnnotations");
      if (!(ca instanceof java.util.List<?> list) || list.isEmpty()) {
        io.println("(no annotations)");
      } else {
        io.println("Annotations:");
        for (Object v : list) io.println("  " + String.valueOf(v));
      }
      return;
    }
    // Default: show a one-row table with curated columns
    java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(meta);
    copy.remove("fieldsByName");
    copy.remove("classAnnotations");
    copy.remove("classAnnotationsFull");
    copy.remove("settingsByName");
    copy.remove("fieldCount");
    TableRenderer.render(java.util.List.of(copy), io);
  }

  /** chunks [--summary|--list] [--range N-M] Default: list all chunks */
  private void cmdChunks(List<String> args) throws Exception {
    Optional<SessionManager.SessionRef> cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open. Use 'open <file>' first.");
      return;
    }

    boolean summary = args.contains("--summary");
    String range = extractOption(new ArrayList<>(args), "--range");
    String format = cur.get().outputFormat;

    // Parse --format option
    for (int i = 0; i < args.size(); i++) {
      if ("--format".equals(args.get(i)) && i + 1 < args.size()) {
        format = args.get(i + 1);
        break;
      }
    }

    if (summary) {
      Map<String, Object> stats =
          ChunkProvider.getChunkSummary(cur.get().session.getRecordingPath());
      if ("json".equalsIgnoreCase(format)) {
        printJson(List.of(stats), io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(List.of(stats), io);
      } else {
        TableRenderer.render(List.of(stats), io);
      }
    } else {
      List<Map<String, Object>> chunks =
          ChunkProvider.loadAllChunks(cur.get().session.getRecordingPath());

      // Apply range filter if provided: --range 0-5
      if (range != null) {
        String[] parts = range.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = parts.length > 1 ? Integer.parseInt(parts[1]) : start;
        final int fStart = start;
        final int fEnd = end;
        chunks =
            chunks.stream()
                .filter(
                    c -> {
                      int idx = (int) c.get("index");
                      return idx >= fStart && idx <= fEnd;
                    })
                .toList();
      }

      if ("json".equalsIgnoreCase(format)) {
        printJson(chunks, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(chunks, io);
      } else {
        TableRenderer.render(chunks, io);
      }
    }
  }

  /** chunk <index> show [--header|--events|--constants] Show detailed view of a single chunk */
  private void cmdChunk(List<String> args) throws Exception {
    Optional<SessionManager.SessionRef> cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open. Use 'open <file>' first.");
      return;
    }
    if (args.isEmpty()) {
      io.error("Usage: chunk <index> show [--header|--events|--constants]");
      return;
    }

    int chunkIndex;
    try {
      chunkIndex = Integer.parseInt(args.get(0));
    } catch (NumberFormatException e) {
      io.error("Invalid chunk index: " + args.get(0));
      return;
    }

    Map<String, Object> chunk =
        ChunkProvider.loadChunk(cur.get().session.getRecordingPath(), chunkIndex);

    if (chunk == null) {
      io.error("Chunk " + chunkIndex + " not found");
      return;
    }

    // For now, just show header. Future: add event counts, CP refs
    TableRenderer.render(List.of(chunk), io);
  }

  /** cp [--summary] [<kind>] [--range N-M] cp <kind> [--range N-M] */
  private void cmdCp(List<String> args) throws Exception {
    Optional<SessionManager.SessionRef> cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open. Use 'open <file>' first.");
      return;
    }

    boolean summary = args.contains("--summary");
    List<String> cleanArgs = new ArrayList<>(args);
    cleanArgs.remove("--summary");

    String range = extractOption(cleanArgs, "--range");
    String format = extractOption(cleanArgs, "--format");
    if (format == null) {
      format = cur.get().outputFormat;
    }

    if (cleanArgs.isEmpty() || summary) {
      // cp or cp --summary: show type summary
      List<Map<String, Object>> summaryRows =
          ConstantPoolProvider.loadSummary(cur.get().session.getRecordingPath());
      if ("json".equalsIgnoreCase(format)) {
        printJson(summaryRows, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(summaryRows, io);
      } else {
        TableRenderer.render(summaryRows, io);
      }
    } else {
      // cp <type>: list entries
      String typeName = cleanArgs.get(0);
      List<Map<String, Object>> entries =
          ConstantPoolProvider.loadEntries(cur.get().session.getRecordingPath(), typeName);

      // Apply range filter if needed
      if (range != null) {
        String[] parts = range.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = parts.length > 1 ? Integer.parseInt(parts[1]) : entries.size() - 1;
        entries = entries.subList(Math.max(0, start), Math.min(entries.size(), end + 1));
      }

      if ("json".equalsIgnoreCase(format)) {
        printJson(entries, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(entries, io);
      } else {
        TableRenderer.render(entries, io);
      }
    }
  }

  /**
   * Helper to extract option value from args list. Modifies the args list by removing the option
   * and its value.
   */
  private String extractOption(List<String> args, String optionName) {
    int idx = args.indexOf(optionName);
    if (idx >= 0 && idx + 1 < args.size()) {
      String value = args.get(idx + 1);
      args.remove(idx + 1);
      args.remove(idx);
      return value;
    }
    return null;
  }

  // ---- Variable commands ----

  private void cmdSet(List<String> args, String fullLine) throws Exception {
    // Parse: set [--global] name = expression
    // Find '=' in full line
    int cmdEnd = fullLine.indexOf(' ');
    if (cmdEnd < 0) {
      io.error("Usage: set [--global] <name> = <expression|value>");
      return;
    }
    String rest = fullLine.substring(cmdEnd + 1).trim();

    // Handle "set output <format>" subcommand
    if (rest.startsWith("output")) {
      String[] parts = rest.split("\\s+", 2);
      if (parts.length == 1) {
        cmdSetOutput(List.of());
      } else {
        cmdSetOutput(List.of(parts[1]));
      }
      return;
    }

    boolean isGlobal = rest.startsWith("--global ");
    if (isGlobal) {
      rest = rest.substring(9).trim();
    }

    int eqPos = rest.indexOf('=');
    if (eqPos < 0) {
      io.error("Usage: set [--global] <name> = <expression|value>");
      return;
    }

    String varName = rest.substring(0, eqPos).trim();
    String exprPart = rest.substring(eqPos + 1).trim();

    if (varName.isEmpty()) {
      io.error("Variable name cannot be empty");
      return;
    }
    if (!varName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      io.error("Invalid variable name: " + varName);
      return;
    }

    VariableStore store = getTargetStore(isGlobal);

    // Check for map literal
    if (exprPart.startsWith("{")) {
      try {
        Map<String, Object> map = parseMapLiteral(exprPart);
        store.set(varName, new VariableStore.MapValue(map));
        if (verbose) {
          io.println("Set " + varName + " = " + new VariableStore.MapValue(map).describe());
        }
        return;
      } catch (Exception e) {
        io.error("Invalid map literal: " + e.getMessage());
        return;
      }
    }

    // Check for merge function syntax
    String exprTrimmed = exprPart.trim();
    if (exprTrimmed.startsWith("merge(") && exprTrimmed.endsWith(")")) {
      try {
        Map<String, Object> merged = parseMergeFunction(exprTrimmed, store);
        store.set(varName, new VariableStore.MapValue(merged));
        if (verbose) {
          io.println("Set " + varName + " = " + new VariableStore.MapValue(merged).describe());
        }
        return;
      } catch (Exception e) {
        io.error("Merge function error: " + e.getMessage());
        return;
      }
    }

    // Check for literal string value
    if (exprPart.startsWith("\"") && exprPart.endsWith("\"")) {
      String literal = exprPart.substring(1, exprPart.length() - 1);
      store.set(varName, new ScalarValue(literal));
      if (verbose) {
        io.println("Set " + varName + " = \"" + literal + "\"");
      }
      return;
    }

    // Check for numeric literal
    if (exprPart.matches("-?\\d+(\\.\\d+)?")) {
      Number num = exprPart.contains(".") ? Double.parseDouble(exprPart) : Long.parseLong(exprPart);
      store.set(varName, new ScalarValue(num));
      if (verbose) {
        io.println("Set " + varName + " = " + num);
      }
      return;
    }

    // Treat as JfrPath query
    Optional<SessionManager.SessionRef> cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open for query evaluation");
      return;
    }

    // Parse the query (but don't evaluate yet)
    JfrPath.Query query;
    try {
      query = JfrPathParser.parse(exprPart);
    } catch (Exception e) {
      io.error("Invalid query: " + e.getMessage());
      return;
    }

    // Use static analysis to determine if query produces a scalar
    if (isDefinitelyScalar(query)) {
      // Scalar-producing query: evaluate immediately and store as scalar
      try {
        JfrPathEvaluator evaluator = new JfrPathEvaluator();
        Object result = evaluator.evaluate(cur.get().session, query);
        Object scalarValue = extractScalarIfSingle(result);
        if (scalarValue != null) {
          store.set(varName, new ScalarValue(scalarValue));
          if (verbose) {
            io.println("Set " + varName + " = " + formatScalarValue(scalarValue));
          }
        } else {
          // Fallback: store as lazy (shouldn't happen for scalar queries)
          LazyQueryValue lqv = new LazyQueryValue(query, cur.get(), exprPart);
          lqv.setCachedResult(result);
          store.set(varName, lqv);
          if (verbose) {
            io.println("Set " + varName + " = lazy[" + exprPart + "]");
          }
        }
      } catch (Exception e) {
        io.error("Query evaluation failed: " + e.getMessage());
      }
    } else if (isDefinitelyMap(query)) {
      // Map-producing query: evaluate and store as MapValue
      try {
        JfrPathEvaluator evaluator = new JfrPathEvaluator();
        Object result = evaluator.evaluate(cur.get().session, query);

        // Extract map from single-element list result
        if (result instanceof List<?> list && list.size() == 1) {
          Object first = list.get(0);
          if (first instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapResult = (Map<String, Object>) map;
            store.set(varName, new VariableStore.MapValue(mapResult));
            if (verbose) {
              io.println(
                  "Set " + varName + " = " + new VariableStore.MapValue(mapResult).describe());
            }
            return;
          }
        }

        // Fallback: store as lazy if not a clean map result
        LazyQueryValue lqv = new LazyQueryValue(query, cur.get(), exprPart);
        lqv.setCachedResult(result);
        store.set(varName, lqv);
        if (verbose) {
          io.println("Set " + varName + " = lazy[" + exprPart + "]");
        }
      } catch (Exception e) {
        io.error("Query evaluation failed: " + e.getMessage());
      }
    } else {
      // Non-scalar query: store as lazy (not evaluated until accessed)
      LazyQueryValue lqv = new LazyQueryValue(query, cur.get(), exprPart);
      store.set(varName, lqv);
      if (verbose) {
        io.println("Set " + varName + " = lazy[" + exprPart + "] (not evaluated)");
      }
    }
  }

  private void cmdSetOutput(List<String> args) {
    if (args.isEmpty()) {
      // Show current setting
      var cur = sessions.current();
      if (cur.isEmpty()) {
        io.println("No session open. Default format: table");
      } else {
        io.println("Current output format: " + cur.get().outputFormat);
      }
      return;
    }

    String format = args.get(0).toLowerCase(Locale.ROOT);
    if (!format.matches("table|json|csv")) {
      io.error("Invalid format: " + format);
      io.error("Valid formats: table, json, csv");
      return;
    }

    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open. Open a recording first with: open <path>");
      return;
    }

    cur.get().outputFormat = format;
    if (verbose) {
      io.println("Output format set to: " + format);
    }
  }

  /**
   * Parses a map literal in JSON-like syntax: {"key": value, "nested": {...}} Supports: strings,
   * numbers, booleans, null, nested maps
   *
   * @param input map literal string starting with { and ending with }
   * @return parsed map
   * @throws IllegalArgumentException if syntax is invalid
   */
  private Map<String, Object> parseMapLiteral(String input) {
    MapLiteralParser parser = new MapLiteralParser(input);
    return parser.parseMap();
  }

  /** Simple recursive-descent parser for map literals */
  private static class MapLiteralParser {
    private final String input;
    private int pos = 0;

    MapLiteralParser(String input) {
      this.input = input.trim();
    }

    Map<String, Object> parseMap() {
      skipWs();
      if (peek() != '{') {
        throw error("Expected '{'");
      }
      consume(); // {

      Map<String, Object> map = new LinkedHashMap<>();
      skipWs();

      if (peek() == '}') {
        consume();
        return map;
      }

      while (true) {
        skipWs();

        // Parse key (must be string)
        if (peek() != '"') {
          throw error("Expected string key");
        }
        String key = parseString();

        skipWs();
        if (peek() != ':') {
          throw error("Expected ':' after key");
        }
        consume();

        skipWs();
        Object value = parseValue();
        map.put(key, value);

        skipWs();
        char next = peek();
        if (next == '}') {
          consume();
          break;
        } else if (next == ',') {
          consume();
        } else {
          throw error("Expected ',' or '}'");
        }
      }

      return map;
    }

    private Object parseValue() {
      skipWs();
      char ch = peek();

      if (ch == '"') {
        return parseString();
      } else if (ch == '{') {
        return parseMap();
      } else if (ch == 't' || ch == 'f') {
        return parseBoolean();
      } else if (ch == 'n') {
        return parseNull();
      } else if (ch == '-' || Character.isDigit(ch)) {
        return parseNumber();
      } else {
        throw error("Unexpected character: " + ch);
      }
    }

    private String parseString() {
      if (peek() != '"') {
        throw error("Expected '\"'");
      }
      consume(); // opening "

      StringBuilder sb = new StringBuilder();
      while (pos < input.length()) {
        char ch = input.charAt(pos);
        if (ch == '"') {
          consume();
          return sb.toString();
        } else if (ch == '\\') {
          if (pos + 1 >= input.length()) {
            throw error("Lone backslash at end of string");
          }
          consume();
          char escaped = input.charAt(pos);
          switch (escaped) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            default -> throw error("Invalid escape: \\" + escaped);
          }
          consume();
        } else {
          sb.append(ch);
          consume();
        }
      }
      throw error("Unterminated string");
    }

    private Number parseNumber() {
      int start = pos;
      if (peek() == '-') {
        consume();
      }

      int digitStart = pos;
      while (pos < input.length() && Character.isDigit(peek())) {
        consume();
      }

      // Validate at least one digit after optional minus sign
      if (pos == digitStart) {
        throw error("Expected digit after minus sign");
      }

      boolean isDouble = false;
      if (pos < input.length() && peek() == '.') {
        isDouble = true;
        consume();
        int decimalStart = pos;
        while (pos < input.length() && Character.isDigit(peek())) {
          consume();
        }
        // Validate at least one digit after decimal point
        if (pos == decimalStart) {
          throw error("Expected digit after decimal point");
        }
      }

      String numStr = input.substring(start, pos);
      try {
        return isDouble ? Double.parseDouble(numStr) : Long.parseLong(numStr);
      } catch (NumberFormatException e) {
        throw error("Invalid number: " + numStr);
      }
    }

    private Boolean parseBoolean() {
      if (input.startsWith("true", pos)) {
        pos += 4;
        if (!isDelimiter(pos)) {
          throw error("Invalid boolean literal - expected delimiter after 'true'");
        }
        return true;
      } else if (input.startsWith("false", pos)) {
        pos += 5;
        if (!isDelimiter(pos)) {
          throw error("Invalid boolean literal - expected delimiter after 'false'");
        }
        return false;
      }
      throw error("Expected 'true' or 'false'");
    }

    private Object parseNull() {
      if (input.startsWith("null", pos)) {
        pos += 4;
        if (!isDelimiter(pos)) {
          throw error("Invalid null literal - expected delimiter after 'null'");
        }
        return null;
      }
      throw error("Expected 'null'");
    }

    /**
     * Checks if the position is at a valid delimiter (comma, closing brace, or end of input).
     *
     * @param position the position to check
     * @return true if at a delimiter
     */
    private boolean isDelimiter(int position) {
      if (position >= input.length()) {
        return true; // End of input
      }
      char c = input.charAt(position);
      return c == ',' || c == '}' || Character.isWhitespace(c);
    }

    private char peek() {
      if (pos >= input.length()) {
        throw error("Unexpected end of input");
      }
      return input.charAt(pos);
    }

    private void consume() {
      pos++;
    }

    private void skipWs() {
      while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at position " + pos);
    }
  }

  /**
   * Determines if a query will definitely produce a single scalar value based on static analysis of
   * its pipeline operators. Only queries ending with count or sum are considered scalar.
   */
  private boolean isDefinitelyScalar(JfrPath.Query query) {
    if (query.pipeline == null || query.pipeline.isEmpty()) {
      return false; // No pipeline = produces rows, not scalar
    }
    // Check the last operator in the pipeline
    JfrPath.PipelineOp lastOp = query.pipeline.get(query.pipeline.size() - 1);
    return lastOp instanceof JfrPath.CountOp || lastOp instanceof JfrPath.SumOp;
  }

  /**
   * Determines if a query will definitely produce a map result. Currently only toMap() produces a
   * map.
   */
  private boolean isDefinitelyMap(JfrPath.Query query) {
    if (query.pipeline == null || query.pipeline.isEmpty()) {
      return false;
    }
    // Check the last operator in the pipeline
    JfrPath.PipelineOp lastOp = query.pipeline.get(query.pipeline.size() - 1);
    return lastOp instanceof JfrPath.ToMapOp;
  }

  /**
   * Parses and executes a merge function call: merge(${map1}, ${map2}, ...)
   *
   * @param expr the merge function expression
   * @param store the variable store for resolving variables
   * @return merged map
   */
  private Map<String, Object> parseMergeFunction(String expr, VariableStore store) {
    // Extract argument list between merge( and )
    String argList = expr.substring(6, expr.length() - 1).trim();

    if (argList.isEmpty()) {
      throw new IllegalArgumentException("merge() requires at least 2 arguments");
    }

    // Parse arguments
    List<String> args = parseMergeArgs(argList);

    if (args.size() < 2) {
      throw new IllegalArgumentException(
          "merge() requires at least 2 arguments, got " + args.size());
    }

    // Resolve each argument to a map
    List<Map<String, Object>> maps = new ArrayList<>();
    for (String arg : args) {
      Map<String, Object> map = resolveMapArg(arg, store);
      maps.add(map);
    }

    // Merge all maps
    return mergeMaps(maps);
  }

  /**
   * Parses merge function arguments, splitting by comma while respecting nested structures.
   *
   * @param argList comma-separated argument list
   * @return list of trimmed arguments
   */
  private List<String> parseMergeArgs(String argList) {
    List<String> args = new ArrayList<>();
    int start = 0;
    int depth = 0;
    boolean inString = false;

    for (int i = 0; i < argList.length(); i++) {
      char c = argList.charAt(i);

      // Track string boundaries
      if (c == '"' && (i == 0 || argList.charAt(i - 1) != '\\')) {
        inString = !inString;
      }

      if (!inString) {
        // Track nesting depth
        if (c == '(' || c == '{' || c == '[') {
          depth++;
        }
        if (c == ')' || c == '}' || c == ']') {
          depth--;
        }

        // Split on comma at depth 0
        if (c == ',' && depth == 0) {
          args.add(argList.substring(start, i).trim());
          start = i + 1;
        }
      }
    }

    // Add last argument
    args.add(argList.substring(start).trim());

    return args;
  }

  /**
   * Resolves a merge argument to a map. Supports variable references and map literals.
   *
   * @param arg the argument string
   * @param store the variable store
   * @return resolved map
   */
  private Map<String, Object> resolveMapArg(String arg, VariableStore store) {
    // Check for variable reference: ${varName}
    if (arg.startsWith("${") && arg.endsWith("}")) {
      String varName = arg.substring(2, arg.length() - 1);
      VariableStore.Value value = store.get(varName);

      if (value == null) {
        throw new IllegalArgumentException("Variable not found: " + varName);
      }
      if (!(value instanceof VariableStore.MapValue mapValue)) {
        throw new IllegalArgumentException(
            "Variable is not a map: "
                + varName
                + " (type: "
                + value.getClass().getSimpleName()
                + ")");
      }

      return mapValue.value();
    }

    // Check for map literal: {...}
    if (arg.startsWith("{") && arg.endsWith("}")) {
      return parseMapLiteral(arg);
    }

    throw new IllegalArgumentException(
        "Merge argument must be a map variable (${name}) or map literal {...}: " + arg);
  }

  /**
   * Merges multiple maps into a single map. Last wins for duplicate keys.
   *
   * @param maps list of maps to merge
   * @return merged map with insertion order preserved
   */
  private Map<String, Object> mergeMaps(List<Map<String, Object>> maps) {
    Map<String, Object> result = new LinkedHashMap<>();

    // Iterate through maps in order
    for (Map<String, Object> map : maps) {
      // Last wins: putAll overwrites existing keys
      result.putAll(map);
    }

    return result;
  }

  private void cmdVars(List<String> args) {
    // Handle --info <name> flag
    int infoIdx = args.indexOf("--info");
    if (infoIdx >= 0) {
      if (infoIdx + 1 >= args.size()) {
        io.error("Usage: vars --info <name>");
        return;
      }
      String name = args.get(infoIdx + 1);
      showVariableInfo(name);
      return;
    }

    boolean showGlobal = args.isEmpty() || args.contains("--global") || args.contains("--all");
    boolean showSession = args.isEmpty() || args.contains("--session") || args.contains("--all");

    Optional<SessionManager.SessionRef> cur = sessions.current();

    if (showGlobal && !globalStore.isEmpty()) {
      io.println("Global variables:");
      for (String name : globalStore.names()) {
        Value val = globalStore.get(name);
        io.println("  " + name + " = " + val.describe());
      }
    }

    if (showSession && cur.isPresent() && !cur.get().variables.isEmpty()) {
      io.println("Session variables (session #" + cur.get().id + "):");
      for (String name : cur.get().variables.names()) {
        Value val = cur.get().variables.get(name);
        io.println("  " + name + " = " + val.describe());
      }
    }

    if (globalStore.isEmpty() && (cur.isEmpty() || cur.get().variables.isEmpty())) {
      io.println("No variables defined");
    }
  }

  private void showVariableInfo(String name) {
    // Check session variables first, then global
    Value val = null;
    String scope = null;

    Optional<SessionManager.SessionRef> cur = sessions.current();
    if (cur.isPresent() && cur.get().variables.contains(name)) {
      val = cur.get().variables.get(name);
      scope = "session #" + cur.get().id;
    } else if (globalStore.contains(name)) {
      val = globalStore.get(name);
      scope = "global";
    }

    if (val == null) {
      io.error("Variable not found: " + name);
      return;
    }

    io.println("Variable: " + name);
    io.println("Scope: " + scope);

    if (val instanceof LazyQueryValue lqv) {
      io.println("Type: lazy query");
      io.println("Source: " + lqv.getQueryString());
      io.println("Cached: " + (lqv.isCached() ? "yes" : "no"));
      if (lqv.isCached()) {
        try {
          int size = lqv.size();
          io.println("Rows: " + size);
        } catch (Exception e) {
          io.println("Rows: error - " + e.getMessage());
        }
      }
    } else if (val instanceof VariableStore.MapValue mv) {
      io.println("Type: map");
      io.println("Size: " + mv.value().size() + " entries");
      io.println("Structure: " + mv.describe());
    } else if (val instanceof ScalarValue sv) {
      io.println("Type: scalar");
      io.println("Value: " + sv.describe());
    }
  }

  private void cmdUnset(List<String> args) {
    if (args.isEmpty()) {
      io.error("Usage: unset <name> [--global]");
      return;
    }

    String varName = args.get(0);
    boolean isGlobal = args.contains("--global");

    VariableStore store = getTargetStore(isGlobal);
    if (store.remove(varName)) {
      if (verbose) {
        io.println("Removed " + varName);
      }
    } else {
      // Try the other store if not found
      VariableStore other = isGlobal ? getSessionStore() : globalStore;
      if (other != null && other.remove(varName)) {
        if (verbose) {
          io.println("Removed " + varName);
        }
      } else {
        io.error("Variable not found: " + varName);
      }
    }
  }

  private void cmdEcho(List<String> args, String fullLine) throws Exception {
    // Extract everything after 'echo '
    int cmdEnd = fullLine.indexOf(' ');
    if (cmdEnd < 0) {
      io.println("");
      return;
    }
    String text = fullLine.substring(cmdEnd + 1);

    // Substitute variables
    VariableSubstitutor sub = new VariableSubstitutor(getSessionStore(), globalStore);
    String result = sub.substitute(text);
    io.println(result);
  }

  private void cmdInvalidate(List<String> args) {
    if (args.isEmpty()) {
      io.error("Usage: invalidate <name>");
      return;
    }

    String varName = args.get(0);
    Value val = resolveVariable(varName);

    if (val == null) {
      io.error("Variable not found: " + varName);
      return;
    }

    if (val instanceof LazyQueryValue lqv) {
      lqv.invalidate();
      if (verbose) {
        io.println("Invalidated cache for " + varName);
      }
    } else {
      io.error("Variable " + varName + " is not a lazy value");
    }
  }

  private VariableStore getTargetStore(boolean global) {
    if (global) {
      return globalStore;
    }
    Optional<SessionManager.SessionRef> cur = sessions.current();
    return cur.isPresent() ? cur.get().variables : globalStore;
  }

  private VariableStore getSessionStore() {
    Optional<SessionManager.SessionRef> cur = sessions.current();
    return cur.isPresent() ? cur.get().variables : null;
  }

  private Value resolveVariable(String name) {
    VariableStore sessionStore = getSessionStore();
    if (sessionStore != null && sessionStore.contains(name)) {
      return sessionStore.get(name);
    }
    if (globalStore.contains(name)) {
      return globalStore.get(name);
    }
    return null;
  }

  /**
   * Extracts a scalar value if the result is a single-row, single-column result. Returns null if
   * the result is a collection or multi-column row.
   */
  @SuppressWarnings("unchecked")
  private Object extractScalarIfSingle(Object result) {
    if (result == null) {
      return null;
    }
    // Direct scalar types
    if (result instanceof String || result instanceof Number || result instanceof Boolean) {
      return result;
    }
    // Check for single-row, single-column list
    if (result instanceof List<?> list) {
      if (list.size() == 1) {
        Object item = list.get(0);
        if (item instanceof Map<?, ?> map) {
          if (map.size() == 1) {
            // Single row, single column - extract the value
            return ((Map<String, Object>) map).values().iterator().next();
          }
        } else {
          // Single non-map item
          return item;
        }
      }
    }
    return null;
  }

  private String formatScalarValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String) {
      return "\"" + value + "\"";
    }
    return value.toString();
  }

  // ---- LLM Commands ----

  private void cmdChat(List<String> args) throws Exception {
    // Check for subcommands (support both bare and / prefix)
    if (!args.isEmpty()) {
      String subCmd = args.get(0).toLowerCase(Locale.ROOT);

      if ("exit".equals(subCmd)
          || "quit".equals(subCmd)
          || "/exit".equals(subCmd)
          || "/quit".equals(subCmd)) {
        chatState.exitChat();
        io.println("Exited chat mode.");
        return;
      }

      if ("help".equals(subCmd) || "/help".equals(subCmd)) {
        printChatHelp();
        return;
      }

      if ("clear".equals(subCmd) || "/clear".equals(subCmd)) {
        // Clear conversation history
        var cur = sessions.current();
        if (cur.isEmpty()) {
          io.error("No session open. Use 'open <path>' to open a recording first.");
          return;
        }
        cur.get()
            .variables
            .set("__llm_history", new VariableStore.MapValue(new ConversationHistory(20).toMap()));
        io.println("Conversation history cleared.");
        return;
      }
    }

    // If entering chat mode (no args or first use)
    if (!chatState.isChatMode()) {
      chatState.enterChat();
      io.println("Entering chat mode. Ask questions naturally.");
      io.println(
          "Commands: '/exit' or '/quit' to return, '/clear' to reset history, '/help' for tips.");
      io.println("");

      // If no query provided, just enter mode
      if (args.isEmpty()) {
        return;
      }
    }

    // Join args back to full query
    String query = String.join(" ", args);

    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open. Use 'open <path>' to open a recording first.");
      return;
    }

    // Get or create LLM provider
    LLMProvider provider = getOrCreateLLMProvider(cur.get());
    if (provider == null) {
      return; // Error already displayed
    }

    // Get or create conversation history
    ConversationHistory history = getOrCreateHistory(cur.get());

    // Build context and translate
    ContextBuilder contextBuilder = new ContextBuilder(cur.get(), provider.getConfig());
    QueryTranslator translator = new QueryTranslator(provider, contextBuilder, history);

    if (verbose) {
      io.println("Translating natural language query...");
    }

    TranslationResult result;
    try {
      result = translator.translate(query);
    } catch (LLMException e) {
      handleLLMException(e, provider.getConfig());
      return;
    }

    // Check if it's a conversational response (no query generated)
    if (result.isConversational()) {
      io.println("");
      io.println(result.conversationalResponse());
      // Add to history
      history.addTurn(
          new ConversationHistory.Turn(query, result.conversationalResponse(), Optional.empty()));
      // Save history back to session
      cur.get().variables.set("__llm_history", new VariableStore.MapValue(history.toMap()));
      return;
    }

    // Show the generated query
    io.println("Generated query: " + result.jfrPathQuery());
    if (result.explanation() != null && !result.explanation().isEmpty()) {
      io.println("Explanation: " + result.explanation());
    }
    if (result.warning().isPresent()) {
      io.println("Warning: " + result.warning().get());
    }
    if (result.confidence() < 0.5) {
      io.println("Confidence: " + String.format("%.0f%%", result.confidence() * 100) + " (low)");
    }

    // Execute the query
    io.println("");
    try {
      List<Map<String, Object>> results;

      if (selector != null) {
        // Use optimized selector if available
        results = selector.select(cur.get().session, result.jfrPathQuery());
      } else {
        // FALLBACK: Use JfrPathEvaluator directly (like cmdShow does)
        var jfrPathQuery = JfrPathParser.parse(result.jfrPathQuery());
        var evaluator = new JfrPathEvaluator(JfrPath.MatchMode.ANY);
        results = evaluator.evaluate(cur.get().session, jfrPathQuery);
      }

      // Render results using session's output format
      String format = cur.get().outputFormat;
      if ("json".equalsIgnoreCase(format)) {
        printJson(results, io);
      } else if ("csv".equalsIgnoreCase(format)) {
        CsvRenderer.render(results, io);
      } else {
        TableRenderer.render(results, io);
      }

      // Add to history
      history.addTurn(
          new ConversationHistory.Turn(
              query, result.explanation(), Optional.of(result.jfrPathQuery())));

      // Save history back to session
      cur.get().variables.set("__llm_history", new VariableStore.MapValue(history.toMap()));

      // Log to audit if enabled
      if (provider.getConfig().privacy().auditEnabled()) {
        try {
          AuditLogger logger = new AuditLogger();
          logger.log(
              new AuditLogger.AuditEntry(
                  provider.getConfig().provider().name().toLowerCase(),
                  provider.getModelName(),
                  "chat",
                  query.length(),
                  result.jfrPathQuery().length(),
                  false, // Only metadata sent
                  Duration.ofMillis(0)));
        } catch (IOException e) {
          if (verbose) {
            io.println("Warning: Failed to write audit log: " + e.getMessage());
          }
        }
      }

    } catch (Exception e) {
      io.error("Query execution failed: " + e.getMessage());
      if (verbose) {
        e.printStackTrace();
      }
    }
  }

  private void printChatHelp() {
    io.println("Chat Mode Tips:");
    io.println("  - Ask questions naturally without command prefix");
    io.println("  - Examples:");
    io.println("    • which threads allocated the most memory?");
    io.println("    • show execution samples decorated with GC phase");
    io.println("    • top 10 methods by CPU time");
    io.println("  - Follow-up questions use conversation context");
    io.println("  - Type '/exit' or '/quit' to return to normal CLI");
    io.println("  - Type '/clear' to reset conversation history");
    io.println("  - Type '/help' to show this message");
  }

  private void cmdLLM(List<String> args) throws Exception {
    if (args.isEmpty()) {
      cmdLLMStatus();
      return;
    }

    String subcommand = args.get(0).toLowerCase();
    switch (subcommand) {
      case "config" -> cmdLLMConfig();
      case "status" -> cmdLLMStatus();
      case "test" -> cmdLLMTest();
      case "clear" -> cmdLLMClear();
      case "audit" -> cmdLLMAudit();
      default -> {
        io.error("Unknown llm subcommand: " + subcommand);
        io.println("Available subcommands: config, status, test, clear, audit");
      }
    }
  }

  private void cmdLLMStatus() {
    try {
      LLMConfig config = LLMConfig.load();
      io.println("LLM Configuration:");
      io.println("  Provider: " + config.provider());
      io.println("  Endpoint: " + config.endpoint());
      io.println("  Model: " + config.model());
      io.println("  Privacy Mode: " + config.privacy().mode());
      io.println("  Audit Enabled: " + config.privacy().auditEnabled());

      // Test availability
      LLMProvider provider = LLMProvider.create(config);
      boolean available = provider.isAvailable();
      io.println("  Status: " + (available ? "Available" : "Unavailable"));

      // For Ollama, show available models
      if (config.provider() == LLMConfig.ProviderType.LOCAL && available) {
        try {
          String models = listOllamaModels(config.endpoint());
          if (models != null && !models.isEmpty()) {
            io.println("");
            io.println("Available models:");
            io.println(models);
          }
        } catch (Exception e) {
          if (verbose) {
            io.println("Could not list models: " + e.getMessage());
          }
        }
      }

      if (!available) {
        io.println("");
        io.println("Troubleshooting:");
        if (config.provider() == LLMConfig.ProviderType.LOCAL) {
          io.println("  - Ensure Ollama is running: ollama serve");
          io.println("  - Check available models: ollama list");
          io.println("  - Pull the configured model: ollama pull " + config.model());
          io.println("  - Or use a different model in ~/.jfr-shell/llm-config.properties");
        } else {
          io.println("  - Check API key is set (config file or environment variable)");
          io.println("  - Verify network connection");
        }
      }
      provider.close();
    } catch (IOException e) {
      io.error("No LLM config found");
      io.println(
          "Default config will be used: LOCAL provider with Ollama at http://localhost:11434");
      io.println("Run 'llm config' to customize or create config file at:");
      io.println("  ~/.jfr-shell/llm-config.properties");
    } catch (Exception e) {
      io.error("Error: " + e.getMessage());
    }
  }

  private void cmdLLMConfig() {
    io.println("LLM Configuration");
    io.println("=================");
    io.println("");
    io.println("Configuration file: ~/.jfr-shell/llm-config.properties");
    io.println("");
    io.println("Default configuration:");
    io.println("  provider=LOCAL");
    io.println("  endpoint=http://localhost:11434");
    io.println("  model=llama3.1:8b");
    io.println("  privacy.mode=LOCAL_ONLY");
    io.println("  privacy.auditEnabled=true");
    io.println("");
    io.println("To use OpenAI:");
    io.println("  provider=OPENAI");
    io.println("  endpoint=https://api.openai.com/v1");
    io.println("  model=gpt-4o-mini");
    io.println("  apiKey=your-api-key-here");
    io.println("");
    io.println("To use Anthropic (Claude):");
    io.println("  provider=ANTHROPIC");
    io.println("  endpoint=https://api.anthropic.com");
    io.println("  model=claude-3-5-sonnet-20241022");
    io.println("  apiKey=your-api-key-here");
    io.println("");
    io.println("For details, see: doc/llm-integration.md");
  }

  private void cmdLLMTest() throws Exception {
    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open");
      return;
    }

    LLMProvider provider = getOrCreateLLMProvider(cur.get());
    if (provider == null) {
      return;
    }

    io.println("Testing LLM connection...");

    LLMProvider.LLMRequest testRequest =
        LLMProvider.LLMRequest.of(
            "You are a test assistant.", "Say 'Hello from jfr-shell!' and nothing else.");

    try {
      LLMProvider.LLMResponse response = provider.complete(testRequest);
      io.println("Success!");
      io.println("Response: " + response.content());
      io.println("Model: " + response.model());
      io.println("Tokens: " + response.tokensUsed());
      io.println("Duration: " + response.durationMs() + "ms");
    } catch (LLMException e) {
      handleLLMException(e, provider.getConfig());
    }
  }

  private void cmdLLMClear() {
    var cur = sessions.current();
    if (cur.isEmpty()) {
      io.error("No session open");
      return;
    }

    cur.get().variables.remove("__llm_history");
    io.println("Conversation history cleared.");
  }

  private void cmdLLMAudit() {
    try {
      String entries = AuditLogger.readRecentEntries(50);
      io.println("Recent LLM Interactions (last 50):");
      io.println("===================================");
      io.println(entries);
      io.println("");
      io.println("Full log: " + AuditLogger.getAuditLogPath());
    } catch (IOException e) {
      io.println("No audit log found.");
    }
  }

  private LLMProvider getOrCreateLLMProvider(SessionManager.SessionRef session) {
    // Check session variable for cached provider
    Value providerVar = session.variables.get("__llm_provider");
    if (providerVar instanceof ScalarValue sv && sv.value() instanceof LLMProvider) {
      return (LLMProvider) sv.value();
    }

    // Load config and create provider
    try {
      LLMConfig config = LLMConfig.load();
      LLMProvider provider = LLMProvider.create(config);

      if (!provider.isAvailable()) {
        io.error("LLM provider not available: " + config.provider());
        if (config.provider() == LLMConfig.ProviderType.LOCAL) {
          io.println("Ensure Ollama is running: ollama serve");
          io.println("And model is pulled: ollama pull " + config.model());
        } else {
          io.println("Check API key and network connection");
        }
        io.println("Run 'llm status' for more details");
        return null;
      }

      // Cache in session
      session.variables.set("__llm_provider", new ScalarValue(provider));
      if (verbose) {
        io.println("Using " + config.provider() + " provider with model " + config.model());
      }
      return provider;
    } catch (IOException e) {
      if (verbose) {
        io.println("Using default configuration (LOCAL provider)");
      }
      try {
        LLMConfig defaultConfig = LLMConfig.defaults();
        LLMProvider provider = LLMProvider.create(defaultConfig);
        if (!provider.isAvailable()) {
          io.error("Default LLM provider not available");
          io.println("Install and start Ollama: https://ollama.ai");
          return null;
        }
        session.variables.set("__llm_provider", new ScalarValue(provider));
        return provider;
      } catch (Exception ex) {
        io.error("Failed to create LLM provider: " + ex.getMessage());
        return null;
      }
    } catch (Exception e) {
      io.error("Failed to create LLM provider: " + e.getMessage());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private ConversationHistory getOrCreateHistory(SessionManager.SessionRef session) {
    Value historyVar = session.variables.get("__llm_history");
    if (historyVar instanceof VariableStore.MapValue mv) {
      try {
        return ConversationHistory.fromMap(mv.value());
      } catch (Exception e) {
        if (verbose) {
          io.println("Failed to restore history, creating new: " + e.getMessage());
        }
      }
    }
    return new ConversationHistory(20);
  }

  /**
   * Lists available models from Ollama.
   *
   * @param endpoint Ollama endpoint
   * @return formatted list of models, or null if unavailable
   */
  private String listOllamaModels(String endpoint) {
    try {
      java.net.http.HttpClient client =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(5))
              .build();
      java.net.http.HttpRequest request =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(endpoint + "/api/tags"))
              .timeout(java.time.Duration.ofSeconds(5))
              .GET()
              .build();

      java.net.http.HttpResponse<String> response =
          client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        // Parse model names from JSON response
        // Response format: {"models":[{"name":"llama3.1:8b",...},{"name":"mistral:7b",...}]}
        java.util.regex.Pattern pattern =
            java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(response.body());
        java.util.List<String> models = new java.util.ArrayList<>();
        while (matcher.find()) {
          models.add(matcher.group(1));
        }

        if (!models.isEmpty()) {
          return models.stream()
              .map(m -> "  - " + m)
              .collect(java.util.stream.Collectors.joining("\n"));
        }
      }
    } catch (Exception e) {
      // Silently fail - not critical
    }
    return null;
  }

  private void handleLLMException(LLMException e, LLMConfig config) {
    switch (e.getType()) {
      case PROVIDER_UNAVAILABLE -> {
        io.error("LLM provider is not available.");
        io.println("Run 'llm status' to diagnose the issue.");
      }
      case TIMEOUT -> {
        io.error("LLM request timed out. Try a simpler query or increase timeout in config.");
      }
      case INVALID_RESPONSE -> {
        io.error(e.getMessage());
        if (verbose && e.getCause() != null) {
          io.println("Details: " + e.getCause().getMessage());
        }
      }
      case AUTH_FAILED -> {
        io.error("Authentication failed. Check your API key.");
        io.println("Set apiKey in ~/.jfr-shell/llm-config.properties");
        io.println(
            "Or set "
                + (config.provider() == LLMConfig.ProviderType.OPENAI
                    ? "OPENAI_API_KEY"
                    : "ANTHROPIC_API_KEY")
                + " environment variable");
      }
      case RATE_LIMITED -> {
        io.error("Rate limit exceeded. Wait a moment and try again.");
      }
      case NETWORK_ERROR -> {
        io.error("Network error: " + e.getMessage());
      }
      case PARSE_ERROR -> {
        io.error("Failed to parse LLM response: " + e.getMessage());
        if (verbose && e.getCause() != null) {
          e.getCause().printStackTrace();
        }
      }
      default -> {
        io.error("LLM error: " + e.getMessage());
      }
    }
  }

  // ---- Conditional handling ----

  private boolean handleIf(String line) throws Exception {
    // Extract condition after "if "
    String condition = line.length() > 2 ? line.substring(2).trim() : "";
    if (condition.isEmpty()) {
      throw new IllegalArgumentException("if requires a condition");
    }

    // Only evaluate if we're in an active branch
    boolean result = false;
    if (conditionalState.isActive() || !conditionalState.inConditional()) {
      ConditionEvaluator evaluator = new ConditionEvaluator(getSessionStore(), globalStore);
      result = evaluator.evaluate(condition);
    }
    conditionalState.enterIf(result);
    return true;
  }

  private boolean handleElif(String line) throws Exception {
    // Extract condition after "elif "
    String condition = line.length() > 4 ? line.substring(4).trim() : "";
    if (condition.isEmpty()) {
      throw new IllegalArgumentException("elif requires a condition");
    }

    // Only evaluate if needed (previous branches failed and parent is active)
    boolean result = false;
    // We need to check if we should evaluate - only if no branch taken yet
    // The state machine handles this, but we need the parent's active state
    int depth = conditionalState.depth();
    if (depth > 0) {
      // Check if we need to evaluate by temporarily looking at state
      // If condition is needed, it will be used; if branch already taken, it's ignored
      ConditionEvaluator evaluator = new ConditionEvaluator(getSessionStore(), globalStore);
      result = evaluator.evaluate(condition);
    }
    conditionalState.handleElif(result);
    return true;
  }
}
