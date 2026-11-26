package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.ShellModuleLoader;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.compare.CompareOptions;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.JfrCompare;
import io.jafar.shell.core.compare.ProfileCompare;
import io.jafar.shell.core.compare.ProfileReader;
import io.jafar.shell.core.compare.ProfileSelection;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.compare.SampleTypeInfo;
import io.jafar.shell.core.discovery.ProviderDiscovery;
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
import java.util.Locale;
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
      Main.CompareCommand.class,
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
    try {
      new CommandLine(main).parseArgs(args);
    } catch (CommandLine.ParameterException e) {
      // A usage error (missing or malformed arguments): say what is wrong and how the command is
      // used, as picocli's own handler would, instead of escaping with a stack trace.
      System.exit(usageError(e));
    }

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

  /** Exit code for a usage error, as picocli's {@code execute} returns for invalid input. */
  static final int USAGE_ERROR = 2;

  /** Prints a usage error's message and the failing command's usage to stderr. */
  static int usageError(CommandLine.ParameterException e) {
    System.err.println("Error: " + e.getMessage());
    e.getCommandLine().usage(System.err);
    return USAGE_ERROR;
  }

  /** An exception's message, or its class name when it has none (an NPE, many wrapped errors). */
  static String describe(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getName() : message;
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

  /** Prints providers skipped during backend discovery, if any, with a fix hint. */
  private static void printBackendFailures(BackendRegistry registry) {
    var failures = registry.discoveryFailures();
    if (failures.isEmpty()) {
      return;
    }
    System.err.println("Failed to load " + failures.size() + " backend provider(s):");
    for (var failure : failures) {
      System.err.println("  " + failure);
    }
    System.err.println("Reinstall or remove the broken jar(s) from ~/.jfr-shell/plugins.");
  }

  private boolean validateBackend() {
    try {
      BackendRegistry registry = BackendRegistry.getInstance();

      // Auto-install default backend if none available
      if (registry.listAll().isEmpty() && !autoInstallDefaultBackend()) {
        System.err.println("Error: No JFR backends available");
        printBackendFailures(registry);
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
                    .map(b -> ProviderDiscovery.call(b::getId, "?"))
                    .collect(Collectors.joining(", ")));
        printBackendFailures(registry);
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
        printBackendFailures(registry);
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
                    .map(b -> ProviderDiscovery.call(b::getId, "?"))
                    .collect(Collectors.joining(", ")));
        printBackendFailures(registry);
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
                    output.append(String.format(Locale.ROOT, fmt, args));
                  }

                  @Override
                  public void error(String s) {
                    errors.append(s).append("\n");
                  }
                },
                current -> {});

        // As a Path, not an "open <path>" line: the dispatcher would split it at its spaces (#233)
        dispatcher.open(path, null);

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
                    out.printf(Locale.ROOT, fmt2, args);
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

  /**
   * {@code compare <baseline> <candidate>}: the {@code jfr_compare} comparison, headless, for JFR
   * recordings and pprof profiles in any pairing. Exits 0 when the comparison ran, whatever it
   * found; 1 when it could not run (a missing or unreadable file, an unknown event or sample type,
   * samplers that measure different things); and 2 on a usage error (a missing or malformed
   * argument), with the message and usage on stderr. It reports where time moved; it does not
   * decide whether a build got slower.
   *
   * <p>A file that starts with JFR's magic is read as a recording. Any other file goes to the first
   * module on the class path whose {@code canHandle} claims it and that has a {@link
   * io.jafar.shell.core.compare.ProfileReader}; when none does, it is read as a recording, so a
   * file that is neither fails with "cannot read JFR recording". jfr-shell's own jar knows JFR
   * only; the {@code jafar-shell} jar adds the pprof module.
   */
  @CommandLine.Command(
      name = "compare",
      mixinStandardHelpOptions = true,
      description =
          "Compare a candidate recording or profile against a baseline: per-frame self and"
              + " inclusive time shares, and event rates when both are JFR recordings. Each side"
              + " is a JFR recording or a pprof profile (pprof needs the jafar-shell jar).")
  static class CompareCommand implements Callable<Integer> {
    @CommandLine.Parameters(
        index = "0",
        description = "Baseline recording or profile (the 'before')")
    private String baselineFile;

    @CommandLine.Parameters(
        index = "1",
        description = "Candidate recording or profile (the 'after')")
    private String candidateFile;

    @CommandLine.Option(
        names = {"--sample-type"},
        paramLabel = "<type[/unit]>",
        description =
            "pprof sample type, for every pprof side (default: the first CPU sample type, else"
                + " the first)")
    private String sampleType;

    @CommandLine.Option(
        names = {"--baseline-sample-type"},
        paramLabel = "<type[/unit]>",
        description = "pprof sample type of the baseline; overrides --sample-type")
    private String baselineSampleType;

    @CommandLine.Option(
        names = {"--candidate-sample-type"},
        paramLabel = "<type[/unit]>",
        description = "pprof sample type of the candidate; overrides --sample-type")
    private String candidateSampleType;

    @CommandLine.Option(
        names = {"--allow-mixed-samplers"},
        description =
            "Compare samplers of different kinds (CPU against wall clock) instead of refusing;"
                + " no change is then called significant. Allocation against time is always"
                + " refused")
    private boolean allowMixedSamplers;

    @CommandLine.Option(
        names = {"--list-sample-types"},
        description = "List the sample types of both files and exit")
    private boolean listSampleTypes;

    @CommandLine.Option(
        names = {"--json"},
        description = "Print the full result as JSON")
    private boolean json;

    @CommandLine.Option(
        names = {"--event-type"},
        description = "Execution sample event type (default: detected per recording)")
    private String eventType;

    @CommandLine.Option(
        names = {"--min-delta-pct"},
        description = "Noise floor in percentage points for per-frame changes (default: 1.0)")
    private double minDeltaPct = JfrCompare.DEFAULT_MIN_DELTA_PCT;

    @CommandLine.Option(
        names = {"--limit", "-l"},
        description = "Maximum number of changed frames per list (default: 25)")
    private int limit = JfrCompare.DEFAULT_LIMIT;

    @CommandLine.Option(
        names = {"-b", "--backend"},
        description = "JFR backend to use")
    private String backend;

    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

    /** An option used wrongly: the message and the command's usage on stderr, exit 2. */
    private int usage(String message) {
      return usageError(new CommandLine.ParameterException(spec.commandLine(), message));
    }

    @Override
    public Integer call() {
      if (limit <= 0) {
        return usage("--limit must be positive");
      }
      if (!Double.isFinite(minDeltaPct) || minDeltaPct < 0) {
        return usage("--min-delta-pct must be a number of zero or more");
      }
      Path baselinePath = Paths.get(baselineFile);
      Path candidatePath = Paths.get(candidateFile);
      for (Path p : List.of(baselinePath, candidatePath)) {
        Path name = p.getFileName(); // null for a root such as "/"
        String what = name != null && name.toString().endsWith(".jfr") ? "JFR file" : "file";
        if (!Files.isRegularFile(p)) {
          System.err.println("Error: " + what + " not found: " + p);
          return 1;
        }
        if (!Files.isReadable(p)) {
          System.err.println("Error: " + what + " not readable: " + p);
          return 1;
        }
      }
      try {
        // null: the side is a JFR recording, read through the selected backend.
        ProfileReader baseReader = profileReader(baselinePath);
        ProfileReader candReader = profileReader(candidatePath);
        String baseType = baselineSampleType != null ? baselineSampleType : sampleType;
        String candType = candidateSampleType != null ? candidateSampleType : sampleType;
        String misuse = typeOptionMisuse(baseReader, candReader);
        if (misuse != null) {
          return usage(misuse);
        }
        if (baseReader == null || candReader == null) {
          Integer rc = validateBackend(backend);
          if (rc != 0) {
            return rc;
          }
        }
        if (listSampleTypes) {
          System.out.print(listTypes(baselinePath, baseReader));
          System.out.print(listTypes(candidatePath, candReader));
          return 0;
        }

        ParsingContext ctx = ParsingContext.create();
        // Inclusive shares always: they are what names a caller whose callees got slower.
        RecordingProfile base = read(baselinePath, baseReader, baseType, 1, ctx);
        RecordingProfile cand = read(candidatePath, candReader, candType, 2, ctx);
        CompareResult result =
            ProfileCompare.compare(
                base,
                cand,
                new CompareOptions(
                    eventType,
                    minDeltaPct,
                    limit,
                    true,
                    CompareOptions.MixedSamplers.forPair(
                        base.format(), cand.format(), allowMixedSamplers)));
        System.out.print(json ? CompareOutput.json(result.toMap()) : CompareOutput.table(result));
        return 0;
      } catch (Exception e) {
        System.err.println("Error: " + describe(e));
        return 1;
      }
    }

    /**
     * The reader of a file that is not a JFR recording, or {@code null} for a recording: a file
     * with JFR's magic, or one no module with a reader claims.
     *
     * @throws IllegalArgumentException when the module that claims the file cannot compare it
     */
    static ProfileReader profileReader(Path path) {
      if (isJfr(path)) {
        return null;
      }
      for (ShellModule module : ShellModuleLoader.loadAll()) {
        if ("jfr".equals(module.getId()) || !module.canHandle(path)) {
          continue;
        }
        ProfileReader reader = module.profileReader();
        if (reader == null) {
          throw new IllegalArgumentException(
              "cannot compare "
                  + path
                  + ": it is a "
                  + module.getDisplayName()
                  + " file, and only JFR recordings and pprof profiles can be compared");
        }
        return reader;
      }
      return null;
    }

    private static boolean isJfr(Path path) {
      try (java.io.InputStream in = Files.newInputStream(path)) {
        byte[] magic = in.readNBytes(4);
        return magic.length == 4
            && magic[0] == 'F'
            && magic[1] == 'L'
            && magic[2] == 'R'
            && magic[3] == 0;
      } catch (java.io.IOException e) {
        return false;
      }
    }

    /**
     * {@code --event-type} is for JFR sides and the sample-type options for the others: the misuse,
     * or {@code null} when there is none.
     */
    private String typeOptionMisuse(ProfileReader baseReader, ProfileReader candReader) {
      if (eventType != null && baseReader != null && candReader != null) {
        return "--event-type selects a JFR event type, and neither file is a JFR recording: use"
            + " --sample-type";
      }
      if (sampleType != null && baseReader == null && candReader == null) {
        return "--sample-type selects a pprof sample type, and both files are JFR recordings: use"
            + " --event-type";
      }
      if (baselineSampleType != null && baseReader == null) {
        return "--baseline-sample-type selects a pprof sample type, and the baseline is a JFR"
            + " recording: use --event-type";
      }
      if (candidateSampleType != null && candReader == null) {
        return "--candidate-sample-type selects a pprof sample type, and the candidate is a JFR"
            + " recording: use --event-type";
      }
      return null;
    }

    private RecordingProfile read(
        Path path, ProfileReader reader, String type, int id, ParsingContext ctx) throws Exception {
      if (reader != null) {
        return reader.read(path, new ProfileSelection(type, true));
      }
      try (JFRSession session = open(path, ctx)) {
        return new JfrCompare().profile(AnalysisTarget.of(id, session), eventType, true);
      }
    }

    private static String listTypes(Path path, ProfileReader reader) throws Exception {
      ProfileReader r = reader != null ? reader : new JfrCompare();
      StringBuilder out = new StringBuilder();
      out.append(path).append(" (").append(r.format()).append(")\n");
      List<SampleTypeInfo> types = r.sampleTypes(path);
      if (types.isEmpty()) {
        out.append("  no sample types\n");
      }
      for (SampleTypeInfo type : types) {
        out.append(String.format(java.util.Locale.ROOT, "  %-40s %s%n", type.label(), type.kind()));
      }
      return out.toString();
    }

    /** Opens a recording, naming the file when it is not one the parser can read. */
    private static JFRSession open(Path path, ParsingContext ctx) {
      try {
        return new JFRSession(path, ctx);
      } catch (Exception e) {
        String hint =
            isJfr(path)
                ? ""
                : " (it is not a JFR recording, and no module on the class path reads it: pprof"
                    + " profiles are read by the jafar-shell jar)";
        throw new IllegalArgumentException(
            "cannot read JFR recording " + path + ": " + describe(e) + hint, e);
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
                    output.append(String.format(Locale.ROOT, fmt, args));
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
                  Locale.ROOT,
                  "\nExecuted %d/%d commands successfully.",
                  result.getSuccessCount(),
                  result.getSuccessCount() + result.getErrors().size()));
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
