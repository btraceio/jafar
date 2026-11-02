package io.jafar.shell.unified;

import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.CrossSessionContext;
import io.jafar.shell.core.ModuleSessionFactory;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.ShellModuleLoader;
import io.jafar.shell.core.TableFormatter;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.scripting.CommandRecorder;
import io.jafar.shell.core.scripting.ConditionalException;
import io.jafar.shell.core.scripting.Conditionals;
import io.jafar.shell.core.scripting.ScriptRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified Jafar shell that supports multiple data formats (JFR, heap dumps) through pluggable
 * modules.
 */
public final class Shell implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  // Both null in script mode (see forScript): nothing there may need a terminal
  private final Terminal terminal;
  private final LineReader lineReader;
  private final java.io.PrintWriter out;
  private final java.io.PrintWriter err;
  // Prints "Opening ...", "Session closed" and the like; off in script mode, as in jfr-shell's
  private final boolean verbose;
  // Every error this shell reports, so a script line that reported one counts as failed
  private final ScriptRunner.ErrorTally errorTally = new ScriptRunner.ErrorTally();
  private final List<ShellModule> modules;
  private final Map<String, ShellModule> moduleById;
  private final SessionManager<Session> sessions;
  private final VariableStore globalStore;
  private final ShellVariables variables;
  // The if-blocks of the input being read: the prompt's, or a running script's own (runScript)
  private Conditionals conditionals = new Conditionals();
  // 'record start|stop|status': the lines typed at the prompt, written as a replayable script
  // by jfr-shell's recorder (moved to shell-core), to this shell's own directory
  private final CommandRecorder recorder =
      new CommandRecorder(
          Paths.get(System.getProperty("user.home"), ".jafar-shell", "recordings"), "Jafar Shell");
  // Built once: execute() passes it for every line
  private int scriptDepth;

  private static final int MAX_SCRIPT_DEPTH = 16;
  private static final java.util.regex.Pattern LIMIT_FLAG =
      java.util.regex.Pattern.compile("--limit\\s+(\\d+)");
  private static final java.util.regex.Pattern FORMAT_FLAG =
      java.util.regex.Pattern.compile("--format\\s+(\\S+)");
  private static final java.util.regex.Pattern WHOLE_VARIABLE =
      java.util.regex.Pattern.compile(
          "^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}((?:\\s+--\\S+(?:\\s+\\S+)?)*)$");
  private boolean running = true;
  // Never holds an llm.api-key line; null in script mode, which keeps no history
  private final io.jafar.shell.core.SecretFilteringHistory history;
  private final ShellCompleter fallbackCompleter;
  private final org.jline.reader.Completer completer; // what Tab runs at this shell's prompt
  private final Object moduleContext; // Context for module completers (e.g., CommandDispatcher)
  private final Map<String, org.jline.reader.Completer>
      completerCache; // Cache completers per module
  private io.jafar.shell.cli.LlmCommands llmCommands;

  // The most recent query result, so 'explain' has something to explain after a hand-typed query
  // and not only after 'ask'. Kept here rather than inside LlmCommands because the LLM handler is
  // built lazily — recording a result must not be what loads a backend.
  private String lastResultQuery;
  private List<Map<String, Object>> lastResultRows;

  /** The interactive shell, on the system terminal. */
  public Shell() throws IOException {
    this(TerminalBuilder.builder().system(true).build(), null, null, true);
  }

  /**
   * A shell for {@code jafar-shell script}: no terminal, no line reader and no history, so it runs
   * where there is no console and never touches the interactive history file. Results go to {@code
   * out}, errors to {@code err}, and the informational lines {@code open}/{@code close}/{@code use}
   * print are left out, as jfr-shell's script mode leaves them out — unless {@code
   * jfr.shell.verbose}/{@code JFR_SHELL_VERBOSE} asks for them, as it does there.
   */
  static Shell forScript(java.io.PrintStream out, java.io.PrintStream err) {
    return new Shell(
        null,
        new java.io.PrintWriter(out, true),
        new java.io.PrintWriter(err, true),
        CommandDispatcher.isVerboseEnabled());
  }

  /** A shell on the given terminal; tests pass one over in-memory streams. */
  Shell(Terminal terminal) throws IOException {
    this(terminal, null, null, true);
  }

  private Shell(
      Terminal terminal,
      java.io.PrintWriter scriptOut,
      java.io.PrintWriter scriptErr,
      boolean verbose) {
    // Named apart from the out/err fields: code in this constructor (the IO adapter below) must
    // reach the fields, and a parameter of the same name would be what it captured instead
    this.terminal = terminal;
    this.out = terminal != null ? terminal.writer() : scriptOut;
    this.err = terminal != null ? terminal.writer() : scriptErr;
    this.verbose = verbose;
    this.modules = ShellModuleLoader.loadAll();
    this.moduleById = new HashMap<>();
    for (ShellModule module : modules) {
      moduleById.put(module.getId(), module);
    }

    // Create session manager with factory that routes to appropriate module
    this.sessions = new SessionManager<>(new ModuleSessionFactory(modules), null);
    this.globalStore = new VariableStore();
    this.variables =
        new ShellVariables(
            globalStore,
            new ShellVariables.QueryContext() {
              @Override
              public Optional<SessionManager.SessionRef<Session>> currentSession() {
                return sessions.getCurrent();
              }

              @Override
              public QueryEvaluator evaluatorFor(Session session) {
                ShellModule module = moduleById.get(session.getType());
                return module != null ? module.getQueryEvaluator() : null;
              }

              @Override
              public io.jafar.shell.core.SessionResolver resolver() {
                return buildCrossSessionContext();
              }
            });

    Path histPath = Paths.get(System.getProperty("user.home"), ".jafar-shell", "history");
    if (terminal != null) {
      try {
        Files.createDirectories(histPath.getParent());
      } catch (Exception ignore) {
      }
    }

    this.history = terminal != null ? new io.jafar.shell.core.SecretFilteringHistory() : null;
    java.util.Map<String, Object> vars = new java.util.HashMap<>();
    vars.put(org.jline.reader.LineReader.HISTORY_FILE, histPath);

    // Create a CommandDispatcher for modules that need it (like JFR shell)
    CommandDispatcher.IO ioAdapter =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            out.println(s);
            flush();
          }

          @Override
          public void printf(String fmt, Object... args) {
            out.printf(fmt, args);
            flush();
          }

          @Override
          public void error(String s) {
            // Counted like the shell's own errors, so a script stops on a failing JFR command.
            // The dispatcher's own catch already says "Error: ..."; do not print it twice.
            // Shell.this: a bare error(...) here is this IO's own method, and would recurse
            if (s.startsWith("Error: ")) {
              Shell.this.error(s);
            } else {
              printError(s);
            }
          }
        };

    // Session change listener (no-op for unified shell - prompt updates handled separately)
    CommandDispatcher.SessionChangeListener sessionChangeListener = current -> {};

    @SuppressWarnings("unchecked")
    SessionManager<JFRSession> jfrSessions =
        (SessionManager<JFRSession>) (SessionManager<?>) sessions;
    // Shares globalStore: jfr-shell's completer, used for JFR sessions here, completes ${...}
    // global names from the dispatcher's store, which would otherwise be a private empty one.
    this.moduleContext =
        new CommandDispatcher(jfrSessions, ioAdapter, sessionChangeListener, null, globalStore);

    // Create fallback completer for basic commands
    this.fallbackCompleter = new ShellCompleter(sessions, moduleById, globalStore);

    // Cache for module-specific completers (create once per module, not on every keystroke)
    this.completerCache = new HashMap<>();

    // Create dynamic completer that delegates to module-specific completers
    org.jline.reader.Completer dynamicCompleter =
        (reader, line, candidates) -> {
          Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
          if (current.isPresent()) {
            SessionManager.SessionRef<Session> ref = current.get();
            String moduleType = ref.session.getType();
            ShellModule module = moduleById.get(moduleType);
            if (module != null) {
              // Get or create cached completer for this module
              org.jline.reader.Completer moduleCompleter =
                  completerCache.computeIfAbsent(
                      moduleType, type -> module.getCompleter(sessions, moduleContext));
              if (moduleCompleter != null) {
                if (fallbackCompleter.ownsArguments(line)) {
                  fallbackCompleter.complete(reader, line, candidates);
                  return;
                }
                moduleCompleter.complete(reader, line, candidates);
                if (line.wordIndex() == 0) {
                  fallbackCompleter.addShellCommands(line, candidates);
                }
                return;
              }
            }
          }
          // Fall back to basic completer
          fallbackCompleter.complete(reader, line, candidates);
        };

    // Create parser that doesn't treat backslash as escape char (for raw string literals)
    org.jline.reader.impl.DefaultParser parser = new org.jline.reader.impl.DefaultParser();
    parser.setEscapeChars(null); // Disable backslash escape processing

    this.lineReader =
        terminal == null
            ? null
            : LineReaderBuilder.builder()
                .terminal(terminal)
                .variables(vars)
                .history(history)
                .completer(dynamicCompleter)
                .parser(parser)
                .build();
    this.completer = dynamicCompleter;
  }

  /** The prompt's completer, for tests that check completion offers only what runs. */
  org.jline.reader.Completer completer() {
    return completer;
  }

  /** The prompt's line reader; null in script mode. */
  LineReader lineReader() {
    return lineReader;
  }

  public void run() {
    printBanner();

    while (running) {
      try {
        String prompt = getPrompt();
        String input = lineReader.readLine(prompt);
        if (input == null || input.isBlank()) continue;
        handleLine(input);
      } catch (UserInterruptException e) {
        out.println("^C");
        flush();
      } catch (EndOfFileException e) {
        stopRecordingOnExit();
        out.println();
        out.println("Goodbye!");
        flush();
        running = false;
      } catch (Exception e) {
        out.println("Error: " + e.getMessage());
        e.printStackTrace();
        flush();
      }
    }
  }

  /**
   * One line typed at the prompt (or piped into it): {@code exit}/{@code quit}, {@code record},
   * then {@link #execute}. Kept apart from {@link #execute} because these three belong to the
   * prompt, not to scripts: a script line is neither recorded nor able to end the shell.
   */
  void handleLine(String input) {
    input = input.trim();
    if (input.isEmpty()) {
      return;
    }

    if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
      if (conditionals.state().inConditional()) {
        out.println("Warning: " + conditionals.state().depth() + " unclosed conditional block(s)");
        flush();
        conditionals.state().reset();
      }
      stopRecordingOnExit();
      running = false;
      return;
    }

    // Never written to the recording; in a branch that is not running it is skipped, like any line
    if (matchesCommand(input, "record")) {
      if (conditionals.isActive()) {
        handleRecord(argumentOf(input));
      }
      return;
    }

    // 'help' is not recorded (jfr-shell does not either): it is not part of an analysis. 'script'
    // is, unlike jfr-shell: the lines a script runs are not recorded (only prompt lines are), so
    // leaving the invocation out too would make the replay silently skip that script.
    if (recorder.isRecording() && !"help".equalsIgnoreCase(input)) {
      try {
        recorder.recordCommand(input);
      } catch (IOException e) {
        printError("Failed to record command: " + e.getMessage());
      }
    }

    if (!execute(input)) {
      out.println("Unknown command: " + input);
      out.println("Type 'help' for available commands.");
      flush();
    }
  }

  /** {@code record start [path] | record stop | record status}, as in jfr-shell. */
  private void handleRecord(String argText) {
    String[] parts = argText.isEmpty() ? new String[0] : argText.split("\\s+", 2);
    String usage = "Usage: record start [path] | record stop | record status";
    if (parts.length == 0) {
      printError(usage);
      return;
    }
    try {
      switch (parts[0].toLowerCase(java.util.Locale.ROOT)) {
        case "start" -> {
          recorder.start(parts.length > 1 ? Paths.get(expandHome(parts[1].trim())) : null);
          out.println("Recording started: " + recorder.getCurrentRecordingPath());
        }
        case "stop" -> {
          recorder.stop();
          out.println("Recording stopped: " + recorder.getCurrentRecordingPath());
        }
        case "status" ->
            out.println(
                recorder.isRecording()
                    ? "Recording to: " + recorder.getCurrentRecordingPath()
                    : "Not currently recording");
        default -> printError("Unknown record command: " + parts[0] + "\n" + usage);
      }
    } catch (IOException | IllegalStateException e) {
      printError(e.getMessage());
    }
    flush();
  }

  /** Leaving the shell saves a recording still in progress, as jfr-shell does on {@code exit}. */
  private void stopRecordingOnExit() {
    if (!recorder.isRecording()) {
      return;
    }
    try {
      recorder.stop();
      out.println("Recording saved to: " + recorder.getCurrentRecordingPath());
    } catch (IOException e) {
      out.println("Warning: Failed to save recording: " + e.getMessage());
    }
    flush();
  }

  /**
   * Runs one command line: typed at the prompt, or read from a script by {@code script}.
   *
   * @return {@code false} when the line is not a command this shell knows
   */
  boolean execute(String input) {
    input = input.trim();
    if (input.isEmpty()) {
      return true;
    }

    // if/elif/else/endif are taken even inside a branch that is not running, so nesting stays
    // balanced; every other line in such a branch is skipped.
    try {
      if (conditionals.handle(input, variables.scopes())) {
        return true;
      }
    } catch (ConditionalException e) {
      // A failed condition has entered its block with no branch running; printError marks the
      // line failed, so a script counts it (and stops unless continue-on-error).
      printError(e.getMessage());
      return true;
    }
    if (!conditionals.isActive()) {
      return true;
    }

    if ("help".equalsIgnoreCase(input)) {
      printHelp();
      return true;
    }

    if ("modules".equalsIgnoreCase(input)) {
      printModules();
      return true;
    }

    if (matchesCommand(input, "open")) {
      String path = argumentOf(input);
      if (path.isEmpty()) {
        printError("Usage: open <path>");
      } else {
        handleOpen(path);
      }
      return true;
    }

    if ("sessions".equalsIgnoreCase(input)) {
      handleSessions();
      return true;
    }

    // 'session' is jfr-shell's alias for 'use'
    if (matchesCommand(input, "use") || matchesCommand(input, "session")) {
      String ref = argumentOf(input);
      if (ref.isEmpty()) {
        printError("Usage: " + (input.startsWith("use") ? "use" : "session") + " <id|alias>");
      } else {
        handleUse(ref);
      }
      return true;
    }

    if (input.startsWith("close")) {
      handleClose(input);
      return true;
    }

    if (input.startsWith("info")) {
      handleInfo(input);
      return true;
    }

    // '?' is short for 'ask', with or without a space after it, so '?why is this slow' and
    // 'ask why is this slow' are one command. No query language here starts with it.
    if (input.startsWith("?")) {
      llmCommands().analyze(input.substring(1).trim());
      return true;
    }

    if (matchesCommand(input, "ask")
        || matchesCommand(input, "analyze")
        || matchesCommand(input, "investigate")) {
      llmCommands().analyze(argumentOf(input));
      return true;
    }

    if (matchesCommand(input, "as-query")) {
      llmCommands().asQuery(argumentOf(input));
      return true;
    }

    if (input.equals("explain") || input.startsWith("explain ")) {
      llmCommandsWithLastResult().explain(input.length() > 7 ? input.substring(8).trim() : "");
      return true;
    }

    if (input.equals("llm") || input.startsWith("llm ")) {
      String rest = input.length() > 3 ? input.substring(4).trim() : "";
      llmCommands().llm(rest.isEmpty() ? List.of() : List.of(rest.split("\\s+")));
      return true;
    }

    String jfrCommand = jfrCommandOf(input);
    if (jfrCommand != null) {
      handleJfrCommand(jfrCommand, input);
      return true;
    }

    if (handleVariableCommand(input)) {
      return true;
    }

    if (matchesCommand(input, "show")) {
      String query = argumentOf(input);
      if (query.isEmpty()) {
        printError("Usage: show <query>");
      } else {
        handleShow(query);
      }
      return true;
    }

    if (input.equals("checkLeaks()")) {
      // Launch interactive wizard
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isPresent()) {
        Object session = current.get().session;
        if (terminal == null) {
          printError("checkLeaks() is an interactive wizard; in a script use checkLeaks(...)");
        } else if (session.getClass().getName().endsWith(".HeapSession")) {
          try {
            // Use reflection to call runLeakDetectionWizard
            var menuClass = Class.forName("io.jafar.shell.core.InteractiveMenu");
            var menuConstructor = menuClass.getConstructor(Terminal.class, LineReader.class);
            Object menu = menuConstructor.newInstance(terminal, lineReader);

            var wizardMethod = session.getClass().getMethod("runLeakDetectionWizard", menuClass);
            wizardMethod.invoke(session, menu);
          } catch (Exception e) {
            error("Error launching wizard: " + e.getMessage());
            printStackTraceIfInteractive(e);
          }
        } else {
          error("checkLeaks() requires an active heap dump session");
          out.println("Use 'open <file.hprof>' to open a heap dump first");
        }
      } else {
        error("checkLeaks() requires an active heap dump session");
        out.println("Use 'open <file.hprof>' to open a heap dump first");
      }
      flush();
      return true;
    }

    if (input.startsWith("checkLeaks(")) {
      handleShow(input);
      return true;
    }

    if (isQueryForCurrentSession(input)) {
      handleShow(input);
      return true;
    }

    if (input.equals("script") || input.startsWith("script ")) {
      handleScript(argumentOf(input));
      return true;
    }

    return false;
  }

  /** The dispatcher the JFR commands and jfr-shell's completer use; package-private for tests. */
  CommandDispatcher jfrDispatcher() {
    return (CommandDispatcher) moduleContext;
  }

  private void printError(String message) {
    error("Error: " + message);
  }

  /** Prints one reported error as given, and counts it (see {@link ScriptRunner.ErrorTally}). */
  private void error(String line) {
    errorTally.record(line);
    err.println(line);
    flush();
  }

  private void flush() {
    out.flush();
    if (err != out) { // the same terminal writer at the prompt
      err.flush();
    }
  }

  /** A stack trace belongs at the prompt, where it always was; a script reports the message. */
  private void printStackTraceIfInteractive(Exception e) {
    if (terminal != null) {
      e.printStackTrace();
    }
  }

  /**
   * Commands that jfr-shell's {@link CommandDispatcher} implements and that read only the current
   * JFR session: run by that dispatcher, which shares this shell's {@link #sessions} and prints to
   * this terminal. Shared with {@link ShellCompleter} so completion offers exactly what runs.
   */
  static final java.util.Set<String> JFR_COMMANDS =
      java.util.Set.of("metadata", "chunks", "chunk", "cp", "constants", "backend");

  // The same names as an array, for the per-line match below: iterating it allocates nothing.
  private static final String[] JFR_COMMAND_NAMES = JFR_COMMANDS.toArray(new String[0]);

  /**
   * True for {@code metadata --summary} or {@code chunks}; false for a query that starts with the
   * same word. {@code metadata/jdk.ExecutionSample}, {@code constants/...} and {@code chunks[...]}
   * are not the bare word. A line with a pipeline ({@code chunks --format json | count()}) or
   * {@code --limit} is a query too: jfr-shell's commands have neither, so its handlers would print
   * every row and ignore the rest. The one pipeline a command takes, {@code cp <type> |
   * crossref()}, stays a command.
   */
  static boolean isJfrCommand(String input) {
    return jfrCommandOf(input) != null;
  }

  /**
   * The JFR command {@code input} starts with, or {@code null}. Runs for every line typed or read
   * from a script, so it scans in place: no split, no regex, no lower-cased copy.
   */
  static String jfrCommandOf(String input) {
    int n = input.length();
    int start = skipSpace(input, 0);
    int end = skipWord(input, start);
    String command = null;
    for (String name : JFR_COMMAND_NAMES) {
      if (name.length() == end - start && input.regionMatches(true, start, name, 0, end - start)) {
        command = name;
        break;
      }
    }
    if (command == null) {
      return null;
    }
    // The remaining words: a pipeline (a word starting with '|') or --limit makes it a query.
    // A '|' inside a word is an argument, e.g. 'metadata --regex ^(jdk|datadog)\..*'.
    for (int word = skipSpace(input, end); word < n; word = skipSpace(input, end)) {
      end = skipWord(input, word);
      if (input.charAt(word) == '|') {
        return isCrossref(command, input, word) ? command : null;
      }
      if (end - word == 7 && input.startsWith("--limit", word)) {
        return null;
      }
    }
    return command;
  }

  /** {@code cp <type> | crossref()}: the form jfr-shell's {@code cmdCp} takes as a command. */
  private static boolean isCrossref(String command, String input, int pipe) {
    if (!command.equals("cp") && !command.equals("constants")) {
      return false;
    }
    int n = input.length();
    if (pipe + 1 < n && !Character.isWhitespace(input.charAt(pipe + 1))) {
      return false; // cmdCp needs '|' and 'crossref()' as separate words
    }
    int word = skipSpace(input, pipe + 1);
    int end = skipWord(input, word);
    return end - word == 10
        && input.regionMatches(true, word, "crossref()", 0, 10)
        && skipSpace(input, end) == n;
  }

  private static int skipSpace(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipWord(String s, int i) {
    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private void handleJfrCommand(String command, String input) {
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isEmpty() || !(current.get().session instanceof JFRSession)) {
      printError(
          "'"
              + command
              + "' needs a JFR session; "
              + current
                  .map(
                      ref ->
                          "session #" + ref.id + " is " + ref.session.getType() + ". Open a .jfr ")
                  .orElse("open a .jfr ")
              + "recording or switch to one with 'use <id>'");
      return;
    }
    jfrDispatcher().dispatch(input);
  }

  /**
   * {@code script <path> [args...]}: runs each line of the file through {@link #execute}, with
   * {@code $1}, {@code ${2:-default}} and {@code $@} replaced by the arguments.
   */
  private void handleScript(String argText) {
    String[] parts = argText.isBlank() ? new String[0] : argText.trim().split("\\s+");
    if (parts.length == 0) {
      printError("Usage: script <path> [args...]");
      return;
    }
    Path path = Paths.get(expandHome(parts[0]));
    if (!Files.exists(path)) {
      printError("Script file not found: " + path);
      return;
    }
    List<String> arguments = List.of(parts).subList(1, parts.length);
    try {
      ScriptRunner.ExecutionResult result =
          runScript(path.getFileName().toString(), Files.readAllLines(path), arguments, false);
      if (result.hasErrors()) {
        // The failures inside were reported line by line; this makes the 'script' line itself
        // fail too, so a script that runs another stops when the inner one does.
        ScriptRunner.ScriptError first = result.getErrors().get(0);
        errorTally.record(
            "script "
                + path.getFileName()
                + " line "
                + first.getLineNumber()
                + ": "
                + first.getMessage());
      }
    } catch (IOException e) {
      printError("Cannot read script " + path + ": " + e.getMessage());
    }
  }

  /**
   * Runs script lines and prints the report both shells print: the errors, then {@code Executed n/m
   * commands successfully}, or {@code Script executed successfully: n commands} when nothing
   * failed. A line counts as failed when it throws, is not a command, or reported an error.
   *
   * <p>Both entry points come through here — {@code script} at the prompt and {@code jafar-shell
   * script} — so both get the nesting cap and a script's own if-block stack. A script that leaves
   * blocks open has failed: the warning is listed as an error of its last line.
   *
   * @param name the script's file name, or {@code -} for stdin, for messages
   */
  ScriptRunner.ExecutionResult runScript(
      String name, List<String> lines, List<String> arguments, boolean continueOnError) {
    if (scriptDepth >= MAX_SCRIPT_DEPTH) {
      // A script that runs itself would otherwise end in a StackOverflowError, which nothing
      // catches, and take the shell down with it.
      printError("Scripts nested more than " + MAX_SCRIPT_DEPTH + " deep; not running " + name);
      return new ScriptRunner.ExecutionResult(
          0,
          List.of(
              new ScriptRunner.ScriptError(
                  0, "", "scripts nested more than " + MAX_SCRIPT_DEPTH + " deep")));
    }
    ScriptRunner runner =
        new ScriptRunner(
            ScriptRunner.failingOnReportedErrors(this::execute, errorTally), arguments);
    runner.setContinueOnError(continueOnError);
    // The script gets an if-block stack of its own, so it can neither leave the caller inside a
    // block (a false branch would skip everything typed next) nor close one of the caller's (a
    // stray 'endif' is an error in the script instead)
    Conditionals callers = conditionals;
    conditionals = new Conditionals();
    ScriptRunner.ExecutionResult result;
    int unclosed;
    scriptDepth++;
    try {
      result = runner.execute(lines);
      unclosed = conditionals.state().depth();
    } finally {
      scriptDepth--;
      conditionals = callers;
    }
    if (unclosed > 0) {
      // Left open, a false branch would go on skipping every command typed after the script.
      printError(name + " left " + unclosed + " if-block(s) open; closed them");
      List<ScriptRunner.ScriptError> errors = new ArrayList<>(result.getErrors());
      errors.add(
          new ScriptRunner.ScriptError(lines.size(), "", "left " + unclosed + " if-block(s) open"));
      result = new ScriptRunner.ExecutionResult(result.getSuccessCount(), errors);
    }
    if (result.hasErrors()) {
      err.println();
      err.println("Script completed with errors:");
      for (ScriptRunner.ScriptError error : result.getErrors()) {
        err.println(error);
      }
      err.println(
          String.format(
              "%nExecuted %d/%d commands successfully.",
              result.getSuccessCount(), result.getSuccessCount() + result.getErrors().size()));
    } else if (result.getSuccessCount() > 0) {
      out.println();
      out.println("Script executed successfully: " + result.getSuccessCount() + " commands");
    }
    flush();
    return result;
  }

  private static String expandHome(String path) {
    if (path.startsWith("~" + java.io.File.separator)) {
      return System.getProperty("user.home") + path.substring(1);
    }
    return path.equals("~") ? System.getProperty("user.home") : path;
  }

  /**
   * {@code set}/{@code let}/{@code vars}/{@code unset}/{@code echo}/{@code invalidate}. Returns
   * whether the input was one of them. Variables live in the current session's store or, with
   * {@code --global} or no session open, in {@link #globalStore}; the LLM host adapter reads {@code
   * llm.*} settings from both, session first.
   */
  private boolean handleVariableCommand(String input) {
    String command = input.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
    String arg = input.length() > command.length() ? input.substring(command.length()).trim() : "";
    try {
      List<String> lines;
      switch (command) {
        case "set", "let" -> lines = variables.set(arg);
        case "vars" -> lines = variables.vars(arg);
        case "unset" -> lines = variables.unset(arg);
        case "echo" -> lines = variables.echo(arg);
        case "invalidate" -> lines = variables.invalidate(arg);
        default -> {
          return false;
        }
      }
      // "Set x = ...", "Removed x", "Invalidated cache for x" are confirmations, which script
      // mode leaves out as jfr-shell's does; vars and echo are what was asked for
      boolean confirmation = !command.equals("vars") && !command.equals("echo");
      if (verbose || !confirmation) {
        lines.forEach(out::println);
      }
    } catch (IllegalArgumentException e) {
      error("Error: " + e.getMessage());
    }
    flush();
    return true;
  }

  private boolean isQueryForCurrentSession(String input) {
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isEmpty()) {
      return false;
    }
    ShellModule module = moduleById.get(current.get().session.getType());
    QueryEvaluator evaluator = module != null ? module.getQueryEvaluator() : null;
    return evaluator != null && startsWithRoot(input, evaluator.getRootTypes());
  }

  /** True for {@code objects | count} or {@code events/jdk.FileRead}: a query with no 'show'. */
  static boolean startsWithRoot(String input, List<String> rootTypes) {
    return rootTypes.contains(input.split("[/\\[\\s|]", 2)[0]);
  }

  private String getPrompt() {
    // Inside an if-block the prompt shows the nesting depth, as in jfr-shell
    if (conditionals.state().inConditional()) {
      return "...(" + conditionals.state().depth() + ")> ";
    }
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isPresent()) {
      SessionManager.SessionRef<Session> ref = current.get();
      String type = ref.session.getType();
      return type + "> ";
    }
    return "jafar> ";
  }

  public void openIfPresent(Path filePath) {
    if (filePath != null && Files.exists(filePath)) {
      try {
        handleOpen(filePath.toString());
      } catch (Exception e) {
        error("Error opening file: " + e.getMessage());
        flush();
      }
    }
  }

  private void handleOpen(String pathStr) {
    // Expand ~ to user home directory
    if (pathStr.startsWith("~" + java.io.File.separator)) {
      pathStr = System.getProperty("user.home") + pathStr.substring(1);
    } else if (pathStr.equals("~")) {
      pathStr = System.getProperty("user.home");
    }

    Path path = Paths.get(pathStr);
    if (!Files.exists(path)) {
      error("Error: file not found: " + path);
      flush();
      return;
    }

    // Find a module that can handle this file
    ShellModule handler = null;
    for (ShellModule module : modules) {
      if (module.canHandle(path)) {
        handler = module;
        break;
      }
    }

    if (handler == null) {
      error("Error: No module can handle file: " + path);
      err.println("Supported extensions:");
      for (ShellModule module : modules) {
        err.println(
            "  "
                + module.getDisplayName()
                + ": "
                + String.join(", ", module.getSupportedExtensions()));
      }
      flush();
      return;
    }

    if (verbose) {
      out.println("Opening " + path + " with " + handler.getDisplayName() + "...");
      flush();
    }

    try {
      SessionManager.SessionRef<Session> ref = sessions.open(path, null);
      if (verbose) {
        out.println("Session " + ref.id + " opened: " + path.getFileName());
      }

      // Debug: Verify session state
      if (System.getProperty("jfr.shell.completion.debug") != null) {
        out.println("[DEBUG] Session type: " + ref.session.getType());
        out.println("[DEBUG] Session file path: " + ref.session.getFilePath());
        out.println("[DEBUG] Session is current: " + sessions.getCurrent().isPresent());
      }

      flush();
    } catch (Exception e) {
      error("Error opening session: " + e.getMessage());
      printStackTraceIfInteractive(e);
      flush();
    }
  }

  private void handleSessions() {
    List<SessionManager.SessionRef<Session>> allSessions = sessions.list();
    if (allSessions.isEmpty()) {
      out.println("No open sessions");
    } else {
      out.println("Open sessions:");
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      for (SessionManager.SessionRef<Session> ref : allSessions) {
        String marker = current.isPresent() && current.get().id == ref.id ? "* " : "  ";
        String alias = ref.alias != null ? " (" + ref.alias + ")" : "";
        out.println(
            marker
                + ref.id
                + alias
                + ": "
                + ref.session.getFilePath().getFileName()
                + " ["
                + ref.session.getType()
                + "]");
      }
    }
    flush();
  }

  private void handleUse(String idOrAlias) {
    if (sessions.use(idOrAlias)) {
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isPresent() && verbose) {
        SessionManager.SessionRef<Session> ref = current.get();
        out.println(
            "Switched to session " + ref.id + ": " + ref.session.getFilePath().getFileName());
      }
    } else {
      error("No such session: " + idOrAlias);
    }
    flush();
  }

  private void handleClose(String input) {
    String[] parts = input.split("\\s+");
    if (parts.length == 1) {
      // Close current session
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isEmpty()) {
        error("No session open");
      } else {
        try {
          closeSession(String.valueOf(current.get().id));
          if (verbose) {
            out.println("Session closed");
          }
        } catch (Exception e) {
          error("Error closing session: " + e.getMessage());
        }
      }
    } else {
      String idOrAlias = parts[1];
      try {
        if (closeSession(idOrAlias)) {
          if (verbose) {
            out.println("Session closed: " + idOrAlias);
          }
        } else {
          error("No such session: " + idOrAlias);
        }
      } catch (Exception e) {
        error("Error closing session: " + e.getMessage());
      }
    }
    flush();
  }

  /**
   * Closes a session (which clears its own variables) and drops the cached rows of global variables
   * bound to it, which could otherwise keep the closed file's data in memory.
   */
  private boolean closeSession(String idOrAlias) throws Exception {
    Optional<SessionManager.SessionRef<Session>> ref = sessions.get(idOrAlias);
    if (ref.isEmpty() || !sessions.close(idOrAlias)) {
      return false;
    }
    int id = ref.get().id;
    // Copies of a variable can sit in any store; each surviving one may hold its rows
    io.jafar.shell.core.scripting.EvaluatorLazyValue.forgetSession(globalStore, id);
    for (SessionManager.SessionRef<Session> other : sessions.list()) {
      io.jafar.shell.core.scripting.EvaluatorLazyValue.forgetSession(other.variables, id);
    }
    return true;
  }

  private void handleInfo(String input) {
    String[] parts = input.split("\\s+");
    Optional<SessionManager.SessionRef<Session>> ref;

    if (parts.length == 1) {
      ref = sessions.getCurrent();
      if (ref.isEmpty()) {
        error("No session open");
        flush();
        return;
      }
    } else {
      ref = sessions.get(parts[1]);
      if (ref.isEmpty()) {
        error("No such session: " + parts[1]);
        flush();
        return;
      }
    }

    SessionManager.SessionRef<Session> session = ref.get();
    out.println("Session " + session.id + ":");
    out.println("  Type: " + session.session.getType());
    out.println("  File: " + session.session.getFilePath());
    out.println("  Closed: " + session.session.isClosed());

    Map<String, Object> stats = session.session.getStatistics();
    if (!stats.isEmpty()) {
      out.println("  Statistics:");
      for (Map.Entry<String, Object> entry : stats.entrySet()) {
        out.println("    " + entry.getKey() + ": " + entry.getValue());
      }
    }
    flush();
  }

  private void handleShow(String query) {
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isEmpty()) {
      error("No session open. Use 'open <file>' first.");
      flush();
      return;
    }

    SessionManager.SessionRef<Session> ref = current.get();
    ShellModule module = moduleById.get(ref.session.getType());
    if (module == null) {
      error("Error: Module not found for session type: " + ref.session.getType());
      flush();
      return;
    }

    QueryEvaluator evaluator = module.getQueryEvaluator();
    if (evaluator == null) {
      error("Error: Module does not support queries: " + module.getId());
      flush();
      return;
    }

    // 'show ${var}' alone prints a query-valued variable's rows (cached after first use), rather
    // than substituting its first value into the line
    java.util.regex.Matcher whole = query.startsWith("${") ? WHOLE_VARIABLE.matcher(query) : null;
    if (whole != null
        && whole.matches()
        && variables.scopes().resolve(whole.group(1)) instanceof VariableStore.LazyValue lazy) {
      try {
        Object rows = lazy.get();
        // The flags may use variables too ('--limit ${n}'); the row variable itself is not text
        String flags = variables.substitute(whole.group(2));
        java.util.regex.Matcher fmt = FORMAT_FLAG.matcher(flags);
        java.util.regex.Matcher lim = LIMIT_FLAG.matcher(flags);
        if (lim.find() && rows instanceof List<?> list) {
          rows = list.subList(0, Math.min(Integer.parseInt(lim.group(1)), list.size()));
        }
        rememberResult(lazy.getQueryString(), rows);
        printResult(rows, fmt.find() ? fmt.group(1) : ref.outputFormat);
      } catch (Exception e) {
        printError(e.getMessage());
      }
      return;
    }

    // ${var} first, as in 'objects[shallow > ${min}]', and before the flags are read so that
    // '--limit ${n}' is a number by the time it is parsed
    String cleanQuery;
    try {
      cleanQuery = variables.substitute(query);
    } catch (IllegalArgumentException e) {
      printError(e.getMessage());
      return;
    }

    // Extract --limit and --format flags from query string
    Integer limit = null;
    String format = ref.outputFormat;

    java.util.regex.Matcher limitMatcher = LIMIT_FLAG.matcher(cleanQuery);
    if (limitMatcher.find()) {
      limit = Integer.parseInt(limitMatcher.group(1));
      cleanQuery = limitMatcher.replaceFirst("").trim();
    }

    java.util.regex.Matcher formatMatcher = FORMAT_FLAG.matcher(cleanQuery);
    if (formatMatcher.find()) {
      format = formatMatcher.group(1).toLowerCase();
      cleanQuery = formatMatcher.replaceFirst("").trim();
    }

    try {
      CrossSessionContext ctx = buildCrossSessionContext();
      Object result = evaluator.evaluate(ref.session, cleanQuery, ctx);
      if (limit != null && result instanceof List<?> list) {
        result = list.subList(0, Math.min(limit, list.size()));
      }
      rememberResult(cleanQuery, result);
      printResult(result, format);
    } catch (Exception e) {
      error("Query error: " + e.getMessage());
      printStackTraceIfInteractive(e);
      flush();
    }
  }

  /**
   * Records a query result for a later {@code explain}.
   *
   * <p>Only row-shaped results are kept: {@code explain} sends rows to the model, and a scalar or a
   * tree rendering has nothing it could serialise.
   */
  @SuppressWarnings("unchecked")
  private void rememberResult(String query, Object result) {
    if (result instanceof List<?> list
        && (list.isEmpty() || list.get(0) instanceof java.util.Map<?, ?>)) {
      this.lastResultQuery = query;
      this.lastResultRows = (List<Map<String, Object>>) list;
    }
  }

  /** Whether the line is exactly this command, or this command followed by arguments. */
  private static boolean matchesCommand(String input, String command) {
    int n = command.length();
    return input.startsWith(command) && (input.length() == n || input.charAt(n) == ' ');
  }

  /** Everything after the first word, trimmed; empty when the line is the command alone. */
  private static String argumentOf(String input) {
    int space = input.indexOf(' ');
    return space < 0 ? "" : input.substring(space + 1).trim();
  }

  /** The LLM commands, primed with the most recent result so {@code explain} has something. */
  private io.jafar.shell.cli.LlmCommands llmCommandsWithLastResult() {
    io.jafar.shell.cli.LlmCommands commands = llmCommands();
    if (lastResultQuery != null && lastResultRows != null) {
      commands.noteResult(lastResultQuery, lastResultRows);
    }
    return commands;
  }

  /**
   * Builds the LLM command handler, adapting the unified shell to {@link
   * io.jafar.shell.cli.LlmCommands.Host}.
   *
   * <p>This shell is the one that can hold sessions of every format, so it is where {@code ask}
   * reaches HdumpPath and the pprof/OTLP samples grammar as well as JfrPath — the module of the
   * current session picks the language.
   *
   * <p>Settings resolve from the variable stores (the current session's, then the global one) and
   * then from environment variables. This shell's {@code set llm.backend = ollama} writes a store;
   * {@code JAFAR_LLM_*} environment variables are the fallback.
   */
  private io.jafar.shell.cli.LlmCommands llmCommands() {
    if (llmCommands == null) {
      llmCommands =
          new io.jafar.shell.cli.LlmCommands(
              new io.jafar.shell.cli.LlmCommands.Host() {
                @Override
                public void println(String line) {
                  out.println(line);
                  flush();
                }

                @Override
                public void error(String line) {
                  Shell.this.error(line); // printed to err and counted, so a script sees it
                }

                @Override
                public Optional<String> currentModuleId() {
                  return sessions.getCurrent().map(ref -> ref.session.getType());
                }

                @Override
                public List<String> availableTypes() {
                  return sessions
                      .getCurrent()
                      .map(
                          ref -> {
                            try {
                              return ref.session.getAvailableTypes().stream().sorted().toList();
                            } catch (Exception e) {
                              return List.<String>of();
                            }
                          })
                      .orElseGet(List::of);
                }

                @Override
                @SuppressWarnings("unchecked")
                public List<Map<String, Object>> runQuery(String query) throws Exception {
                  Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
                  if (current.isEmpty()) {
                    throw new IllegalStateException("No session open");
                  }
                  SessionManager.SessionRef<Session> ref = current.get();
                  ShellModule module = moduleById.get(ref.session.getType());
                  if (module == null || module.getQueryEvaluator() == null) {
                    throw new IllegalStateException(
                        "No query evaluator for session type: " + ref.session.getType());
                  }
                  // Parse first: an evaluator's contract is to take the parsed query, and only
                  // some of them also accept the raw string.
                  QueryEvaluator evaluator = module.getQueryEvaluator();
                  Object result =
                      evaluator.evaluate(
                          ref.session, evaluator.parse(query), buildCrossSessionContext());
                  return result instanceof List<?> list
                      ? (List<Map<String, Object>>) list
                      : List.of();
                }

                @Override
                public void renderRows(List<Map<String, Object>> rows) {
                  printResult(rows);
                }

                @Override
                public void rememberResult(String query, List<Map<String, Object>> rows) {
                  Shell.this.rememberResult(query, rows);
                }

                @Override
                public Optional<String> validateQuery(String query) {
                  // Use the current module's own parser, so each format validates in its own
                  // language and the model is corrected with a message it can act on.
                  try {
                    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
                    if (current.isEmpty()) {
                      return Optional.empty();
                    }
                    ShellModule module = moduleById.get(current.get().session.getType());
                    if (module == null || module.getQueryEvaluator() == null) {
                      return Optional.empty();
                    }
                    module.getQueryEvaluator().parse(query);
                    return Optional.empty();
                  } catch (RuntimeException e) {
                    String message = e.getMessage();
                    return Optional.of(
                        message == null || message.isBlank() ? e.toString() : message);
                  }
                }

                @Override
                public String setting(String name) {
                  // Session-scoped settings win over global ones, matching how 'set' scopes them
                  // and how jfr-shell's adapter reads them.
                  VariableStore.Value value = variables.scopes().resolve(name);
                  if (value == null) {
                    return null;
                  }
                  try {
                    Object raw = value.get();
                    return raw == null ? null : String.valueOf(raw);
                  } catch (Exception e) {
                    return null;
                  }
                }
              });
    }
    return llmCommands;
  }

  private CrossSessionContext buildCrossSessionContext() {
    return new CrossSessionContext() {
      @Override
      @SuppressWarnings("unchecked")
      public Optional<SessionManager.SessionRef<? extends Session>> resolve(String idOrAlias) {
        return (Optional<SessionManager.SessionRef<? extends Session>>)
            (Optional<?>) sessions.get(idOrAlias);
      }

      @Override
      public Optional<QueryEvaluator> evaluatorFor(Session session) {
        ShellModule module = moduleById.get(session.getType());
        return module != null ? Optional.of(module.getQueryEvaluator()) : Optional.empty();
      }
    };
  }

  private void printResult(Object result) {
    printResult(result, "table");
  }

  private void printResult(Object result, String format) {
    if (result instanceof List<?> list) {
      if ("json".equalsIgnoreCase(format)) {
        out.println(toJson(result, 0));
      } else if ("csv".equalsIgnoreCase(format)) {
        printCsv(list);
      } else {
        boolean isInteractive = terminal != null && System.console() != null;
        int maxRows = Integer.MAX_VALUE;

        if (isInteractive) {
          try {
            int termHeight = terminal.getHeight();
            if (termHeight > 0) {
              maxRows = Math.max(10, termHeight - 10);
            } else {
              maxRows = 50;
            }
          } catch (Exception e) {
            maxRows = 50;
          }
        }

        String formatted = TableFormatter.formatTable(list, maxRows);
        out.print(formatted);
      }
    } else if (result instanceof Number) {
      out.println(result);
    } else {
      out.println("Result: " + result);
    }
    flush();
  }

  @SuppressWarnings("unchecked")
  private void printCsv(List<?> rows) {
    if (rows.isEmpty()) {
      out.println("(no rows)");
      return;
    }
    if (!(rows.get(0) instanceof Map)) {
      for (Object v : rows) out.println(escapeCsv(String.valueOf(v)));
      return;
    }
    var mapRows = (List<Map<String, Object>>) rows;
    var cols = new java.util.LinkedHashSet<String>();
    for (var row : mapRows) cols.addAll(row.keySet());
    var headers = new ArrayList<>(cols);
    out.println(String.join(",", headers));
    for (var row : mapRows) {
      var sb = new StringBuilder();
      for (int i = 0; i < headers.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(escapeCsv(String.valueOf(row.getOrDefault(headers.get(i), ""))));
      }
      out.println(sb);
    }
  }

  private static String escapeCsv(String s) {
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  private static String toJson(Object obj, int indent) {
    String ind = "  ".repeat(indent);
    if (obj == null) return "null";
    if (obj instanceof String s) return '"' + escapeJson(s) + '"';
    if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
    if (obj instanceof Map<?, ?> m) {
      var sb = new StringBuilder("{\n");
      boolean first = true;
      for (var e : m.entrySet()) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(ind)
            .append("  \"")
            .append(escapeJson(String.valueOf(e.getKey())))
            .append("\": ")
            .append(toJson(e.getValue(), indent + 1));
      }
      return sb.append("\n").append(ind).append("}").toString();
    }
    if (obj instanceof Iterable<?> coll) {
      var sb = new StringBuilder("[\n");
      boolean first = true;
      for (Object v : coll) {
        if (!first) sb.append(",\n");
        first = false;
        sb.append(ind).append("  ").append(toJson(v, indent + 1));
      }
      return sb.append("\n").append(ind).append("]").toString();
    }
    return '"' + escapeJson(String.valueOf(obj)) + '"';
  }

  private static String escapeJson(String s) {
    var sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.toString();
  }

  private void printBanner() {
    out.println("╔═══════════════════════════════════════╗");
    out.println("║            Jafar Shell                ║");
    out.println("║   Pluggable Performance Analysis      ║");
    out.println("╚═══════════════════════════════════════╝");
    out.println("Type 'help' for commands, 'exit' to quit");
    out.println();
    flush();
  }

  private void printHelp() {
    out.println("Jafar Shell Commands:");
    out.println();
    out.println("Session Management:");
    out.println("  open <path>        Open a file (auto-detects format)");
    out.println("  sessions           List all open sessions");
    out.println("  use <id>           Switch to a session ('session <id>' is the same)");
    out.println("  close [id]         Close session (current if no id given)");
    out.println("  info [id]          Show session information");
    out.println();
    out.println("Query:");
    out.println("  show <query>       Execute a query on current session");
    out.println();
    out.println("JFR recordings (need a JFR session):");
    out.println("  metadata [--summary] [--search <glob>] [--events-only] ...  List types");
    out.println("  metadata class <name> [--tree|--json] [--fields] [--annotations]");
    out.println("  chunks [--summary] [--range N-M]  List chunks");
    out.println("  chunk <index> show  Show one chunk's details");
    out.println("  constants|cp [<type>] [--summary] [--range N-M]  Constant pools");
    out.println("  backend [list]     Show the JFR backend, or list them");
    out.println();
    out.println("Ask (LLM, optional):");
    out.println("  ask <question>     Several queries, read each, conclude ('?' for short)");
    out.println("  as-query <question> Turn a question into one query, show it, run it");
    out.println("  explain            Explain the most recent result");
    out.println("                     (each takes --dry-run: print, send nothing)");
    out.println("  llm                status | cost");
    out.println();
    out.println("Variables and settings:");
    out.println("  set [--global] <name> = <value>");
    out.println("                     A string, number, boolean, map {\"k\": 1},");
    out.println("                     merge(${a}, {...}), another variable, a query");
    out.println("                     (lazy: run on first use), or an llm.* setting.");
    out.println("                     Session-scoped unless --global or no session.");
    out.println("  vars [--global|--session|--all] | vars --info <name>");
    out.println("  unset <name> [--global]");
    out.println("  invalidate <name>  Re-run a lazy variable's query on next use");
    out.println("  echo <text>        Print text: ${var}, ${var.size}, ${var[0].field}");
    out.println();
    out.println("Scripting:");
    out.println("  if <condition> ... elif <condition> ... else ... endif");
    out.println("                     e.g. if ${big.size} > 0 && exists(limit)");
    out.println("                     A condition that fails to evaluate is an error, not");
    out.println("                     false: no branch runs; the block stays open to endif");
    out.println("  script <path> [args...]  Run a script; $1, ${2:-default}, $@");
    out.println("  record start [path] | stop | status");
    out.println("                     Save the commands you type as a script");
    out.println();
    out.println("General:");
    out.println("  help               Show this help message");
    out.println("  modules            List available modules");
    out.println("  exit|quit          Exit shell");
    out.println();

    // Show context-aware examples based on current session
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isPresent()) {
      SessionManager.SessionRef<Session> ref = current.get();
      ShellModule module = moduleById.get(ref.session.getType());
      if (module != null) {
        List<String> examples = module.getExamples();
        if (!examples.isEmpty()) {
          out.println("Examples for " + module.getDisplayName() + ":");
          for (String example : examples) {
            out.println("  " + example);
          }
        }
      }
    } else {
      out.println("Example workflow:");
      out.println("  open myfile.jfr     # Opens a file");
      out.println("  sessions            # List open sessions");
      out.println("  show <query>        # Query current session (use Tab for completion)");
      out.println("  use 2               # Switch to session 2");
      out.println("  close               # Close current session");
    }
    out.println();
    flush();
  }

  private void printModules() {
    out.println("Available modules:");
    if (modules.isEmpty()) {
      out.println("  (none)");
    } else {
      for (ShellModule module : modules) {
        out.println("  " + module.getDisplayName() + " (" + module.getId() + ")");
        out.println("    Extensions: " + String.join(", ", module.getSupportedExtensions()));
        out.println("    Priority: " + module.getPriority());
      }
    }
    out.println();
    flush();
  }

  @Override
  public void close() throws Exception {
    stopRecordingOnExit();

    try {
      globalStore.clear();
    } catch (Exception ignore) {
    }

    try {
      sessions.closeAll();
    } catch (Exception ignore) {
    }

    if (history != null) {
      try {
        history.save();
      } catch (Exception ignore) {
      }
    }

    for (ShellModule module : modules) {
      try {
        module.shutdown();
      } catch (Exception e) {
        LOG.error("Error shutting down module: {}", module.getId(), e);
      }
    }

    if (terminal != null) {
      terminal.close();
    } else {
      flush();
    }
  }
}
