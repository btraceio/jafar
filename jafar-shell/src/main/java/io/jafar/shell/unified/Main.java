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
      "JFR commands: show, metadata, chunks, constants (cp), script.",
      "JFR options: --backend, --plugin-dir, --install-plugin."
    })
public final class Main implements Callable<Integer> {

  // The command line jfr-shell has always had; this jar is what the jafar-shell and jfr-shell
  // aliases both install, so those invocations keep going to it.
  private static final Set<String> JFR_COMMANDS =
      Set.of("show", "metadata", "chunks", "constants", "cp", "script");
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

  public static void main(String[] args) {
    if (isJfrCommandLine(args)) {
      io.jafar.shell.Main.main(args);
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
}
