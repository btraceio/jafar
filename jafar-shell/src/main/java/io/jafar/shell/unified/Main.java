package io.jafar.shell.unified;

import io.jafar.shell.TuiShell;
import io.jafar.shell.plugin.PluginManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
      "script <file|-> [args...] [--continue-on-error]: run a script in this shell, any format.",
      "JFR commands: show, metadata, chunks, constants (cp).",
      "JFR options: --backend, --plugin-dir, --install-plugin."
    })
public final class Main implements Callable<Integer> {

  // The command line jfr-shell has always had, so 'jafar-shell show x.jfr ...' keeps working.
  // 'script' is not among them: it runs in this shell, so a script can open any format. (The
  // jfr-shell jbang alias launches io.jafar.shell.Main directly and never comes through here.)
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

  /** The arguments after a leading {@code script} word, or {@code null} if this is not one. */
  static String[] scriptArguments(String[] args) {
    if (args.length > 0 && "script".equals(args[0]) && !Files.exists(Paths.get("script"))) {
      return java.util.Arrays.copyOfRange(args, 1, args.length);
    }
    return null;
  }

  public static void main(String[] args) {
    if (isJfrCommandLine(args)) {
      io.jafar.shell.Main.main(args);
      return;
    }
    String[] script = scriptArguments(args);
    if (script != null) {
      PluginManager.initialize();
      System.exit(new CommandLine(new ScriptCommand()).execute(script));
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

  /** {@code jafar-shell script <file|-> [args...]}: a script run by this shell, not jfr-shell's. */
  @CommandLine.Command(
      name = "script",
      mixinStandardHelpOptions = true,
      description = "Run a script in jafar-shell: any format, the commands of its prompt")
  static final class ScriptCommand implements Callable<Integer> {
    @CommandLine.Parameters(
        index = "0",
        description = "Path to script file ('-' for stdin)",
        defaultValue = "-")
    private String scriptPath;

    @CommandLine.Parameters(
        index = "1..*",
        arity = "0..*",
        description = "Positional arguments for the script ($1, $2, ...)")
    private List<String> arguments = new java.util.ArrayList<>();

    @CommandLine.Option(
        names = {"--continue-on-error"},
        description = "Run every line even after one fails")
    private boolean continueOnError;

    @CommandLine.Option(
        names = {"-b", "--backend"},
        description = "JFR backend to use for recordings, as in jfr-shell")
    private String backend;

    @Override
    public Integer call() throws Exception {
      if (backend != null) {
        // Read when the backend registry first initialises, which opening a recording does
        System.setProperty("jfr.shell.backend", backend);
      }
      List<String> lines;
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
      try (Shell shell = Shell.forScript()) {
        return shell.runScript(lines, arguments, continueOnError);
      }
    }
  }
}
