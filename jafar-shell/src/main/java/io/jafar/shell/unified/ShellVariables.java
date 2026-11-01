package io.jafar.shell.unified;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import io.jafar.shell.core.llm.LlmSettings;
import io.jafar.shell.core.scripting.EvaluatorLazyValue;
import io.jafar.shell.core.scripting.LazyRows;
import io.jafar.shell.core.scripting.MapLiteralParser;
import io.jafar.shell.core.scripting.MapMerge;
import io.jafar.shell.core.scripting.VariableScopes;
import io.jafar.shell.core.scripting.VariableSubstitutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code set} / {@code vars} / {@code unset} / {@code echo} / {@code invalidate} commands of
 * the unified shell.
 *
 * <p>A value is a string, a number, a boolean, a map literal ({@code {"k": 1}}), a {@code
 * merge(...)} of maps, a copy of another variable, or a query in the current session's language,
 * held lazily ({@link EvaluatorLazyValue}) and run by that session's module {@link QueryEvaluator}
 * on first use. The parsing and the scoping rules are {@code shell-core}'s, shared with {@code
 * jfr-shell}: a variable goes to the current session's store unless {@code --global} is given or no
 * session is open, and is looked up in the session first, then globally.
 *
 * <p>{@code llm.*} settings are text and never coerced: {@code set llm.max-rows = 20} stores {@code
 * "20"}.
 *
 * <p>Every method returns the lines to print; a failure throws {@link IllegalArgumentException}
 * carrying the message to show, so the caller cannot print a confirmation for a rejected command.
 */
final class ShellVariables {
  private static final String SET_USAGE = "Usage: set [--global] <name> = <value|query>";
  private static final String NAME = "[a-zA-Z_][a-zA-Z0-9_]*";

  /** What a query-valued variable needs from the shell. */
  interface QueryContext {
    /** The current session, if one is open. */
    Optional<SessionRef<Session>> currentSession();

    /** The query evaluator of the session's module, or {@code null} if it has none. */
    QueryEvaluator evaluatorFor(Session session);

    /** Resolves other sessions for cross-session operators such as {@code join}. */
    SessionResolver resolver();
  }

  /** No sessions: scalar, map and setting values only. */
  static final QueryContext NO_SESSIONS =
      new QueryContext() {
        @Override
        public Optional<SessionRef<Session>> currentSession() {
          return Optional.empty();
        }

        @Override
        public QueryEvaluator evaluatorFor(Session session) {
          return null;
        }

        @Override
        public SessionResolver resolver() {
          return id -> Optional.empty();
        }
      };

  private final QueryContext queries;
  private final VariableScopes scopes;

  ShellVariables(VariableStore global) {
    this(global, NO_SESSIONS);
  }

  ShellVariables(VariableStore global, QueryContext queries) {
    this.queries = queries;
    this.scopes =
        new VariableScopes(
            global, () -> queries.currentSession().map(ref -> ref.variables).orElse(null));
  }

  /** The scoping rules, for {@code if} conditions and {@code ${var}} in queries. */
  VariableScopes scopes() {
    return scopes;
  }

  /** Handles the text after {@code set}/{@code let}, e.g. {@code --global n = 1}. */
  List<String> set(String rest) {
    boolean global = false;
    if (rest.startsWith("--global ")) {
      global = true;
      rest = rest.substring("--global ".length()).trim();
    }
    int eq = rest.indexOf('=');
    if (eq < 0) {
      throw new IllegalArgumentException(SET_USAGE);
    }
    String name = rest.substring(0, eq).trim();
    String expr = rest.substring(eq + 1).trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Variable name cannot be empty");
    }

    boolean setting = LlmSettings.isSetting(name);
    if (setting) {
      name = name.toLowerCase(Locale.ROOT);
    } else if (LlmSettings.looksLikeSetting(name)) {
      throw new IllegalArgumentException(
          "Unknown setting: " + name + "\nSettings are: " + String.join(", ", LlmSettings.names()));
    } else if (!name.matches(NAME)) {
      throw new IllegalArgumentException(
          "Invalid variable name: "
              + name
              + "\nNames may contain letters, digits and underscores, and cannot start with a"
              + " digit.");
    }

    VariableStore store = scopes.target(global);

    if (setting) {
      // A setting's value is text. Coercing '20' to a number would store 20.0 and the config
      // reader would then fall back to its default without saying so; reading a bare word as a
      // query would answer 'set llm.backend = ollama' with a parse error.
      String text = substitute(expr);
      if (isQuoted(text)) {
        text = text.substring(1, text.length() - 1);
      }
      return store(store, name, new ScalarValue(text));
    }

