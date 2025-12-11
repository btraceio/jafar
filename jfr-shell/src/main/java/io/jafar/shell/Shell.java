package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.SessionManager;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.impl.history.DefaultHistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        java.nio.file.Path histPath = java.nio.file.Paths.get(System.getProperty("user.home"), ".jfr-shell", "history");
        try { java.nio.file.Files.createDirectories(histPath.getParent()); } catch (Exception ignore) {}
        this.history = new DefaultHistory();
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put(org.jline.reader.LineReader.HISTORY_FILE, histPath);
        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variables(vars)
                .history(history)
                .completer(new ShellCompleter(sessions))
                .build();
        this.dispatcher = new CommandDispatcher(sessions, new CommandDispatcher.IO() {
            @Override public void println(String s) { terminal.writer().println(s); terminal.flush(); }
            @Override public void printf(String fmt, Object... args) { terminal.writer().printf(fmt, args); terminal.flush(); }
            @Override public void error(String s) { terminal.writer().println(s); terminal.flush(); }
        }, current -> {});
    }

    public void run() {
        printBanner();
        while (running) {
            try {
                String input = lineReader.readLine("jfr> ");
                if (input == null || input.isBlank()) continue;
                input = input.trim();
                // Built-ins
                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) { running = false; continue; }
                if ("help".equalsIgnoreCase(input)) { printHelp(); continue; }
                // Dispatch M1 commands
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
        } catch (Exception ignore) { }
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

    private void printHelp() {
        terminal.writer().println("Commands:");
        terminal.writer().println("  open <path> [--alias NAME]     Open a recording as a new session");
        terminal.writer().println("  sessions                       List all sessions");
        terminal.writer().println("  use <id|alias>                 Switch current session");
        terminal.writer().println("  info [id|alias]                Show session information");
        terminal.writer().println("  metadata [options]             List and inspect metadata types");
        terminal.writer().println("  show <expr> [options]          Execute JfrPath queries");
        terminal.writer().println("  chunks [options]               List chunk information");
        terminal.writer().println("  chunk <index> show             Show specific chunk details");
        terminal.writer().println("  cp [<type>] [options]          Browse constant pool entries");
        terminal.writer().println("  close [id|alias|--all]         Close session(s)");
        terminal.writer().println("  help [<command>]               Show help (use 'help <command>' for details)");
        terminal.writer().println("  exit|quit                      Exit shell");
        terminal.writer().println();
        terminal.writer().println("Quick Examples:");
        terminal.writer().println("  show events/jdk.FileRead[bytes>=1000] --limit 5");
        terminal.writer().println("  show events/jdk.FileRead | count()");
        terminal.writer().println("  show events/jdk.ExecutionSample | groupBy(thread/name)");
        terminal.writer().println("  show metadata/java.lang.Thread --tree");
        terminal.writer().println();
        terminal.writer().println("Type 'help show' for JfrPath query syntax and more examples.");
        terminal.flush();
    }

    @Override
    public void close() throws Exception {
        try { sessions.closeAll(); } catch (Exception ignore) {}
        try { history.save(); } catch (Exception ignore) {}
        terminal.close();
    }
}
