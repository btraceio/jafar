package io.jafar.shell;

import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.VariableStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
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
  private final SessionManager sessions;
  private final VariableStore globalStore;
  private boolean running = true;
  private final DefaultHistory history;
  private final ShellCompleter fallbackCompleter;
  private final Object moduleContext; // Context for module completers (e.g., CommandDispatcher)
  private final Map<String, org.jline.reader.Completer> completerCache; // Cache completers per module

  public Shell() throws IOException {
    this.terminal = TerminalBuilder.builder().system(true).build();
    this.modules = loadModules();
    this.moduleById = new HashMap<>();
    for (ShellModule module : modules) {
      moduleById.put(module.getId(), module);
    }

    // Create session manager with factory that routes to appropriate module
    this.sessions = new SessionManager(this::createSessionForFile, null);
    this.globalStore = new VariableStore();

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

    this.moduleContext = new CommandDispatcher(sessions, ioAdapter, sessionChangeListener);

    // Create fallback completer for basic commands
    this.fallbackCompleter = new ShellCompleter(sessions, moduleById);

    // Cache for module-specific completers (create once per module, not on every keystroke)
    this.completerCache = new HashMap<>();

    // Create dynamic completer that delegates to module-specific completers
    org.jline.reader.Completer dynamicCompleter =
        (reader, line, candidates) -> {
          Optional<SessionManager.SessionRef> current = sessions.getCurrent();
          if (current.isPresent()) {
            SessionManager.SessionRef ref = current.get();
            String moduleType = ref.session.getType();
            ShellModule module = moduleById.get(moduleType);
            if (module != null) {
              // Get or create cached completer for this module
              org.jline.reader.Completer moduleCompleter =
                  completerCache.computeIfAbsent(
                      moduleType, type -> module.getCompleter(sessions, moduleContext));
              if (moduleCompleter != null) {
                moduleCompleter.complete(reader, line, candidates);
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

  private Session createSessionForFile(Path path, Object context) throws Exception {
    // Find a module that can handle this file
    for (ShellModule module : modules) {
      if (module.canHandle(path)) {
        return module.createSession(path, context);
      }
    }
    throw new IllegalArgumentException("No module can handle file: " + path);
  }

  private List<ShellModule> loadModules() {
    List<ShellModule> loaded = new ArrayList<>();
    ServiceLoader<ShellModule> loader = ServiceLoader.load(ShellModule.class);
    for (ShellModule module : loader) {
      try {
        module.initialize();
        loaded.add(module);
        LOG.info("Loaded module: {} ({})", module.getDisplayName(), module.getId());
      } catch (Exception e) {
        LOG.error("Failed to initialize module: {}", module.getId(), e);
      }
    }

    // Sort by priority (highest first)
    loaded.sort(Comparator.comparingInt(ShellModule::getPriority).reversed());

    return loaded;
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
          running = false;
          continue;
        }

        if ("help".equalsIgnoreCase(input)) {
          printHelp();
          continue;
        }

        if ("modules".equalsIgnoreCase(input)) {
          printModules();
          continue;
        }

        if (input.startsWith("open ")) {
          handleOpen(input.substring(5).trim());
          continue;
        }

        if ("sessions".equalsIgnoreCase(input)) {
          handleSessions();
          continue;
        }

        if (input.startsWith("use ")) {
          handleUse(input.substring(4).trim());
          continue;
        }

        if (input.startsWith("close")) {
          handleClose(input);
          continue;
        }

        if (input.startsWith("info")) {
          handleInfo(input);
          continue;
        }

        if (input.startsWith("show ")) {
          handleShow(input.substring(5).trim());
          continue;
        }

        terminal.writer().println("Unknown command: " + input);
        terminal.writer().println("Type 'help' for available commands.");
        terminal.flush();

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

  private String getPrompt() {
    Optional<SessionManager.SessionRef> current = sessions.getCurrent();
    if (current.isPresent()) {
      SessionManager.SessionRef ref = current.get();
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
      SessionManager.SessionRef ref = sessions.open(path, null);
      terminal.writer().println("Session " + ref.id + " opened: " + path.getFileName());

      // Debug: Verify session state
      if (System.getProperty("jfr.shell.completion.debug") != null) {
        terminal.writer().println("[DEBUG] Session type: " + ref.session.getType());
        terminal.writer().println("[DEBUG] Session file path: " + ref.session.getFilePath());
        terminal.writer().println("[DEBUG] Session is current: " + sessions.getCurrent().isPresent());
      }

      terminal.flush();
    } catch (Exception e) {
      terminal.writer().println("Error opening session: " + e.getMessage());
      e.printStackTrace();
      terminal.flush();
    }
  }

  private void handleSessions() {
    List<SessionManager.SessionRef> allSessions = sessions.list();
    if (allSessions.isEmpty()) {
      terminal.writer().println("No open sessions");
    } else {
      terminal.writer().println("Open sessions:");
      Optional<SessionManager.SessionRef> current = sessions.getCurrent();
      for (SessionManager.SessionRef ref : allSessions) {
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
      Optional<SessionManager.SessionRef> current = sessions.getCurrent();
      if (current.isPresent()) {
        SessionManager.SessionRef ref = current.get();
        terminal.writer().println("Switched to session " + ref.id + ": " + ref.session.getFilePath().getFileName());
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
      Optional<SessionManager.SessionRef> current = sessions.getCurrent();
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
    Optional<SessionManager.SessionRef> ref;

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

    SessionManager.SessionRef session = ref.get();
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
    Optional<SessionManager.SessionRef> current = sessions.getCurrent();
    if (current.isEmpty()) {
      terminal.writer().println("No session open. Use 'open <file>' first.");
      terminal.flush();
      return;
    }

    SessionManager.SessionRef ref = current.get();
    ShellModule module = moduleById.get(ref.session.getType());
    if (module == null) {
      terminal.writer().println("Error: Module not found for session type: " + ref.session.getType());
      terminal.flush();
      return;
    }

    QueryEvaluator evaluator = module.getQueryEvaluator();
    if (evaluator == null) {
      terminal.writer().println("Error: Module does not support queries: " + module.getId());
      terminal.flush();
      return;
    }

    try {
      Object result = evaluator.evaluate(ref.session, query);
      printResult(result);
    } catch (Exception e) {
      terminal.writer().println("Query error: " + e.getMessage());
      e.printStackTrace();
      terminal.flush();
    }
  }

  private void printResult(Object result) {
    if (result instanceof List<?> list) {
      boolean isInteractive = System.console() != null;
      int maxRows = Integer.MAX_VALUE;

      if (isInteractive) {
        // Detect terminal height and reserve lines for header + summary message
        try {
          int termHeight = terminal.getHeight();
          if (termHeight > 0) {
            // Reserve 10 lines: header line + summary message + prompt + margin
            maxRows = Math.max(10, termHeight - 10);
          } else {
            maxRows = 50; // Fallback if height detection fails
          }
        } catch (Exception e) {
          maxRows = 50; // Fallback on error
        }
      }

      String formatted = TableFormatter.formatTable(list, maxRows);
      terminal.writer().print(formatted);
    } else if (result instanceof Number) {
      terminal.writer().println(result);
    } else {
      terminal.writer().println("Result: " + result);
    }
    terminal.flush();
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
    terminal.writer().println("General:");
    terminal.writer().println("  help               Show this help message");
    terminal.writer().println("  modules            List available modules");
    terminal.writer().println("  exit|quit          Exit shell");
    terminal.writer().println();

    // Show context-aware examples based on current session
    Optional<SessionManager.SessionRef> current = sessions.getCurrent();
    if (current.isPresent()) {
      SessionManager.SessionRef ref = current.get();
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
      terminal.writer().println("  show <query>        # Query current session (use Tab for completion)");
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
            .println(
                "    Extensions: " + String.join(", ", module.getSupportedExtensions()));
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