    // Map literal and merge(): before substitution, as in jfr-shell (merge reads ${name} itself)
    if (expr.startsWith("{")) {
      Map<String, Object> map;
      try {
        map = MapLiteralParser.parse(expr);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid map literal: " + e.getMessage());
      }
      return store(store, name, new MapValue(map));
    }
    if (MapMerge.isMergeCall(expr)) {
      Map<String, Object> merged;
      try {
        // Arguments resolve in the target store, as jfr-shell's merge() does.
        merged = MapMerge.evaluate(expr, arg -> asMapIfSingleMapRow(store.get(arg)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Merge function error: " + e.getMessage());
      }
      return store(store, name, new MapValue(merged));
    }

    String text = substitute(expr);
    if (isQuoted(text)) {
      return store(store, name, new ScalarValue(text.substring(1, text.length() - 1)));
    }
    if (text.matches("-?\\d+")) {
      return store(store, name, new ScalarValue(Long.parseLong(text)));
    }
    if (text.matches("-?\\d+\\.\\d+")) {
      return store(store, name, new ScalarValue(Double.parseDouble(text)));
    }
    if (text.equals("true") || text.equals("false")) {
      return store(store, name, new ScalarValue(Boolean.parseBoolean(text)));
    }

    // A copy of another variable: 'set b = a' or 'set b = $a'. A bare word that names no variable
    // falls through to the query below, since 'objects' or 'events' is a query on its own.
    boolean dollar = text.matches("\\$" + NAME);
    if (dollar || text.matches(NAME)) {
      String source = dollar ? text.substring(1) : text;
      Value existing = scopes.resolve(source);
      if (existing != null) {
        Value copy =
            existing instanceof EvaluatorLazyValue lazy ? lazy.copy() : existing; // own cache
        return store(store, name, copy);
      }
      if (dollar) {
        throw new IllegalArgumentException("Variable not found: " + source);
      }
    }

    return store(store, name, lazyQuery(text));
  }

  /**
   * A lazy variable whose rows are exactly one map — what {@code toMap()} produces — read as that
   * map, so {@code merge()} takes it. jfr-shell stores such a result as a map when it is set, by
   * recognising {@code toMap()} in the parsed JfrPath; this shell cannot inspect another module's
   * parsed query, so it applies the same test to the rows instead. Anything else is unchanged.
   */
  private static Value asMapIfSingleMapRow(Value value) {
    if (value instanceof VariableStore.LazyValue lazy) {
      try {
        if (lazy.get() instanceof List<?> rows
            && rows.size() == 1
            && rows.get(0) instanceof Map<?, ?> row) {
          @SuppressWarnings("unchecked")
          Map<String, Object> map = (Map<String, Object>) row;
          return new MapValue(map);
        }
      } catch (Exception e) {
        throw new IllegalArgumentException(lazy.getQueryString() + ": " + e.getMessage());
      }
    }
    return value;
  }

  /** A query in the current session's language, held until first use. */
  private EvaluatorLazyValue lazyQuery(String query) {
    Optional<SessionRef<Session>> current = queries.currentSession();
    if (current.isEmpty()) {
      throw new IllegalArgumentException(
          "No session open for query evaluation: "
              + query
              + "\nOpen a file to assign a query, or quote a string: set name = \"text\"");
    }
    SessionRef<Session> ref = current.get();
    QueryEvaluator evaluator = queries.evaluatorFor(ref.session);
    if (evaluator == null) {
      throw new IllegalArgumentException(
          "Session " + ref.id + " (" + ref.session.getType() + ") has no query language");
    }
    Object parsed;
    try {
      // Parsed now, so a typo is reported at 'set' rather than at first use.
      parsed = evaluator.parse(query);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Invalid query: "
              + e.getMessage()
              + "\nQuote strings (set name = \"text\"); anything else is read as a "
              + ref.session.getType()
              + " query.");
    }
    return new EvaluatorLazyValue(evaluator, parsed, query, ref, queries.resolver());
  }

  private static List<String> store(VariableStore store, String name, Value value) {
    store.set(name, value);
    return List.of("Set " + name + " = " + value.describe());
  }

