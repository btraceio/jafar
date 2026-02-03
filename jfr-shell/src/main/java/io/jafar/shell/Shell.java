package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.CommandRecorder;
import io.jafar.shell.cli.ScriptRunner;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.plugin.PluginManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class Shell implements AutoCloseable {
  private static final Path SCRIPTS_DIR =
      Paths.get(System.getProperty("user.home"), ".jfr-shell", "scripts");

  private final Terminal terminal;
  private final LineReader lineReader;
  private final SessionManager sessions;
  private final CommandDispatcher dispatcher;
  private boolean running = true;
  private final DefaultHistory history;

  public Shell() throws IOException {
    this.terminal = TerminalBuilder.builder().system(true).build();
    ParsingContext ctx = ParsingContext.create();
    this.sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    java.nio.file.Path histPath =
        java.nio.file.Paths.get(System.getProperty("user.home"), ".jfr-shell", "history");
    try {
      java.nio.file.Files.createDirectories(histPath.getParent());
    } catch (Exception ignore) {
    }
    this.history = new DefaultHistory();
    // Create dispatcher first so it can be passed to ShellCompleter
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
    java.util.Map<String, Object> vars = new java.util.HashMap<>();
    vars.put(org.jline.reader.LineReader.HISTORY_FILE, histPath);
    this.lineReader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .variables(vars)
            .history(history)
            .completer(new ShellCompleter(sessions, dispatcher))
            .build();
  }

  public void run() {
    printBanner();

    // Check for plugin updates in background (non-blocking)
    CompletableFuture.runAsync(
        () -> {
          try {
            PluginManager.getInstance().checkAndApplyUpdates();
          } catch (Exception e) {
            // Log but don't fail startup
          }
        });

    CommandRecorder recorder = new CommandRecorder();

    while (running) {
      try {
        // Adjust prompt based on conditional nesting depth
        String prompt;
        if (dispatcher.getConditionalState().inConditional()) {
          int depth = dispatcher.getConditionalState().depth();
          prompt = "...(" + depth + ")> ";
        } else {
          prompt = "jfr> ";
        }

        String input = lineReader.readLine(prompt);
        if (input == null || input.isBlank()) continue;
        input = input.trim();

        // Built-ins
        if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
          // Warn about unclosed conditionals
          if (dispatcher.getConditionalState().inConditional()) {
            terminal
                .writer()
                .println(
                    "Warning: "
                        + dispatcher.getConditionalState().depth()
                        + " unclosed conditional block(s)");
            terminal.flush();
            dispatcher.getConditionalState().reset();
          }
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

  private void handleRecordCommand(String input, CommandRecorder recorder) {
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
          Path path = parts.length > 2 ? Paths.get(parts[2]) : null;
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
      printScriptUsage();
      return;
    }

    String subCommand = parts[1].toLowerCase();

    switch (subCommand) {
      case "list":
        listScripts();
        return;
      case "run":
        if (parts.length < 3) {
          terminal.writer().println("Usage: script run <name> [arg1] [arg2] ...");
          terminal.flush();
          return;
        }
        runScriptByName(parts, 2);
        return;
      default:
        // Backward compatibility: treat as path
        runScriptByPath(parts, 1);
    }
  }

  private void printScriptUsage() {
    terminal.writer().println("Usage:");
    terminal.writer().println("  script list                    List available scripts");
    terminal
        .writer()
        .println("  script run <name> [args...]    Run script by name from scripts dir");
    terminal.writer().println("  script <path> [args...]        Run script by full path");
    terminal.writer().println();
    terminal.writer().println("Scripts directory: " + SCRIPTS_DIR);
    terminal.flush();
  }

  private void listScripts() {
    if (!Files.exists(SCRIPTS_DIR)) {
      terminal.writer().println("Scripts directory does not exist: " + SCRIPTS_DIR);
      terminal.writer().println("Create it and add .jfrs scripts to use 'script run <name>'");
      terminal.flush();
      return;
    }

    try {
      List<Path> scripts =
          Files.list(SCRIPTS_DIR).filter(p -> p.toString().endsWith(".jfrs")).sorted().toList();

      if (scripts.isEmpty()) {
        terminal.writer().println("No scripts found in: " + SCRIPTS_DIR);
        terminal.writer().println("Add .jfrs script files to this directory");
        terminal.flush();
        return;
      }

      terminal.writer().println("Available scripts in " + SCRIPTS_DIR + ":");
      terminal.writer().println();
      for (Path script : scripts) {
        String name = script.getFileName().toString();
        String baseName = name.substring(0, name.length() - 5); // Remove .jfrs
        String description = getScriptDescription(script);
        if (description != null) {
          terminal.writer().printf("  %-25s %s%n", baseName, description);
        } else {
          terminal.writer().println("  " + baseName);
        }
      }
      terminal.writer().println();
      terminal.writer().println("Run with: script run <name> [args...]");
      terminal.flush();
    } catch (IOException e) {
      terminal.writer().println("Error listing scripts: " + e.getMessage());
      terminal.flush();
    }
  }

  private String getScriptDescription(Path script) {
    try {
      List<String> lines = Files.readAllLines(script);
      for (String line : lines) {
        line = line.trim();
        if (line.isEmpty()) continue;
        if (line.startsWith("#!")) continue; // Skip shebang
        if (line.startsWith("#")) {
          // First comment line is the description
          String desc = line.substring(1).trim();
          if (!desc.isEmpty() && !desc.toLowerCase().startsWith("arguments:")) {
            return desc;
          }
        } else {
          break; // Stop at first non-comment line
        }
      }
    } catch (IOException ignore) {
    }
    return null;
  }

  private void runScriptByName(String[] parts, int nameIndex) {
    String scriptName = parts[nameIndex];
    List<String> arguments = new ArrayList<>();
    for (int i = nameIndex + 1; i < parts.length; i++) {
      arguments.add(parts[i]);
    }

    // Resolve script path
    Path scriptPath = SCRIPTS_DIR.resolve(scriptName + ".jfrs");
    if (!Files.exists(scriptPath)) {
      // Try without adding extension
      scriptPath = SCRIPTS_DIR.resolve(scriptName);
      if (!Files.exists(scriptPath)) {
        terminal.writer().println("Script not found: " + scriptName);
        terminal.writer().println("Use 'script list' to see available scripts");
        terminal.flush();
        return;
      }
    }

    executeScript(scriptPath, arguments);
  }

  private void runScriptByPath(String[] parts, int pathIndex) {
    String scriptPathStr = parts[pathIndex];
    List<String> arguments = new ArrayList<>();
    for (int i = pathIndex + 1; i < parts.length; i++) {
      arguments.add(parts[i]);
    }

    Path scriptPath = Paths.get(scriptPathStr);
    if (!Files.exists(scriptPath)) {
      terminal.writer().println("Error: Script file not found: " + scriptPathStr);
      terminal.flush();
      return;
    }

    executeScript(scriptPath, arguments);
  }

  private void executeScript(Path scriptPath, List<String> arguments) {
    try {
      // Auto-bind current session's JFR file as first argument if session is active
      List<String> effectiveArgs = new ArrayList<>(arguments);
      sessions
          .getCurrent()
          .ifPresent(
              ref -> {
                Path recordingPath = ref.session.getFilePath();
                effectiveArgs.add(0, recordingPath.toString());
                terminal.writer().println("Using session recording: " + recordingPath);
                terminal.flush();
              });

      ScriptRunner runner =
          new ScriptRunner(
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
              effectiveArgs);

      ScriptRunner.ExecutionResult result = runner.execute(scriptPath);

      if (result.hasErrors()) {
        terminal.writer().println("\nScript completed with errors:");
        for (ScriptRunner.ScriptError error : result.getErrors()) {
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
    terminal.writer().println("Variables:");
    terminal
        .writer()
        .println("  set [--global] <name> = <val>  Set variable (scalar or lazy query)");
    terminal.writer().println("  vars [--global|--session]      List variables");
    terminal.writer().println("  unset <name>                   Remove variable");
    terminal.writer().println("  echo <text>                    Print with ${var} substitution");
    terminal.writer().println("  invalidate <name>              Clear cached lazy variable");
    terminal.writer().println();
    terminal.writer().println("Conditionals:");
    terminal.writer().println("  if <condition>                 Start conditional block");
    terminal.writer().println("  elif <condition>               Else-if branch");
    terminal.writer().println("  else                           Else branch");
    terminal.writer().println("  endif                          End conditional block");
    terminal.writer().println();
    terminal.writer().println("Scripting:");
    terminal.writer().println("  script list                    List available scripts");
    terminal.writer().println("  script run <name> [args...]    Run script by name");
    terminal.writer().println("  script <path> [args...]        Run script by full path");
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
    terminal.writer().println("  script list                             List available scripts");
    terminal.writer().println("  script run basic-analysis /tmp/app.jfr  Run script by name");
    terminal.writer().println("  script /path/to/analysis.jfrs arg1      Run script by path");
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
      dispatcher.getGlobalStore().clear(); // Release global variables
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
    terminal.close();
  }
}
