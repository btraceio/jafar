package io.jafar.shell.unified;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.llm.LlmSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * Context-aware completer for the unified jafar-shell. Shows only relevant completions based on
 * whether a session is open and what type of session it is.
 */
public final class ShellCompleter implements Completer {

  private final SessionManager<?> sessions;
  private final Map<String, ShellModule> moduleById;
  private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter =
      new org.jline.reader.impl.completer.FileNameCompleter();

  // Commands always available
  private static final String[] BASE_COMMANDS = {
    "open",
    "sessions",
    "use",
    "close",
    "info",
    "modules",
    "ask",
    "as-query",
    "explain",
    "llm",
    "set",
    "let",
    "vars",
    "unset",
    "echo",
    "invalidate",
    "if",
    "elif",
    "else",
    "endif",
    "script",
    "record",
    "help",
    "exit",
    "quit"
  };

  // Commands only available when session is open
  private static final String[] SESSION_COMMANDS = {"show"};

  // Shell-level commands whose arguments this completer owns even when a session is open
  private static final Set<String> SHELL_LEVEL =
      Set.of("set", "let", "vars", "unset", "invalidate", "script", "if", "elif", "record");

  private final VariableStore globalStore;

  public ShellCompleter(SessionManager<?> sessions, Map<String, ShellModule> moduleById) {
    this(sessions, moduleById, null);
  }

  public ShellCompleter(
      SessionManager<?> sessions, Map<String, ShellModule> moduleById, VariableStore globalStore) {
    this.sessions = sessions;
    this.moduleById = moduleById;
    this.globalStore = globalStore;
  }

  /**
   * Whether this completer, not the session's module completer, should complete the line: the
   * arguments of a shell-level command ({@code set} up to its {@code =}, {@code vars}, {@code
   * unset}, {@code invalidate}, {@code script}, {@code if}, {@code elif}). Module completers know
   * their query language, not these commands; after {@code set x =} the query is theirs again.
   */
  public boolean ownsArguments(ParsedLine line) {
    if (line.wordIndex() == 0 || line.words().isEmpty()) {
      return false;
    }
    String cmd = line.words().get(0).toLowerCase(Locale.ROOT);
    if (!SHELL_LEVEL.contains(cmd)) {
      return false;
    }
    if (cmd.equals("set") || cmd.equals("let")) {
      String text = line.line();
      int cursor = Math.min(line.cursor(), text.length());
      return text.lastIndexOf('=', cursor - 1) < 0; // no '=' before the cursor
    }
    return true;
  }

  /**
   * Adds this shell's own commands to a first-word completion that a module completer produced,
   * skipping any it already offered, so {@code set}, {@code if}, {@code script}, {@code ask} and
   * the rest complete in every session type.
   */
  public void addShellCommands(ParsedLine line, List<Candidate> candidates) {
    Set<String> offered = new java.util.HashSet<>();
    for (Candidate c : candidates) {
      offered.add(c.value());
    }
    String partial = line.word().toLowerCase(Locale.ROOT);
    for (String cmd : BASE_COMMANDS) {
      if (cmd.startsWith(partial) && offered.add(cmd)) {
        candidates.add(new Candidate(cmd));
      }
    }
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    List<String> words = line.words();
    int wordIndex = line.wordIndex();

    // First word - complete commands
    if (wordIndex == 0) {
      completeCommands(line, candidates);
      return;
    }

    String cmd = words.get(0).toLowerCase(Locale.ROOT);

    switch (cmd) {
      case "show" -> completeShow(line, candidates);
      case "llm" -> completeLlm(line, candidates, words, wordIndex);
      case "ask", "as-query", "analyze", "investigate", "explain" ->
          completeDryRunFlag(line, candidates);
      case "open" -> completeOpen(reader, line, candidates);
      case "use", "close" -> completeSessionRef(line, candidates);
      case "info" -> completeInfoCommand(line, candidates, wordIndex);
      case "set", "let" -> completeSet(line, candidates, words, wordIndex);
      case "vars" -> completeVars(line, candidates, words, wordIndex);
      case "unset" -> completeWords(line, candidates, variableNames(false), "--global");
      case "invalidate" -> {
        if (wordIndex == 1) {
          completeWords(line, candidates, variableNames(true));
        }
      }
      case "script" -> {
        if (wordIndex == 1) {
          completeOpen(reader, line, candidates);
        }
      }
      case "record" -> {
        if (wordIndex == 1) {
          completeWords(line, candidates, Set.of(), "start", "stop", "status");
        } else if (wordIndex == 2 && "start".equals(words.get(1))) {
          completeOpen(reader, line, candidates);
        }
      }
      case "if", "elif" ->
          completeWords(line, candidates, variableNames(false), "exists(", "empty(");
      default -> {
        // No completion for other commands
      }
    }
  }

  private void completeCommands(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word().toLowerCase(Locale.ROOT);

    // Always suggest base commands
    for (String cmd : BASE_COMMANDS) {
      if (cmd.startsWith(partial)) {
        candidates.add(new Candidate(cmd));
      }
    }

    // Suggest session commands only if a session is open
    if (sessions.getCurrent().isPresent()) {
      for (String cmd : SESSION_COMMANDS) {
        if (cmd.startsWith(partial)) {
          candidates.add(new Candidate(cmd));
        }
      }
    }
  }

  /** The {@code --dry-run} flag, offered once a leading dash is typed. */
  private void completeDryRunFlag(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word();
    if (partial.startsWith("-") && "--dry-run".startsWith(partial)) {
      candidates.add(
          new Candidate(
              "--dry-run", "--dry-run", null, "print the request, send nothing", null, null, true));
    }
  }

