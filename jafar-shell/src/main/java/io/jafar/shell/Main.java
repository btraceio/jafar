package io.jafar.shell;

import io.jafar.shell.plugin.PluginManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "jafar-shell",
    description = "Unified analysis tool for JFR recordings and heap dumps",
    version = "0.10.0",
    mixinStandardHelpOptions = true)
public final class Main implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "File to open immediately (interactive mode)")
  private String file;

  @CommandLine.Option(
      names = {"-q", "--quiet"},
      description = "Suppress banner (interactive mode)")
  private boolean quiet;

  public static void main(String[] args) {
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
            "[DEBUG] Current backend: " + current.getId() + " (priority: " + current.getPriority() + ")");
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
    try (Shell shell = new Shell()) {
      if (file != null) {
        Path p = Paths.get(file);
        if (!Files.exists(p)) {
          System.err.println("Error: File not found: " + file);
          return 1;
        }
        shell.openIfPresent(p);
      }
      shell.run();
      return 0;
    }
  }
}
