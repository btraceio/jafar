package io.jafar.shell.tui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Terminal;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.SessionManager;
import java.util.concurrent.ExecutorService;

/**
 * Factory that wires all TUI components together. Keeps cross-component wiring within the tui
 * package where all constructors and setters are accessible.
 */
public final class TuiWiring {

  /** Assembled TUI components returned to the coordinator. */
  public static final class Components {
    public final TuiEventLoop eventLoop;
    public final TuiCommandExecutor executor;
    public final CommandDispatcher dispatcher;

    Components(TuiEventLoop eventLoop, TuiCommandExecutor executor, CommandDispatcher dispatcher) {
      this.eventLoop = eventLoop;
      this.executor = executor;
      this.dispatcher = dispatcher;
    }
  }

  /**
   * Creates and wires all TUI components including the CommandDispatcher.
   *
   * @param backend the terminal backend (for key reading and screen control)
   * @param tuiTerminal the TamboUI terminal (for draw calls)
   * @param sessions session manager
   * @param commandExecutor thread pool for async commands
   */
  public static Components wire(
      Backend backend,
      Terminal<? extends Backend> tuiTerminal,
      SessionManager sessions,
      ExecutorService commandExecutor) {

    TuiContext ctx = new TuiContext();
    TuiCommandExecutor executor = new TuiCommandExecutor(ctx, commandExecutor);

    CommandDispatcher dispatcher =
        new CommandDispatcher(
            sessions,
            new CommandDispatcher.IO() {
              @Override
              public void println(String s) {
                executor.addOutputLine(s);
              }

              @Override
              public void printf(String fmt, Object... args) {
                executor.addOutputLine(String.format(fmt, args));
              }

              @Override
              public void error(String s) {
                executor.addOutputLine("ERROR: " + s);
              }
            },
            current -> {
              if (current != null) {
                current.outputFormat = "tui";
              }
            });

    ShellCompleter completer = new ShellCompleter(sessions, dispatcher);

    executor.setDispatcher(dispatcher);
    executor.setSessions(sessions);
    executor.setCompleter(completer);

    TuiDetailBuilder detailBuilder = new TuiDetailBuilder(ctx);
    TuiBrowserController browser = new TuiBrowserController(ctx, sessions, detailBuilder);
    TuiRenderer renderer = new TuiRenderer(ctx, sessions, detailBuilder);
    TuiKeyHandler keyHandler =
        new TuiKeyHandler(ctx, backend::read, browser, executor, detailBuilder, sessions);

    executor.setBrowser(browser);
    executor.setDetailBuilder(detailBuilder);

    TuiEventLoop eventLoop =
        new TuiEventLoop(ctx, backend, tuiTerminal, renderer, keyHandler, executor, browser);

    executor.loadHistory();
    ctx.tabs.add(new TuiContext.ResultTab("jfr>"));
    ctx.activeTabIndex = 0;

    return new Components(eventLoop, executor, dispatcher);
  }

  private TuiWiring() {}
}