  /** Subcommands of {@code llm}, offered only in the subcommand position. */
  private void completeLlm(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    if (wordIndex != 1) {
      // `llm dry-run <question>` takes free text.
      return;
    }
    String partial = line.word().toLowerCase(Locale.ROOT);
    for (String[] sub :
        new String[][] {
          {"status", "which backend is used, and why"},
          {"dry-run", "print what 'ask' would send, and send nothing"},
          {"cost", "token usage for this process"},
        }) {
      if (sub[0].startsWith(partial)) {
        candidates.add(new Candidate(sub[0], sub[0], null, sub[1], null, null, true));
      }
    }
  }

  private void completeShow(ParsedLine line, List<Candidate> candidates) {
    var currentSession = sessions.getCurrent();
    if (currentSession.isEmpty()) {
      return;
    }

    var ref = currentSession.get();
    ShellModule module = moduleById.get(ref.session.getType());
    if (module == null) {
      return;
    }

    QueryEvaluator evaluator = module.getQueryEvaluator();
    if (evaluator == null) {
      return;
    }

    String partial = line.word();

    // Check if we're completing a root type or an operator
    String fullLine = line.line();
    int showIndex = fullLine.toLowerCase(Locale.ROOT).indexOf("show");
    if (showIndex < 0) {
      return;
    }

    String queryPart = fullLine.substring(showIndex + 4).trim();

    // If there's a pipe, complete operators
    if (queryPart.contains("|")) {
      completeOperators(evaluator, partial, candidates);
    } else {
      // Complete root types
      completeRootTypes(evaluator, partial, candidates);
    }
  }

  private void completeRootTypes(
      QueryEvaluator evaluator, String partial, List<Candidate> candidates) {
    for (String rootType : evaluator.getRootTypes()) {
      // Add trailing slash for better UX
      String completion = rootType.endsWith("/") ? rootType : rootType + "/";
      if (completion.startsWith(partial)) {
        // Use noSpace=true to allow immediate continuation
        candidates.add(
            new Candidate(completion, completion, null, rootType + " queries", null, null, false));
      }
    }
  }

  private void completeOperators(
      QueryEvaluator evaluator, String partial, List<Candidate> candidates) {
    // Get the part after the last pipe
    String afterPipe = partial;
    int lastPipe = partial.lastIndexOf('|');
    if (lastPipe >= 0) {
      afterPipe = partial.substring(lastPipe + 1).trim();
    }

    for (String operator : evaluator.getOperators()) {
      if (operator.startsWith(afterPipe)) {
        String help = evaluator.getOperatorHelp(operator);
        candidates.add(
            new Candidate(
                operator, operator, null, help != null ? help : operator, null, null, true));
      }
    }
  }

  private void completeOpen(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    if (reader != null) {
      fileCompleter.complete(reader, line, candidates);
    }
  }

  private void completeSessionRef(ParsedLine line, List<Candidate> candidates) {
    String partial = line.word();
    for (SessionManager.SessionRef<?> ref : sessions.list()) {
      String idStr = String.valueOf(ref.id);
      if (idStr.startsWith(partial)) {
        candidates.add(
            new Candidate(
                idStr,
                idStr,
                null,
                ref.session.getFilePath().getFileName().toString(),
                null,
                null,
                true));
      }
      if (ref.alias != null && ref.alias.startsWith(partial)) {
        candidates.add(
            new Candidate(
                ref.alias,
                ref.alias,
                null,
                ref.session.getFilePath().getFileName().toString(),
                null,
                null,
                true));
      }
    }
  }

  private void completeInfoCommand(ParsedLine line, List<Candidate> candidates, int wordIndex) {
    // info [sessionRef]
    if (wordIndex == 1) {
      completeSessionRef(line, candidates);
    }
  }

  /** {@code set [--global] <name>}: the flag, settings and existing names, before the '='. */
  private void completeSet(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    boolean afterFlag = wordIndex == 2 && "--global".equals(words.get(1));
    if (wordIndex != 1 && !afterFlag) {
      return;
    }
    Set<String> names = new TreeSet<>(variableNames(false));
    names.addAll(LlmSettings.names());
    if (afterFlag) {
      completeWords(line, candidates, names);
    } else {
      completeWords(line, candidates, names, "--global");
    }
  }

  /** {@code vars [--global|--session|--all]} or {@code vars --info <name>}. */
  private void completeVars(
      ParsedLine line, List<Candidate> candidates, List<String> words, int wordIndex) {
    if (wordIndex >= 2 && "--info".equals(words.get(wordIndex - 1))) {
      completeWords(line, candidates, variableNames(false));
    } else if (wordIndex == 1) {
      completeWords(line, candidates, Set.of(), "--global", "--session", "--all", "--info");
    }
  }

  private void completeWords(
      ParsedLine line, List<Candidate> candidates, Set<String> names, String... extra) {
    String partial = line.word();
    for (String e : extra) {
      if (e.startsWith(partial)) {
        // 'exists(' and 'empty(' take an argument straight after the parenthesis
        candidates.add(new Candidate(e, e, null, null, null, null, !e.endsWith("(")));
      }
    }
    for (String n : names) {
      if (n.startsWith(partial)) {
        candidates.add(new Candidate(n));
      }
    }
  }

  /** Variable names visible now: the current session's and the global ones. */
  private Set<String> variableNames(boolean lazyOnly) {
    Set<String> names = new TreeSet<>();
    if (globalStore != null) {
      addNames(globalStore, lazyOnly, names);
    }
    sessions.getCurrent().ifPresent(ref -> addNames(ref.variables, lazyOnly, names));
    return names;
  }

  private static void addNames(VariableStore store, boolean lazyOnly, Set<String> names) {
    for (String n : store.names()) {
      if (!lazyOnly || store.get(n) instanceof VariableStore.LazyValue) {
        names.add(n);
      }
    }
  }
}
