package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ScriptRunner;
import io.jafar.shell.core.SessionManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "jfr-shell",
    description = "JFR analysis tool with interactive and non-interactive modes",
    version = "0.1.0",
    mixinStandardHelpOptions = true,
    subcommands = {
      Main.ShowCommand.class,
      Main.MetadataCommand.class,
      Main.ChunksCommand.class,
      Main.CpCommand.class,
      Main.ScriptCommand.class
    })
public final class Main implements Callable<Integer> {
  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "JFR file to open immediately (interactive mode)")
  private String jfrFile;

  @CommandLine.Option(
      names = {"-q", "--quiet"},
      description = "Suppress banner (interactive mode)")
  private boolean quiet;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    // Default: run interactive mode
    try (Shell shell = new Shell()) {
      if (jfrFile != null) {
        Path p = Paths.get(jfrFile);
        if (!Files.exists(p)) {
          System.err.println("Error: JFR file not found: " + jfrFile);
          return 1;
        }
        shell.openIfPresent(p);
      }
      shell.run();
      return 0;
    }
  }

  // Base class for non-interactive commands
  abstract static class NonInteractiveCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Path to JFR recording file")
    protected String jfrFile;

    protected Integer executeNonInteractive(String command) {
      Path path = Paths.get(jfrFile);
      if (!Files.exists(path)) {
        System.err.println("Error: JFR file not found: " + jfrFile);
        return 1;
      }

      try {
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (p, c) -> new JFRSession(p, c));
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

  @CommandLine.Command(name = "cp", description = "List constant pool entries")
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
      StringBuilder cmd = new StringBuilder("cp");

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

    @Override
    public Integer call() {
      // Disable pager in script mode to prevent blocking on "-- more --" prompts
      System.setProperty("jfr.shell.pager", "off");

      // Check if reading from stdin
      boolean fromStdin = "-".equals(scriptPath);
      Path path = fromStdin ? null : Paths.get(scriptPath);

      if (!fromStdin && !Files.exists(path)) {
        System.err.println("Error: Script file not found: " + scriptPath);
        return 1;
      }

      try {
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (p, c) -> new JFRSession(p, c));
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

        CommandDispatcher.IO scriptIO =
            new CommandDispatcher.IO() {
              @Override
              public void println(String s) {
                System.out.println(s);
              }

              @Override
              public void printf(String fmt, Object... args) {
                System.out.printf(fmt, args);
              }

              @Override
              public void error(String s) {
                System.err.println(s);
              }
            };

        ScriptRunner runner = new ScriptRunner(dispatcher, scriptIO, arguments);
        runner.setContinueOnError(continueOnError);

        ScriptRunner.ExecutionResult result;
        if (fromStdin) {
          // Read from stdin
          List<String> lines = new ArrayList<>();
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
              lines.add(line);
            }
          }
          result = runner.execute(lines);
        } else {
          result = runner.execute(path);
        }

        // Print captured output
        if (output.length() > 0) {
          System.out.print(output.toString());
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
