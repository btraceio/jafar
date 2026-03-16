package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.tui.TuiWiring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Full-screen TUI shell using TamboUI for rendering. Launched via {@code --tui} flag as an
 * alternative to the readline-based {@link Shell}.
 *
 * <p>This is a thin coordinator that wires together the extracted TUI components and runs the
 * draw/event loop. Layout: status bar | results (with optional detail split) | command input | tips
 * | hints.
 */
public final class TuiShell implements AutoCloseable {
  private final Terminal jlineTerminal;
  private final SessionManager<JFRSession> sessions;
  private final ExecutorService commandExecutor;
  private final TuiWiring.Components components;

  public TuiShell() throws IOException {
    // Disable pager — TUI mode uses scrollable view buffer instead.
    System.setProperty("jfr.shell.pager", "off");

    this.jlineTerminal = TerminalBuilder.builder().system(true).build();
    JLineShellBackend backend = new JLineShellBackend(jlineTerminal);
    dev.tamboui.terminal.Terminal<JLineShellBackend> tuiTerminal =
        new dev.tamboui.terminal.Terminal<>(backend);

    this.commandExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "tui-command");
              t.setDaemon(true);
              return t;
            });

    ParsingContext parsingCtx = ParsingContext.create();
    this.sessions =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), parsingCtx);

    this.components = TuiWiring.wire(backend, tuiTerminal, sessions, commandExecutor);
  }

  /** Pre-open a JFR recording if the path exists. */
  public void openIfPresent(Path jfrPath) {
    if (jfrPath == null || !Files.exists(jfrPath)) return;
    try {
      components.dispatcher.dispatch("open " + jfrPath);
    } catch (Exception ignore) {
    }
  }

  /** Enter full-screen mode and run the draw/event loop until exit. */
  public void run() throws IOException {
    components.eventLoop.run();
  }

  @Override
  public void close() throws Exception {
    components.executor.saveHistory();
    try {
      components.dispatcher.getGlobalStore().clear();
    } catch (Exception ignore) {
    }
    try {
      sessions.closeAll();
    } catch (Exception ignore) {
    }
    commandExecutor.shutdownNow();
    jlineTerminal.close();
  }
}
