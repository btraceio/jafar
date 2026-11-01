package io.jafar.shell.unified;

import io.jafar.shell.TuiShell;
import io.jafar.shell.plugin.PluginManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "jafar-shell",
    description = "Unified analysis tool for JFR recordings and heap dumps",
    versionProvider = io.jafar.shell.Main.VersionProvider.class,
    mixinStandardHelpOptions = true,
    footer = {
      "",
      "Scripts: jafar-shell script <file|-> [args...] [--continue-on-error]",
      "  (one script can open recordings, heap dumps and profiles)",
      "JFR commands: show, metadata, chunks, constants (cp); script with --backend.",
      "JFR options: --backend, --plugin-dir, --install-plugin."
    })
public final class Main implements Callable<Integer> {

  // The command line jfr-shell has always had. The jafar-shell jbang alias runs this class, so
  // these invocations keep going to it. 'script' is not here: it runs in the unified shell (see
  // isScriptCommandLine), except with a JFR option such as --backend, which only jfr-shell's has.
  // The jfr-shell alias, and the shebang scripts written for it, never reach this class: its
  // launcher names io.jafar.shell.Main as the main class.
  private static final Set<String> JFR_COMMANDS =
      Set.of("show", "metadata", "chunks", "constants", "cp");
  private static final Set<String> JFR_OPTIONS =
      Set.of("-b", "--backend", "--plugin-dir", "--install-plugin");

  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "File to open immediately (interactive mode)")
  private String file;

  @CommandLine.Parameters(
      index = "0",
      arity = "0..1",
      description = "File to open (JFR recording or HPROF heap dump)")
  private String positionalFile;

  @CommandLine.Option(
      names = {"-q", "--quiet"},
      description = "Suppress banner (interactive mode)")
  private boolean quiet;

  @CommandLine.Option(
      names = {"--tui"},
      description = "Launch full-screen TUI mode instead of readline REPL")
  private boolean tui;

  static boolean isJfrCommandLine(String[] args) {
    int command = scriptCommandIndex(args);
    if (command >= 0) {
      // 'script --backend jdk f.jfrs': only jfr-shell's script command takes a backend
      for (String arg : args) {
        if (JFR_OPTIONS.contains(arg.split("=", 2)[0])) {
          return true;
        }
      }
      return false;
    }
    for (String arg : args) {
      if (JFR_OPTIONS.contains(arg.split("=", 2)[0])) {
        return true;
      }
      if (!arg.startsWith("-")) {
        return JFR_COMMANDS.contains(arg) && !Files.exists(Paths.get(arg));
      }
    }
    return false;
  }

  /** {@code jafar-shell script ...} that runs in the unified shell (see {@link ScriptCommand}). */
  static boolean isScriptCommandLine(String[] args) {
    return scriptCommandIndex(args) >= 0 && !isJfrCommandLine(args);
  }

  /**
   * Where the {@code script} command is: the first word that is not an option, unless a file of
   * that name exists, which is opened instead as it always was. {@code -1} when there is none.
   */
  private static int scriptCommandIndex(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("-")) {
        return "script".equals(args[i]) && !Files.exists(Paths.get(args[i])) ? i : -1;
      }
    }
    return -1;
  }

  public static void main(String[] args) {
    if (isJfrCommandLine(args)) {
      io.jafar.shell.Main.main(args);
      return;
    }

    if (isScriptCommandLine(args)) {
      int command = scriptCommandIndex(args);
      String[] rest = java.util.Arrays.copyOfRange(args, command + 1, args.length);
      PluginManager.initialize();
      System.exit(new CommandLine(new ScriptCommand()).execute(rest));
      return;
    }

    // Initialize plugin system before BackendRegistry is accessed
    PluginManager.initialize();

    // Debug: Verify backends are available
    if (System.getProperty("jfr.shell.completion.debug") != null) {
      try {
        io.jafar.shell.backend.BackendRegistry registry =
            io.jafar.shell.backend.BackendRegistry.getInstance();
        io.jafar.shell.backend.JfrBackend current = registry.getCurrent();
        System.err.println("[DEBUG] BackendRegistry initialized");
        System.err.println(
            "[DEBUG] Current backend: "
                + current.getId()
                + " (priority: "
                + current.getPriority()
                + ")");
        System.err.println(
            "[DEBUG] Available backends: "
                + registry.listAll().stream()
                    .map(b -> b.getId() + ":" + b.getPriority())
                    .collect(java.util.stream.Collectors.joining(", ")));
      } catch (Exception e) {
        System.err.println("[DEBUG] BackendRegistry initialization FAILED: " + e.getMessage());
        e.printStackTrace(System.err);
      }
    }

    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  /**
   * {@code jafar-shell script <file|-> [args...]}: runs a script through the unified shell, so its
   * lines can open and query every format this shell handles. Output goes to stdout and errors to
   * stderr; the exit status is 1 when any line failed, as in {@code jfr-shell script}.
   */
  @CommandLine.Command(
      name = "jafar-shell script",
      description = "Run a script file, or stdin with '-', in the unified shell")
  static final class ScriptCommand implements Callable<Integer> {
    @CommandLine.Parameters(
        index = "0",
        description = "Path to script file (use '-' for stdin)",
        defaultValue = "-")
    private String scriptPath;

    @CommandLine.Parameters(
        index = "1..*",
        arity = "0..*",
        description = "Positional arguments for the script ($1, $2, ...)")
    private java.util.List<String> arguments = new java.util.ArrayList<>();

    @CommandLine.Option(
        names = {"--continue-on-error"},
        description = "Run the remaining lines after a line fails")
    private boolean continueOnError;

    @CommandLine.Option(
        names = {"-h", "--help"},
        usageHelp = true,
        description = "Show this help message and exit.")
    private boolean help;

    @Override
    public Integer call() throws Exception {
      // A shebang line ending 'script -': the kernel passes the script's own path after '-'
      var shebang = io.jafar.shell.core.scripting.ScriptRunner.shebangScript(scriptPath, arguments);
      if (shebang.isPresent()) {
        scriptPath = shebang.get().toString();
        arguments = new java.util.ArrayList<>(arguments.subList(1, arguments.size()));
      }
      java.util.List<String> lines;
      if ("-".equals(scriptPath)) {
        lines =
            new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        System.in, java.nio.charset.Charset.defaultCharset()))
                .lines()
                .toList();
      } else {
        Path path = Paths.get(scriptPath);
        if (!Files.exists(path)) {
          System.err.println("Error: Script file not found: " + scriptPath);
          return 1;
        }
        lines = Files.readAllLines(path);
      }
      try (Shell shell = Shell.forScript(System.out, System.err)) {
        String name = "-".equals(scriptPath) ? "-" : Paths.get(scriptPath).getFileName().toString();
        return shell.runScript(name, lines, arguments, continueOnError).hasErrors() ? 1 : 0;
      }
    }
  }

  @Override
  public Integer call() throws Exception {
    // -f/--file takes precedence over positional argument
    String target = file != null ? file : positionalFile;
    Path targetPath = null;
    if (target != null) {
      targetPath = Paths.get(target);
      if (!Files.exists(targetPath)) {
        System.err.println("Error: File not found: " + target);
        return 1;
      }
    }

    if (tui) {
      try (TuiShell tuiShell = new TuiShell(targetPath)) {
        tuiShell.openIfPresent(targetPath);
        tuiShell.run();
        return 0;
      }
    }

    try (Shell shell = new Shell()) {
      if (targetPath != null) {
        shell.openIfPresent(targetPath);
      }
      shell.run();
      return 0;
    }
  }
}
