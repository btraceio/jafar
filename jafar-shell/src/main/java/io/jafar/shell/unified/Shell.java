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
import org.jline.reader.impl.history.DefaultHistory;
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

  private final Terminal terminal;
  private final LineReader lineReader;
  private final List<ShellModule> modules;
  private final Map<String, ShellModule> moduleById;
  private final SessionManager<Session> sessions;
  private final VariableStore globalStore;
  private final ShellVariables variables;
  private final Conditionals conditionals = new Conditionals();
  private boolean running = true;
  private final DefaultHistory history;
  private final ShellCompleter fallbackCompleter;
  private final Object moduleContext; // Context for module completers (e.g., CommandDispatcher)
  private final Map<String, org.jline.reader.Completer>
      completerCache; // Cache completers per module
  private io.jafar.shell.cli.LlmCommands llmCommands;

  // The most recent query result, so 'explain' has something to explain after a hand-typed query
  // and not only after 'ask'. Kept here rather than inside LlmCommands because the LLM handler is
  // built lazily — recording a result must not be what loads a backend.
  private String lastResultQuery;
  private List<Map<String, Object>> lastResultRows;

  public Shell() throws IOException {
    this.terminal = TerminalBuilder.builder().system(true).build();
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
    try {
      Files.createDirectories(histPath.getParent());
    } catch (Exception ignore) {
    }

    this.history = new DefaultHistory();
    java.util.Map<String, Object> vars = new java.util.HashMap<>();
    vars.put(org.jline.reader.LineReader.HISTORY_FILE, histPath);

    // Create a CommandDispatcher for modules that need it (like JFR shell)
    CommandDispatcher.IO ioAdapter =
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            terminal.writer().println(s);
            terminal.flush();
          }

          @Override
          public void printf(String fmt, Object... args) {
            terminal.writer().printf(fmt, args);
            terminal.flush();
          }

          @Override
          public void error(String s) {
            terminal.writer().println("Error: " + s);
            terminal.flush();
          }
        };

    // Session change listener (no-op for unified shell - prompt updates handled separately)
    CommandDispatcher.SessionChangeListener sessionChangeListener = current -> {};

    @SuppressWarnings("unchecked")
    SessionManager<JFRSession> jfrSessions =
        (SessionManager<JFRSession>) (SessionManager<?>) sessions;
    this.moduleContext = new CommandDispatcher(jfrSessions, ioAdapter, sessionChangeListener);

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
        LineReaderBuilder.builder()
            .terminal(terminal)
            .variables(vars)
            .history(history)
            .completer(dynamicCompleter)
            .parser(parser)
            .build();
  }

  public void run() {
    printBanner();

    while (running) {
      try {
        String prompt = getPrompt();
        String input = lineReader.readLine(prompt);
        if (input == null || input.isBlank()) continue;
        input = input.trim();

        if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
          if (conditionals.state().inConditional()) {
            terminal
                .writer()
                .println(
                    "Warning: " + conditionals.state().depth() + " unclosed conditional block(s)");
            terminal.flush();
            conditionals.state().reset();
          }
          running = false;
          continue;
        }

        if (!execute(input)) {
          terminal.writer().println("Unknown command: " + input);
          terminal.writer().println("Type 'help' for available commands.");
          terminal.flush();
        }

      } catch (UserInterruptException e) {
        terminal.writer().println("^C");
        terminal.flush();
      } catch (EndOfFileException e) {
        terminal.writer().println();
        terminal.writer().println("Goodbye!");
        terminal.flush();
        running = false;
      } catch (Exception e) {
        terminal.writer().println("Error: " + e.getMessage());
        e.printStackTrace();
        terminal.flush();
      }
    }
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
      if (conditionals.handle(input, variables.scopes(), this::printError)) {
        return true;
      }
    } catch (IllegalStateException e) {
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

    if (input.startsWith("open ")) {
      handleOpen(input.substring(5).trim());
      return true;
    }

    if ("sessions".equalsIgnoreCase(input)) {
      handleSessions();
      return true;
    }

    if (input.startsWith("use ")) {
      handleUse(input.substring(4).trim());
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

    if (handleVariableCommand(input)) {
      return true;
    }

    if (input.startsWith("show ")) {
      handleShow(input.substring(5).trim());
      return true;
    }

    if (input.equals("checkLeaks()")) {
      // Launch interactive wizard
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isPresent()) {
        Object session = current.get().session;
        if (session.getClass().getName().endsWith(".HeapSession")) {
          try {
            // Use reflection to call runLeakDetectionWizard
            var menuClass = Class.forName("io.jafar.shell.core.InteractiveMenu");
            var menuConstructor = menuClass.getConstructor(Terminal.class, LineReader.class);
            Object menu = menuConstructor.newInstance(terminal, lineReader);

            var wizardMethod = session.getClass().getMethod("runLeakDetectionWizard", menuClass);
            wizardMethod.invoke(session, menu);
          } catch (Exception e) {
            terminal.writer().println("Error launching wizard: " + e.getMessage());
            e.printStackTrace();
          }
        } else {
          terminal.writer().println("checkLeaks() requires an active heap dump session");
          terminal.writer().println("Use 'open <file.hprof>' to open a heap dump first");
        }
      } else {
        terminal.writer().println("checkLeaks() requires an active heap dump session");
        terminal.writer().println("Use 'open <file.hprof>' to open a heap dump first");
      }
      terminal.writer().flush();
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

  private void printError(String message) {
    terminal.writer().println("Error: " + message);
    terminal.flush();
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
          new ScriptRunner(this::execute, arguments).execute(path);
      if (result.hasErrors()) {
        terminal.writer().println();
        terminal.writer().println("Script completed with errors:");
        for (ScriptRunner.ScriptError error : result.getErrors()) {
          terminal.writer().println(error);
        }
        terminal
            .writer()
            .println(
                String.format(
                    "%nExecuted %d/%d commands successfully.",
                    result.getSuccessCount(),
                    result.getSuccessCount() + result.getErrors().size()));
      } else if (result.getSuccessCount() > 0) {
        terminal.writer().println();
        terminal
            .writer()
            .println("Script executed successfully: " + result.getSuccessCount() + " commands");
      }
    } catch (IOException e) {
      printError("Cannot read script " + path + ": " + e.getMessage());
    }
    terminal.flush();
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
      lines.forEach(terminal.writer()::println);
    } catch (IllegalArgumentException e) {
      terminal.writer().println("Error: " + e.getMessage());
    }
    terminal.flush();
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
        terminal.writer().println("Error opening file: " + e.getMessage());
        terminal.flush();
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
      terminal.writer().println("Error: file not found: " + path);
      terminal.flush();
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
      terminal.writer().println("Error: No module can handle file: " + path);
      terminal.writer().println("Supported extensions:");
      for (ShellModule module : modules) {
        terminal
            .writer()
            .println(
                "  "
                    + module.getDisplayName()
                    + ": "
                    + String.join(", ", module.getSupportedExtensions()));
      }
      terminal.flush();
      return;
    }

    terminal.writer().println("Opening " + path + " with " + handler.getDisplayName() + "...");
    terminal.flush();

    try {
      SessionManager.SessionRef<Session> ref = sessions.open(path, null);
      terminal.writer().println("Session " + ref.id + " opened: " + path.getFileName());

      // Debug: Verify session state
      if (System.getProperty("jfr.shell.completion.debug") != null) {
        terminal.writer().println("[DEBUG] Session type: " + ref.session.getType());
        terminal.writer().println("[DEBUG] Session file path: " + ref.session.getFilePath());
        terminal
            .writer()
            .println("[DEBUG] Session is current: " + sessions.getCurrent().isPresent());
      }

      terminal.flush();
    } catch (Exception e) {
      terminal.writer().println("Error opening session: " + e.getMessage());
      e.printStackTrace();
      terminal.flush();
    }
  }

  private void handleSessions() {
    List<SessionManager.SessionRef<Session>> allSessions = sessions.list();
    if (allSessions.isEmpty()) {
      terminal.writer().println("No open sessions");
    } else {
      terminal.writer().println("Open sessions:");
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      for (SessionManager.SessionRef<Session> ref : allSessions) {
        String marker = current.isPresent() && current.get().id == ref.id ? "* " : "  ";
        String alias = ref.alias != null ? " (" + ref.alias + ")" : "";
        terminal
            .writer()
            .println(
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
    terminal.flush();
  }

  private void handleUse(String idOrAlias) {
    if (sessions.use(idOrAlias)) {
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isPresent()) {
        SessionManager.SessionRef<Session> ref = current.get();
        terminal
            .writer()
            .println(
                "Switched to session " + ref.id + ": " + ref.session.getFilePath().getFileName());
      }
    } else {
      terminal.writer().println("No such session: " + idOrAlias);
    }
    terminal.flush();
  }

  private void handleClose(String input) {
    String[] parts = input.split("\\s+");
    if (parts.length == 1) {
      // Close current session
      Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
      if (current.isEmpty()) {
        terminal.writer().println("No session open");
      } else {
        try {
          sessions.close(String.valueOf(current.get().id));
          terminal.writer().println("Session closed");
        } catch (Exception e) {
          terminal.writer().println("Error closing session: " + e.getMessage());
        }
      }
    } else {
      String idOrAlias = parts[1];
      try {
        if (sessions.close(idOrAlias)) {
          terminal.writer().println("Session closed: " + idOrAlias);
        } else {
          terminal.writer().println("No such session: " + idOrAlias);
        }
      } catch (Exception e) {
        terminal.writer().println("Error closing session: " + e.getMessage());
      }
    }
    terminal.flush();
  }

  private void handleInfo(String input) {
    String[] parts = input.split("\\s+");
    Optional<SessionManager.SessionRef<Session>> ref;

    if (parts.length == 1) {
      ref = sessions.getCurrent();
      if (ref.isEmpty()) {
        terminal.writer().println("No session open");
        terminal.flush();
        return;
      }
    } else {
      ref = sessions.get(parts[1]);
      if (ref.isEmpty()) {
        terminal.writer().println("No such session: " + parts[1]);
        terminal.flush();
        return;
      }
    }

    SessionManager.SessionRef<Session> session = ref.get();
    terminal.writer().println("Session " + session.id + ":");
    terminal.writer().println("  Type: " + session.session.getType());
    terminal.writer().println("  File: " + session.session.getFilePath());
    terminal.writer().println("  Closed: " + session.session.isClosed());

    Map<String, Object> stats = session.session.getStatistics();
    if (!stats.isEmpty()) {
      terminal.writer().println("  Statistics:");
      for (Map.Entry<String, Object> entry : stats.entrySet()) {
        terminal.writer().println("    " + entry.getKey() + ": " + entry.getValue());
      }
    }
    terminal.flush();
  }

  private void handleShow(String query) {
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isEmpty()) {
      terminal.writer().println("No session open. Use 'open <file>' first.");
      terminal.flush();
      return;
    }

    SessionManager.SessionRef<Session> ref = current.get();
    ShellModule module = moduleById.get(ref.session.getType());
    if (module == null) {
      terminal
          .writer()
          .println("Error: Module not found for session type: " + ref.session.getType());
      terminal.flush();
      return;
    }

    QueryEvaluator evaluator = module.getQueryEvaluator();
    if (evaluator == null) {
      terminal.writer().println("Error: Module does not support queries: " + module.getId());
      terminal.flush();
      return;
    }

    // 'show ${var}' alone prints a query-valued variable's rows (cached after first use), rather
    // than substituting its first value into the line
    java.util.regex.Matcher whole =
        java.util.regex.Pattern.compile(
                "^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}((?:\\s+--\\S+(?:\\s+\\S+)?)*)$")
            .matcher(query.trim());
    if (whole.matches()
        && variables.scopes().resolve(whole.group(1)) instanceof VariableStore.LazyValue lazy) {
      try {
        Object rows = lazy.get();
        java.util.regex.Matcher fmt =
            java.util.regex.Pattern.compile("--format\\s+(\\S+)").matcher(whole.group(2));
        java.util.regex.Matcher lim =
            java.util.regex.Pattern.compile("--limit\\s+(\\d+)").matcher(whole.group(2));
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

    java.util.regex.Matcher limitMatcher =
        java.util.regex.Pattern.compile("--limit\\s+(\\d+)").matcher(cleanQuery);
    if (limitMatcher.find()) {
      limit = Integer.parseInt(limitMatcher.group(1));
      cleanQuery = limitMatcher.replaceFirst("").trim();
    }

    java.util.regex.Matcher formatMatcher =
        java.util.regex.Pattern.compile("--format\\s+(\\S+)").matcher(cleanQuery);
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
      terminal.writer().println("Query error: " + e.getMessage());
      e.printStackTrace();
      terminal.flush();
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
    return input.equals(command) || input.startsWith(command + " ");
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
                  terminal.writer().println(line);
                  terminal.flush();
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
        terminal.writer().println(toJson(result, 0));
      } else if ("csv".equalsIgnoreCase(format)) {
        printCsv(list);
      } else {
        boolean isInteractive = System.console() != null;
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
        terminal.writer().print(formatted);
      }
    } else if (result instanceof Number) {
      terminal.writer().println(result);
    } else {
      terminal.writer().println("Result: " + result);
    }
    terminal.flush();
  }

  @SuppressWarnings("unchecked")
  private void printCsv(List<?> rows) {
    if (rows.isEmpty()) {
      terminal.writer().println("(no rows)");
      return;
    }
    if (!(rows.get(0) instanceof Map)) {
      for (Object v : rows) terminal.writer().println(escapeCsv(String.valueOf(v)));
      return;
    }
    var mapRows = (List<Map<String, Object>>) rows;
    var cols = new java.util.LinkedHashSet<String>();
    for (var row : mapRows) cols.addAll(row.keySet());
    var headers = new ArrayList<>(cols);
    terminal.writer().println(String.join(",", headers));
    for (var row : mapRows) {
      var sb = new StringBuilder();
      for (int i = 0; i < headers.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(escapeCsv(String.valueOf(row.getOrDefault(headers.get(i), ""))));
      }
      terminal.writer().println(sb);
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
    terminal.writer().println("╔═══════════════════════════════════════╗");
    terminal.writer().println("║            Jafar Shell                ║");
    terminal.writer().println("║   Pluggable Performance Analysis      ║");
    terminal.writer().println("╚═══════════════════════════════════════╝");
    terminal.writer().println("Type 'help' for commands, 'exit' to quit");
    terminal.writer().println();
    terminal.flush();
  }

  private void printHelp() {
    terminal.writer().println("Jafar Shell Commands:");
    terminal.writer().println();
    terminal.writer().println("Session Management:");
    terminal.writer().println("  open <path>        Open a file (auto-detects format)");
    terminal.writer().println("  sessions           List all open sessions");
    terminal.writer().println("  use <id>           Switch to a session");
    terminal.writer().println("  close [id]         Close session (current if no id given)");
    terminal.writer().println("  info [id]          Show session information");
    terminal.writer().println();
    terminal.writer().println("Query:");
    terminal.writer().println("  show <query>       Execute a query on current session");
    terminal.writer().println();
    terminal.writer().println("Ask (LLM, optional):");
    terminal
        .writer()
        .println("  ask <question>     Several queries, read each, conclude ('?' for short)");
    terminal
        .writer()
        .println("  as-query <question> Turn a question into one query, show it, run it");
    terminal.writer().println("  explain            Explain the most recent result");
    terminal.writer().println("                     (each takes --dry-run: print, send nothing)");
    terminal.writer().println("  llm                status | cost");
    terminal.writer().println();
    terminal.writer().println("Variables and settings:");
    terminal.writer().println("  set [--global] <name> = <value>");
    terminal.writer().println("                     A string, number, boolean, map {\"k\": 1},");
    terminal.writer().println("                     merge(${a}, {...}), another variable, a query");
    terminal
        .writer()
        .println("                     (lazy: run on first use), or an llm.* setting.");
    terminal.writer().println("                     Session-scoped unless --global or no session.");
    terminal.writer().println("  vars [--global|--session|--all] | vars --info <name>");
    terminal.writer().println("  unset <name> [--global]");
    terminal.writer().println("  invalidate <name>  Re-run a lazy variable's query on next use");
    terminal
        .writer()
        .println("  echo <text>        Print text: ${var}, ${var.size}, ${var[0].field}");
    terminal.writer().println();
    terminal.writer().println("Scripting:");
    terminal.writer().println("  if <condition> ... elif <condition> ... else ... endif");
    terminal.writer().println("                     e.g. if ${big.size} > 0 && exists(limit)");
    terminal.writer().println("  script <path> [args...]  Run a script; $1, ${2:-default}, $@");
    terminal.writer().println();
    terminal.writer().println("General:");
    terminal.writer().println("  help               Show this help message");
    terminal.writer().println("  modules            List available modules");
    terminal.writer().println("  exit|quit          Exit shell");
    terminal.writer().println();

    // Show context-aware examples based on current session
    Optional<SessionManager.SessionRef<Session>> current = sessions.getCurrent();
    if (current.isPresent()) {
      SessionManager.SessionRef<Session> ref = current.get();
      ShellModule module = moduleById.get(ref.session.getType());
      if (module != null) {
        List<String> examples = module.getExamples();
        if (!examples.isEmpty()) {
          terminal.writer().println("Examples for " + module.getDisplayName() + ":");
          for (String example : examples) {
            terminal.writer().println("  " + example);
          }
        }
      }
    } else {
      terminal.writer().println("Example workflow:");
      terminal.writer().println("  open myfile.jfr     # Opens a file");
      terminal.writer().println("  sessions            # List open sessions");
      terminal
          .writer()
          .println("  show <query>        # Query current session (use Tab for completion)");
      terminal.writer().println("  use 2               # Switch to session 2");
      terminal.writer().println("  close               # Close current session");
    }
    terminal.writer().println();
    terminal.flush();
  }

  private void printModules() {
    terminal.writer().println("Available modules:");
    if (modules.isEmpty()) {
      terminal.writer().println("  (none)");
    } else {
      for (ShellModule module : modules) {
        terminal.writer().println("  " + module.getDisplayName() + " (" + module.getId() + ")");
        terminal
            .writer()
            .println("    Extensions: " + String.join(", ", module.getSupportedExtensions()));
        terminal.writer().println("    Priority: " + module.getPriority());
      }
    }
    terminal.writer().println();
    terminal.flush();
  }

  @Override
  public void close() throws Exception {
    try {
      globalStore.clear();
    } catch (Exception ignore) {
    }

    try {
      sessions.closeAll();
    } catch (Exception ignore) {
    }

    try {
      history.save();
    } catch (Exception ignore) {
    }

    for (ShellModule module : modules) {
      try {
        module.shutdown();
      } catch (Exception e) {
        LOG.error("Error shutting down module: {}", module.getId(), e);
      }
    }

    terminal.close();
  }
}
