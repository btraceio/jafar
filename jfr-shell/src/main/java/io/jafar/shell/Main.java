package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.scripting.ScriptRunner;
import io.jafar.shell.live.TailRunner;
import io.jafar.shell.plugin.PluginManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine;

@CommandLine.Command(
    name = "jfr-shell",
    description = "JFR analysis tool with interactive and non-interactive modes",
    versionProvider = Main.VersionProvider.class,
    mixinStandardHelpOptions = true,
    subcommands = {
      Main.ShowCommand.class,
      Main.MetadataCommand.class,
      Main.ChunksCommand.class,
      Main.CpCommand.class,
      Main.ScriptCommand.class,
      Main.PsCommand.class,
      Main.TailCommand.class
    })
public final class Main implements Callable<Integer> {
  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "JFR file to open immediately (interactive mode)")
  private String jfrFile;

  @CommandLine.Parameters(
      index = "0",
      arity = "0..1",
      description = "JFR file to open immediately (interactive mode)")
  private String jfrFilePositional;

  @CommandLine.Option(
      names = {"-q", "--quiet"},
      description = "Suppress banner (interactive mode)")
  private boolean quiet;

  @CommandLine.Option(
      names = {"-b", "--backend"},
      description = "JFR backend to use (default: auto-select by priority)")
  private String backend;

  @CommandLine.Option(
      names = {"--plugin-dir"},
      description = "Custom plugin directory (default: ~/.jfr-shell/plugins)")
  private String pluginDir;

  @CommandLine.Option(
      names = {"--tui"},
      description = "Launch full-screen TUI mode instead of readline REPL")
  private boolean tui;

  @CommandLine.Option(
      names = {"--install-plugin"},
      description = "Install a backend plugin from a local JAR file and exit")
  private String installPlugin;

  public static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {Version.VALUE};
    }
  }

  public static void main(String[] args) {
    // Parse CLI args to extract plugin-dir and install-plugin before initialization
    Main main = new Main();
    new CommandLine(main).parseArgs(args);

    // Set plugin directory if specified
    if (main.pluginDir != null) {
      System.setProperty("jfr.shell.plugin.dir", main.pluginDir);
    }

    // Handle --install-plugin before normal initialization
    if (main.installPlugin != null) {
      Path jarPath = Paths.get(main.installPlugin);
      try {
        PluginManager.getInstance().installLocalPlugin(jarPath);
        System.out.println("Plugin installed successfully from: " + jarPath);
        System.out.println("Restart jfr-shell to use the new plugin.");
        System.exit(0);
      } catch (Exception e) {
        System.err.println("Failed to install plugin: " + e.getMessage());
        System.exit(1);
      }
    }

    // Initialize plugin system before BackendRegistry is accessed
    PluginManager.initialize();

    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    // Set backend selection via system property before registry initializes
    if (backend != null) {
      System.setProperty("jfr.shell.backend", backend);
    }

    // Validate backend is available
    if (!validateBackend()) {
      return 1;
    }

    // Support both positional argument and -f/--file option; -f takes precedence
    String effectiveJfrFile = jfrFile != null ? jfrFile : jfrFilePositional;
    Path jfrPath = null;
    if (effectiveJfrFile != null) {
      jfrPath = Paths.get(effectiveJfrFile);
      if (!Files.exists(jfrPath)) {
        System.err.println("Error: JFR file not found: " + effectiveJfrFile);
        return 1;
      }
    }

    if (tui) {
      try (TuiShell tuiShell = new TuiShell(jfrPath)) {
        tuiShell.openIfPresent(jfrPath);
        tuiShell.run();
        return 0;
      }
    }

    // Default: run interactive readline mode
    try (Shell shell = new Shell()) {
      shell.openIfPresent(jfrPath);
      shell.run();
      return 0;
    }
  }

  private boolean validateBackend() {
    try {
      BackendRegistry registry = BackendRegistry.getInstance();

      // Auto-install default backend if none available
      if (registry.listAll().isEmpty() && !autoInstallDefaultBackend()) {
        System.err.println("Error: No JFR backends available");
        return false;
      }

      JfrBackend current = registry.getCurrent();
      warnIfBackendNotFromThisBuild(current);

      // If user specified a backend, verify it was selected
      if (backend != null && !current.getId().equalsIgnoreCase(backend)) {
        // Check if plugin can be auto-installed
        if (PluginManager.getInstance().canInstall(backend)) {
          System.err.println("Backend not found: " + backend);
          System.err.print("Downloading from Maven repositories... ");

          try {
            PluginManager.getInstance().installPlugin(backend);
            System.err.println("done.");
            System.err.println("Restart jfr-shell to use backend: " + backend);
            return false;
          } catch (Exception e) {
            System.err.println("failed: " + e.getMessage());
            return false;
          }
        }

        System.err.println("Error: Backend not found: " + backend);
        System.err.println(
            "Available backends: "
                + registry.listAll().stream()
                    .map(JfrBackend::getId)
                    .collect(Collectors.joining(", ")));
        return false;
      }
      return true;
    } catch (IllegalStateException e) {
      System.err.println("Error: " + e.getMessage());
      return false;
    }
  }

  /** Backends built in this repository, versioned with the shell. */
  private static final Set<String> IN_TREE_BACKENDS = Set.of("jafar", "jdk");

  /**
   * Prints {@link #backendBuildMismatch} to stderr when it applies.
   *
   * @param backend the backend the shell is about to use
   */
  static void warnIfBackendNotFromThisBuild(JfrBackend backend) {
    String warning = backendBuildMismatch(Version.VALUE, backend);
    if (warning != null) {
      System.err.println(warning);
    }
  }

  /**
   * A development build of the shell ({@code -SNAPSHOT}) does not contain its backends: unless told
   * otherwise it runs whatever {@code jfr-shell-jafar} is installed under {@code
   * ~/.jfr-shell/plugins}, which it fetches from Maven Central on its first start. Typing a change
   * to {@code jfr-shell-jafar} into such a shell then exercises the released backend, silently
   * (doc/agents/Verification.md, R1). This names that case. A backend reporting {@code dev} has no
   * jar manifest, so it was loaded from this tree's classes (Gradle tests, an IDE) and is not
   * flagged; neither is a release build of the shell, nor a backend from outside this repository.
   *
   * @param shellVersion this shell's version
   * @param backend the selected backend
   * @return the warning, or {@code null} when the backend is the one built with this shell
   */
  static String backendBuildMismatch(String shellVersion, JfrBackend backend) {
    if (shellVersion == null || !shellVersion.endsWith("-SNAPSHOT") || backend == null) {
      return null;
    }
    String id = backend.getId();
    String version = backend.getVersion();
    if (!IN_TREE_BACKENDS.contains(id)
        || version == null
        || "dev".equals(version)
        || version.equals(shellVersion)) {
      return null;
    }
    String module = "jfr-shell-" + id;
    return "Warning: this jfr-shell is a development build ("
        + shellVersion
        + ") but its '"
        + id
        + "' backend is version "
        + version
        + ", not the one built with it. To run the built backend, pass -Djfr.shell.backend.jars="
        + module
        + "/build/libs/"
        + module
        + "-"
        + shellVersion
        + ".jar (doc/agents/Verification.md, R1).";
  }

  /**
   * Attempt to auto-install the default "jafar" backend from Maven repositories. After successful
   * installation, reinitializes the plugin system and re-discovers backends so the tool can
   * continue without a restart.
   *
   * @return true if a backend was installed and is now available
   */
  static boolean autoInstallDefaultBackend() {
    String pluginId = "jafar";
    PluginManager pm = PluginManager.getInstance();
    if (!pm.canInstall(pluginId)) {
      return false;
    }
    System.err.print("No backends found. Installing default backend from Maven... ");
    try {
      pm.installPlugin(pluginId);
      PluginManager.reinitialize();
      BackendRegistry.getInstance().rediscover();
      System.err.println("done.");
      return !BackendRegistry.getInstance().listAll().isEmpty();
    } catch (Exception e) {
      System.err.println("failed: " + e.getMessage());
      return false;
    }
  }

  /**
   * Shared backend validation for non-interactive commands: selects the backend via system
   * property, auto-installs a default when none is present, and honors {@code --backend} (incl. the
   * auto-install-and-restart path). Returns 0 on success, or the process exit code.
   */
  static Integer validateBackend(String backend) {
    if (backend != null) {
      System.setProperty("jfr.shell.backend", backend);
    }
    try {
      BackendRegistry registry = BackendRegistry.getInstance();

      if (registry.listAll().isEmpty() && !autoInstallDefaultBackend()) {
        System.err.println("Error: No JFR backends available");
        return 1;
      }

      JfrBackend current = registry.getCurrent();
      warnIfBackendNotFromThisBuild(current);
      if (backend != null && !current.getId().equalsIgnoreCase(backend)) {
        if (PluginManager.getInstance().canInstall(backend)) {
          System.err.println("Backend not found: " + backend);
          System.err.print("Downloading from Maven repositories... ");
          try {
            PluginManager.getInstance().installPlugin(backend);
            System.err.println("done.");
            System.err.println("Restart jfr-shell to use backend: " + backend);
            return 1;
          } catch (Exception e) {
            System.err.println("failed: " + e.getMessage());
            return 1;
          }
        }
        System.err.println("Error: Backend not found: " + backend);
        System.err.println(
            "Available backends: "
                + registry.listAll().stream()
                    .map(JfrBackend::getId)
                    .collect(Collectors.joining(", ")));
        return 1;
      }
    } catch (IllegalStateException e) {
      System.err.println("Error: " + e.getMessage());
      return 1;
    }
    return 0;
  }

  // Base class for non-interactive commands
  abstract static class NonInteractiveCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Path to JFR recording file")
    protected String jfrFile;

    @CommandLine.Option(
        names = {"-b", "--backend"},
        description = "JFR backend to use")
    protected String backend;

    protected Integer executeNonInteractive(String command) {
      Integer rc = validateBackend(backend);
      if (rc != 0) {
        return rc;
      }
      Path path = Paths.get(jfrFile);
      if (!Files.exists(path)) {
        System.err.println("Error: JFR file not found: " + jfrFile);
        return 1;
      }

      try {
        ParsingContext ctx = ParsingContext.create();
        SessionManager<Session> sessions =
            new SessionManager<>((p, c) -> new JFRSession(p, (ParsingContext) c), ctx);
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        CommandDispatcher dispatcher =
            new CommandDispatcher(
                sessions,
                new CommandDispatcher.IO() {
                  @Override
                  public void println(String s) {
                    output.append(s).append("\n");
                  }

                  @Override
                  public void printf(String fmt, Object... args) {
                    output.append(String.format(fmt, args));
                  }

                  @Override
                  public void error(String s) {
                    errors.append(s).append("\n");
                  }
                },
                current -> {});

        // Open the file
        dispatcher.dispatch("open " + path.toString());

        // Execute command
        boolean handled = dispatcher.dispatch(command);

        // Print output
        if (output.length() > 0) {
          System.out.print(output.toString());
        }

        if (errors.length() > 0) {
          System.err.print(errors.toString());
          return 1;
        }

        return handled ? 0 : 1;
      } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
        return 1;
      }
    }
  }

  @CommandLine.Command(name = "show", description = "Execute a JfrPath query and display results")
  static class ShowCommand extends NonInteractiveCommand {
    @CommandLine.Parameters(
        index = "1",
        description = "JfrPath expression (e.g., \"events/jdk.FileRead | count()\")")
    private String expression;

    @CommandLine.Option(
        names = {"--limit", "-l"},
        description = "Limit number of results")
    private Integer limit;

    @CommandLine.Option(
        names = {"--format", "-f"},
        description = "Output format: table (default), json")
    private String format = "table";

    @CommandLine.Option(
        names = {"--list-match"},
        description = "List matching mode: any, all, none")
    private String listMatch;

    @Override
    public Integer call() {
      StringBuilder cmd = new StringBuilder("show ");
      cmd.append(expression);

      if (limit != null) {
        cmd.append(" --limit ").append(limit);
      }
      if (!"table".equals(format)) {
        cmd.append(" --format ").append(format);
      }
      if (listMatch != null) {
        cmd.append(" --list-match ").append(listMatch);
      }

      return executeNonInteractive(cmd.toString());
    }
  }

  @CommandLine.Command(name = "metadata", description = "List event types and metadata")
  static class MetadataCommand extends NonInteractiveCommand {
    @CommandLine.Option(
        names = {"--search", "-s"},
        description = "Search pattern")
    private String search;

    @CommandLine.Option(
        names = {"--regex", "-r"},
        description = "Use regex for search")
    private boolean regex;

    @CommandLine.Option(
        names = {"--events-only", "-e"},
        description = "Show only event types")
    private boolean eventsOnly;

    @CommandLine.Option(
        names = {"--summary"},
        description = "Show summary only")
    private boolean summary;

    @Override
    public Integer call() {
      StringBuilder cmd = new StringBuilder("types");

      if (search != null) {
        cmd.append(" ").append(search);
        if (regex) {
          cmd.append(" --regex");
        }
      }
      if (eventsOnly) {
        cmd.append(" --events-only");
      }
      if (summary) {
        cmd.append(" --summary");
      }

      return executeNonInteractive(cmd.toString());
    }
  }

  @CommandLine.Command(name = "chunks", description = "List chunk information")
  static class ChunksCommand extends NonInteractiveCommand {
    @CommandLine.Option(
        names = {"--summary"},
        description = "Show summary only")
    private boolean summary;

    @CommandLine.Option(
        names = {"--format", "-f"},
        description = "Output format: table (default), json")
    private String format = "table";

    @Override
    public Integer call() {
      StringBuilder cmd = new StringBuilder("chunks");

      if (summary) {
        cmd.append(" --summary");
      }
      if (!"table".equals(format)) {
        cmd.append(" --format ").append(format);
      }

      return executeNonInteractive(cmd.toString());
    }
  }

  @CommandLine.Command(
      name = "constants",
      aliases = {"cp"},
      description = "List constant pool entries")
  static class CpCommand extends NonInteractiveCommand {
    @CommandLine.Option(
        names = {"--type", "-t"},
        description = "Constant pool type name")
    private String type;

    @CommandLine.Option(
        names = {"--summary"},
        description = "Show summary only")
    private boolean summary;

    @CommandLine.Option(
        names = {"--format", "-f"},
        description = "Output format: table (default), json")
    private String format = "table";

    @Override
    public Integer call() {
      StringBuilder cmd = new StringBuilder("constants");

      if (type != null) {
        cmd.append(" ").append(type);
      }
      if (summary) {
        cmd.append(" --summary");
      }
      if (!"table".equals(format)) {
        cmd.append(" --format ").append(format);
      }

      return executeNonInteractive(cmd.toString());
    }
  }

  /** {@code ps} — list local JVMs with discoverable JFR event repositories (issue #92). */
  @CommandLine.Command(
      name = "ps",
      mixinStandardHelpOptions = true,
      description = "List local JVMs ([jfr] marks a discoverable disk-backed JFR event repository)")
  static class PsCommand implements Callable<Integer> {
    @Override
    public Integer call() {
      var processes = io.jafar.shell.live.DefaultLiveStreams.INSTANCE.listLocalJvm();
      if (processes.isEmpty()) {
        System.out.println(
            "No local JVMs found (perf-data tree under "
                + io.jafar.shell.live.DefaultLiveStreams.INSTANCE.searchedBase()
                + ")");
        return 0;
      }
      System.out.println("Local JVMs (use 'jfr-shell tail <pid>' to stream events):");
      for (var p : processes) {
        System.out.println(io.jafar.shell.live.JvmProcesses.renderRow(p));
      }
      System.out.println(io.jafar.shell.live.JvmProcesses.JFR_LEGEND);
      return 0;
    }
  }

  /**
   * {@code tail <pid> [filter]} — one-shot live event streaming. Deliberately bypasses {@code
   * executeNonInteractive}: its StringBuilder output buffering would hold the whole stream in
   * memory and print it only at the end. Events print+flush as they arrive; Ctrl-C exits 0 (the
   * TailRunner's SIGINT handler interrupts the loop); the target JVM exiting ends the stream
   * cleanly.
   */
  @CommandLine.Command(
      name = "tail",
      mixinStandardHelpOptions = true,
      description = "Stream live JFR events from a local JVM until Ctrl-C")
  static class TailCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Target JVM PID")
    private int pid;

    @CommandLine.Parameters(
        index = "1",
        arity = "0..1",
        description =
            "JfrPath filter (events root, per-event operators only), e.g. "
                + "\"events/jdk.ExecutionSample | filter(costlyStacks == true)\"")
    private String filterExpr;

    @CommandLine.Option(
        names = {"--repository"},
        description = "JFR event repository directory (bypasses discovery)")
    private String repository;

    @CommandLine.Option(
        names = {"--limit", "-l"},
        description = "Stop after N events (default: stream until Ctrl-C)")
    private Long limit;

    @CommandLine.Option(
        names = {"--format", "-f"},
        description = "Output format: table (default), json")
    private String format = "table";

    // Package-private output routing override; null => the process streams (System.out/err).
    // Integration tests (LiveTailIT) inject captured PrintStreams here so they can read tail
    // output without swapping the process-global streams from worker threads.
    transient PrintStream outOverride;
    transient PrintStream errOverride;

    @Override
    public Integer call() {
      final PrintStream out = outOverride != null ? outOverride : System.out;
      final PrintStream err = errOverride != null ? errOverride : System.err;
      // Validate filter/format FIRST (cheap, no I/O) so a bad expression never reaches the
      // hardening check or opens any stream.
      io.jafar.shell.jfrpath.JfrPath.Query filter = null;
      TailRunner.Format fmt;
      try {
        if (filterExpr != null && !filterExpr.isBlank()) {
          filter = TailRunner.parseFilter(filterExpr);
        }
        fmt = TailRunner.parseFormat(format);
      } catch (IllegalArgumentException e) {
        err.println("Error: " + e.getMessage());
        return 1;
      }

      Path repo;
      try {
        if (repository != null) {
          repo = Path.of(repository);
          if (!java.nio.file.Files.isDirectory(repo)) {
            err.println("Error: repository directory not found: " + repo);
            return 1;
          }
        } else {
          // Discovery failure throws the rich IOException (searched temp base + --repository
          // suggestion); it propagates to the catch below verbatim.
          repo = io.jafar.shell.live.DefaultLiveStreams.INSTANCE.findRepository(pid);
        }
      } catch (Exception e) {
        err.println("Error: " + e.getMessage());
        return 1;
      }

      // attach applies the full planted-directory hardening (explicit --repository paths
      // included) and returns a STARTED session. LiveJfrSession implements AutoCloseable
      // (Session); close() is idempotent and never throws. TWRS closes the stream and its
      // dispatch thread on the error path too; the shutdown hook stays for hard process exits
      // (double-close is a no-op). The Ctrl-C path is TailRunner's signal handler interrupting
      // the loop, which returns normally (exit 0).
      try (io.jafar.shell.live.LiveStreamView session =
          io.jafar.shell.live.DefaultLiveStreams.INSTANCE.attach(pid, repo)) {
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      try {
                        session.close();
                      } catch (Exception ignored) {
                        // close() is idempotent and never throws in practice
                      }
                    }));
        TailRunner.run(
            session,
            filter,
            fmt,
            limit == null ? -1 : limit,
            new TailRunner.PrintSink(
                new CommandDispatcher.IO() {
                  @Override
                  public void println(String s) {
                    out.println(s);
                  }

                  @Override
                  public void printf(String fmt2, Object... args) {
                    out.printf(fmt2, args);
                  }

                  @Override
                  public void error(String s) {
                    err.println(s);
                  }
                },
                fmt));
        return 0;
      } catch (Exception e) {
        err.println("Error: " + e.getMessage());
        return 1;
      }
    }
  }

  @CommandLine.Command(name = "script", description = "Execute a script file or from stdin")
  static class ScriptCommand implements Callable<Integer> {
    @CommandLine.Parameters(
        index = "0",
        description = "Path to script file (use '-' for stdin)",
        defaultValue = "-")
    private String scriptPath;

    @CommandLine.Parameters(
        index = "1..*",
        arity = "0..*",
        description = "Positional arguments for script ($1, $2, etc.)")
    private List<String> arguments = new ArrayList<>();

    @CommandLine.Option(
        names = {"--continue-on-error"},
        description = "Continue execution on command failures")
    private boolean continueOnError;

    @CommandLine.Option(
        names = {"-b", "--backend"},
        description = "JFR backend to use")
    private String backend;

    @Override
    public Integer call() {
      Integer rc = validateBackend(backend);
      if (rc != 0) {
        return rc;
      }
      // Disable pager in script mode to prevent blocking on "-- more --" prompts
      System.setProperty("jfr.shell.pager", "off");

      // '#!/usr/bin/env -S jbang jfr-shell@btraceio script -': the kernel passes the script's own
      // path after '-', so that file is the script and $1 is the argument after it
      var shebang = ScriptRunner.shebangScript(scriptPath, arguments);
      if (shebang.isPresent()) {
        scriptPath = shebang.get().toString();
        arguments = new ArrayList<>(arguments.subList(1, arguments.size()));
      }

      // Check if reading from stdin
      boolean fromStdin = "-".equals(scriptPath);
      Path path = fromStdin ? null : Paths.get(scriptPath);

      if (!fromStdin && !Files.exists(path)) {
        System.err.println("Error: Script file not found: " + scriptPath);
        return 1;
      }

      try {
        ParsingContext ctx = ParsingContext.create();
        SessionManager<Session> sessions =
            new SessionManager<>((p, c) -> new JFRSession(p, (ParsingContext) c), ctx);
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        CommandDispatcher dispatcher =
            new CommandDispatcher(
                sessions,
                new CommandDispatcher.IO() {
                  @Override
                  public void println(String s) {
                    output.append(s).append("\n");
                  }

                  @Override
                  public void printf(String fmt, Object... args) {
                    output.append(String.format(fmt, args));
                  }

                  @Override
                  public void error(String s) {
                    errors.append(s).append("\n");
                  }
                },
                current -> {},
                null,
                null,
                false); // Suppress informational messages in script mode

        List<String> lines;
        if (fromStdin) {
          // Read from stdin
          lines = new ArrayList<>();
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
              lines.add(line);
            }
          }
        } else {
          lines = Files.readAllLines(path);
        }
        ScriptRunner.ExecutionResult result =
            dispatcher.runScript(lines, arguments, continueOnError);

        // Print captured output
        if (output.length() > 0) {
          System.out.print(output.toString());
        }

        // Print any captured error messages (from io.error())
        if (errors.length() > 0) {
          System.err.print(errors.toString());
        }

        // Handle errors
        if (result.hasErrors()) {
          System.err.println("\nScript completed with errors:");
          for (ScriptRunner.ScriptError error : result.getErrors()) {
            System.err.println(error);
          }
          System.err.println(
              String.format(
                  "\nExecuted %d/%d commands successfully.",
                  result.getSuccessCount(), result.getSuccessCount() + result.getErrors().size()));
          return 1;
        }

        if (result.getSuccessCount() > 0) {
          System.out.println(
              "\nScript executed successfully: " + result.getSuccessCount() + " commands");
        }

        return 0;
      } catch (Exception e) {
        System.err.println("Script execution failed: " + e.getMessage());
        return 1;
      }
    }
  }
}
