package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.SessionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class Shell implements AutoCloseable {
  private final Terminal terminal;
  private final LineReader lineReader;
  private final SessionManager sessions;
  private final CommandDispatcher dispatcher;
  private boolean running = true;
  private final DefaultHistory history;

  public Shell() throws IOException {
    this.terminal = TerminalBuilder.builder().system(true).build();
    ParsingContext ctx = ParsingContext.create();
    this.sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    java.nio.file.Path histPath =
        java.nio.file.Paths.get(System.getProperty("user.home"), ".jfr-shell", "history");
    try {
      java.nio.file.Files.createDirectories(histPath.getParent());
    } catch (Exception ignore) {
    }
    this.history = new DefaultHistory();
    java.util.Map<String, Object> vars = new java.util.HashMap<>();
    vars.put(org.jline.reader.LineReader.HISTORY_FILE, histPath);
    this.lineReader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .variables(vars)
            .history(history)
            .completer(new ShellCompleter(sessions))
            .build();
    this.dispatcher =
        new CommandDispatcher(
            sessions,
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
                terminal.writer().println(s);
                terminal.flush();
              }
            },
            current -> {});
  }

  public void run() {
    printBanner();
    io.jafar.shell.cli.CommandRecorder recorder = new io.jafar.shell.cli.CommandRecorder();

    while (running) {
      try {
        String input = lineReader.readLine("jfr> ");
        if (input == null || input.isBlank()) continue;
        input = input.trim();

        // Built-ins
        if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
          if (recorder.isRecording()) {
            try {
              recorder.stop();
              terminal
                  .writer()
                  .println("Recording saved to: " + recorder.getCurrentRecordingPath());
              terminal.flush();
            } catch (Exception e) {
              terminal.writer().println("Warning: Failed to save recording: " + e.getMessage());
              terminal.flush();
            }
          }
          running = false;
          continue;
        }

        if ("help".equalsIgnoreCase(input)) {
          printHelp();
          continue;
        }

        // Handle record commands
        if (input.startsWith("record ") || "record".equals(input)) {
          handleRecordCommand(input, recorder);
          continue;
        }

        // Handle script execution
        if (input.startsWith("script ")) {
          handleScriptCommand(input);
          continue;
        }

        // Record non-record/script commands
        if (recorder.isRecording()) {
          try {
            recorder.recordCommand(input);
          } catch (Exception e) {
            terminal.writer().println("Warning: Failed to record command: " + e.getMessage());
            terminal.flush();
          }
        }

        // Dispatch commands
        boolean handled = dispatcher.dispatch(input);
        if (!handled) {
          terminal.writer().println("Unknown command. Type 'help'.");
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
        terminal.flush();
      }
    }
  }

  public void openIfPresent(Path jfrPath) {
    if (jfrPath == null) return;
    if (!Files.exists(jfrPath)) {
      terminal.writer().println("Error: file not found: " + jfrPath);
      terminal.flush();
      return;
    }
    try {
      dispatcher.dispatch("open " + jfrPath.toString());
    } catch (Exception ignore) {
    }
  }

  private void printBanner() {
    terminal.writer().println("╔═══════════════════════════════════════╗");
    terminal.writer().println("║           JFR Shell (CLI)             ║");
    terminal.writer().println("║     Interactive JFR exploration       ║");
    terminal.writer().println("╚═══════════════════════════════════════╝");
    terminal.writer().println("Type 'help' for commands, 'exit' to quit");
    terminal.writer().println();
    terminal.flush();
  }

  private void handleRecordCommand(String input, io.jafar.shell.cli.CommandRecorder recorder) {
    String[] parts = input.split("\\s+", 3);
    if (parts.length < 2) {
      terminal.writer().println("Usage: record start [path] | record stop | record status");
      terminal.flush();
      return;
    }

    String subCommand = parts[1].toLowerCase();
    try {
      switch (subCommand) {
        case "start":
          java.nio.file.Path path = parts.length > 2 ? java.nio.file.Paths.get(parts[2]) : null;
          recorder.start(path);
          terminal.writer().println("Recording started: " + recorder.getCurrentRecordingPath());
          break;
        case "stop":
          recorder.stop();
          terminal.writer().println("Recording stopped: " + recorder.getCurrentRecordingPath());
          break;
        case "status":
          if (recorder.isRecording()) {
            terminal.writer().println("Recording to: " + recorder.getCurrentRecordingPath());
          } else {
            terminal.writer().println("Not currently recording");
          }
          break;
        default:
          terminal.writer().println("Unknown record command: " + subCommand);
          terminal.writer().println("Usage: record start [path] | record stop | record status");
      }
    } catch (Exception e) {
      terminal.writer().println("Error: " + e.getMessage());
    }
    terminal.flush();
  }

  private void handleScriptCommand(String input) {
    String[] parts = input.split("\\s+");
    if (parts.length < 2) {
      terminal.writer().println("Usage: script <path> [--var key=value]...");
      terminal.flush();
      return;
    }

    String scriptPath = parts[1];
    java.util.Map<String, String> variables = new java.util.HashMap<>();

    // Parse --var options
    for (int i = 2; i < parts.length; i += 2) {
      if ("--var".equals(parts[i]) && i + 1 < parts.length) {
        String[] varParts = parts[i + 1].split("=", 2);
        if (varParts.length == 2) {
          variables.put(varParts[0], varParts[1]);
        }
      }
    }

    try {
      java.nio.file.Path path = java.nio.file.Paths.get(scriptPath);
      if (!java.nio.file.Files.exists(path)) {
        terminal.writer().println("Error: Script file not found: " + scriptPath);
        terminal.flush();
        return;
      }

      io.jafar.shell.cli.ScriptRunner runner =
          new io.jafar.shell.cli.ScriptRunner(
              dispatcher,
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
                  terminal.writer().println(s);
                  terminal.flush();
                }
              },
              variables);

      io.jafar.shell.cli.ScriptRunner.ExecutionResult result = runner.execute(path);

      if (result.hasErrors()) {
        terminal.writer().println("\nScript completed with errors:");
        for (io.jafar.shell.cli.ScriptRunner.ScriptError error : result.getErrors()) {
          terminal.writer().println(error);
        }
        terminal
            .writer()
            .println(
                String.format(
                    "\nExecuted %d/%d commands successfully.",
                    result.getSuccessCount(),
                    result.getSuccessCount() + result.getErrors().size()));
      } else if (result.getSuccessCount() > 0) {
        terminal
            .writer()
            .println("\nScript executed successfully: " + result.getSuccessCount() + " commands");
      }
      terminal.flush();
    } catch (Exception e) {
      terminal.writer().println("Script execution failed: " + e.getMessage());
      terminal.flush();
    }
  }

  private void printHelp() {
    terminal.writer().println("JFR Shell Commands:");
    terminal.writer().println();
    terminal.writer().println("Session Management:");
    terminal.writer().println("  open <path> [--alias NAME]     Open a recording as a new session");
    terminal.writer().println("  sessions                       List all sessions");
    terminal.writer().println("  use <id|alias>                 Switch current session");
    terminal.writer().println("  info [id|alias]                Show session information");
    terminal.writer().println("  close [id|alias|--all]         Close session(s)");
    terminal.writer().println();
    terminal.writer().println("Analysis:");
    terminal.writer().println("  show <expr> [options]          Execute JfrPath queries");
    terminal.writer().println("  metadata [options]             List and inspect metadata types");
    terminal.writer().println("  chunks [options]               List chunk information");
    terminal.writer().println("  chunk <index> show             Show specific chunk details");
    terminal.writer().println("  cp [<type>] [options]          Browse constant pool entries");
    terminal.writer().println();
    terminal.writer().println("Scripting:");
    terminal.writer().println("  script <path> [--var k=v]...   Execute a script file");
    terminal
        .writer()
        .println("                                 Use variables with ${varname} in script");
    terminal
        .writer()
        .println("  record start [path]            Start recording commands to script");
    terminal.writer().println("  record stop                    Stop recording and save script");
    terminal.writer().println("  record status                  Show recording status");
    terminal.writer().println();
    terminal.writer().println("General:");
    terminal
        .writer()
        .println("  help [<command>]               Show help (use 'help <command>' for details)");
    terminal.writer().println("  exit|quit                      Exit shell");
    terminal.writer().println();
    terminal.writer().println("Quick Examples:");
    terminal.writer().println("  show events/jdk.FileRead[bytes>=1000] --limit 5");
    terminal.writer().println("  show events/jdk.FileRead | count()");
    terminal.writer().println("  show events/jdk.ExecutionSample | groupBy(thread/name)");
    terminal.writer().println();
    terminal.writer().println("Scripting Examples:");
    terminal.writer().println("  record start my-analysis.jfrs");
    terminal.writer().println("  script my-analysis.jfrs --var recording=/path/to/file.jfr");
    terminal.writer().println("  script - --var file=/path/to.jfr < analysis.jfrs");
    terminal.writer().println();
    terminal.writer().println("For more info:");
    terminal.writer().println("  Type 'help show' for JfrPath query syntax");
    terminal.writer().println("  See example scripts in jfr-shell/src/main/resources/examples/");
    terminal.writer().println("  Visit: https://github.com/btraceio/jafar");
    terminal.flush();
  }

  @Override
  public void close() throws Exception {
    try {
      sessions.closeAll();
    } catch (Exception ignore) {
    }
    try {
      history.save();
    } catch (Exception ignore) {
    }
    terminal.close();
  }
}