  /** {@code vars [--global|--session|--all]} or {@code vars --info <name>}. */
  List<String> vars(String argText) {
    List<String> args = argText.isBlank() ? List.of() : Arrays.asList(argText.trim().split("\\s+"));
    int infoIdx = args.indexOf("--info");
    if (infoIdx >= 0) {
      if (infoIdx + 1 >= args.size()) {
        throw new IllegalArgumentException("Usage: vars --info <name>");
      }
      return info(args.get(infoIdx + 1));
    }
    for (String arg : args) {
      if (!arg.equals("--global") && !arg.equals("--session") && !arg.equals("--all")) {
        throw new IllegalArgumentException(
            "Usage: vars [--global|--session|--all] | vars --info <name>");
      }
    }
    boolean showGlobal = args.isEmpty() || args.contains("--global") || args.contains("--all");
    boolean showSession = args.isEmpty() || args.contains("--session") || args.contains("--all");

    List<String> lines = new ArrayList<>();
    VariableStore global = scopes.global();
    if (showGlobal && !global.isEmpty()) {
      lines.add("Global variables:");
      list(global, lines);
    }
    Optional<SessionRef<Session>> current = queries.currentSession();
    if (showSession && current.isPresent() && !current.get().variables.isEmpty()) {
      lines.add("Session variables (session #" + current.get().id + "):");
      list(current.get().variables, lines);
    }
    if (lines.isEmpty()) {
      lines.add("No variables defined");
    }
    return lines;
  }

  private static void list(VariableStore store, List<String> lines) {
    store.names().stream()
        .sorted()
        .forEach(n -> lines.add("  " + n + " = " + store.get(n).describe()));
  }

  private List<String> info(String name) {
    Value value = scopes.resolve(name);
    if (value == null) {
      throw new IllegalArgumentException("Variable not found: " + name);
    }
    List<String> lines = new ArrayList<>();
    lines.add("Variable: " + name);
    lines.add(
        "Scope: "
            + ("session".equals(scopes.scopeOf(name))
                ? "session #" + queries.currentSession().map(r -> r.id).orElse(0)
                : "global"));
    if (value instanceof VariableStore.LazyValue lazy) {
      lines.add("Type: lazy query");
      lines.add("Source: " + lazy.getQueryString());
      if (lazy instanceof EvaluatorLazyValue bound) {
        String type = bound.sessionType();
        lines.add(
            "Session: #" + bound.sessionId() + (type != null ? " (" + type + ")" : " (gone)"));
      }
      lines.add("Cached: " + (lazy.isCached() ? "yes" : "no"));
      if (lazy.isCached()) {
        try {
          lines.add("Rows: " + LazyRows.size(lazy.get()));
        } catch (Exception e) {
          lines.add("Rows: error - " + e.getMessage());
        }
      }
    } else if (value instanceof MapValue map) {
      lines.add("Type: map");
      lines.add("Size: " + map.value().size() + " entries");
      lines.add("Structure: " + map.describe());
    } else {
      lines.add("Type: scalar");
      lines.add("Value: " + value.describe());
    }
    return lines;
  }

  /** {@code unset <name> [--global]}. */
  List<String> unset(String argText) {
    List<String> args = argText.isBlank() ? List.of() : Arrays.asList(argText.trim().split("\\s+"));
    boolean global = args.contains("--global");
    List<String> names = args.stream().filter(a -> !a.equals("--global")).toList();
    if (names.size() != 1) {
      throw new IllegalArgumentException("Usage: unset <name> [--global]");
    }
    String name = names.get(0);
    String key = LlmSettings.isSetting(name) ? name.toLowerCase(Locale.ROOT) : name;
    if (!scopes.remove(key, global)) {
      throw new IllegalArgumentException("Variable not found: " + key);
    }
    return List.of("Removed " + key);
  }

  /** {@code invalidate <name>}: drop a lazy variable's cached rows so the next use re-runs it. */
  List<String> invalidate(String name) {
    if (name.isBlank()) {
      throw new IllegalArgumentException("Usage: invalidate <name>");
    }
    Value value = scopes.resolve(name.trim());
    if (value == null) {
      throw new IllegalArgumentException("Variable not found: " + name.trim());
    }
    if (!(value instanceof VariableStore.LazyValue lazy)) {
      throw new IllegalArgumentException("Variable " + name.trim() + " is not a lazy value");
    }
    lazy.invalidate();
    return List.of("Invalidated cache for " + name.trim());
  }

  /** {@code echo <text>} with {@code ${var}} substitution. */
  List<String> echo(String text) {
    return List.of(substitute(text));
  }

  /** {@code ${var}} substitution over both scopes; a failure carries the reason. */
  String substitute(String text) {
    if (!VariableSubstitutor.hasVariables(text)) {
      return text;
    }
    try {
      return new VariableSubstitutor(scopes.session(), scopes.global()).substitute(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("Variable substitution failed: " + e.getMessage());
    }
  }

  private static boolean isQuoted(String s) {
    return s.length() >= 2
        && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")));
  }
}
