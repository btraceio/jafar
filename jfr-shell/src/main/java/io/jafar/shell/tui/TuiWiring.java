package io.jafar.shell.tui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Terminal;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.TuiAdapter;
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
    final TuiBrowserController browser;
    final TuiRenderer renderer;
    final TuiContext ctx;

    Components(
        TuiEventLoop eventLoop,
        TuiCommandExecutor executor,
        CommandDispatcher dispatcher,
        TuiBrowserController browser,
        TuiRenderer renderer,
        TuiContext ctx) {
      this.eventLoop = eventLoop;
      this.executor = executor;
      this.dispatcher = dispatcher;
      this.browser = browser;
      this.renderer = renderer;
      this.ctx = ctx;
    }

    /** Swaps the TUI adapter across all components. */
    public void swapAdapter(TuiAdapter adapter) {
      executor.setTuiAdapter(adapter);
      executor.setCompleter(adapter != null ? adapter.getCompleter() : null);
      browser.setTuiAdapter(adapter);
      renderer.setTuiAdapter(adapter);

      // Exit browser mode on adapter swap
      if (ctx.browserMode) {
        ctx.browserMode = false;
        ctx.activeBrowserDescriptor = null;
        ctx.browserCategory = null;
        ctx.sidebarFocused = false;
        ctx.sidebarSearchQuery = "";
        ctx.sidebarFilteredIndices = null;
        ctx.metadataBrowserLineRefs = null;
        ctx.metadataByName = null;
      }
    }
  }

  /**
   * Creates and wires all TUI components including the CommandDispatcher.
   *
   * @param backend the terminal backend (for key reading and screen control)
   * @param tuiTerminal the TamboUI terminal (for draw calls)
   * @param sessions session manager
   * @param commandExecutor thread pool for async commands
   * @param adapter the module-specific TUI adapter (may be null for fallback)
   * @param sessionChangeCallback extra callback invoked on session changes (may be null)
   */
  public static Components wire(
      Backend backend,
      Terminal<? extends Backend> tuiTerminal,
      SessionManager<? extends Session> sessions,
      ExecutorService commandExecutor,
      TuiAdapter adapter,
      CommandDispatcher.SessionChangeListener sessionChangeCallback) {

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
              if (sessionChangeCallback != null) {
                sessionChangeCallback.onCurrentSessionChanged(current);
              }
            });

    org.jline.reader.Completer completer = adapter != null ? adapter.getCompleter() : null;

    executor.setDispatcher(dispatcher);
    executor.setSessions(sessions);
    executor.setCompleter(completer);
    executor.setTuiAdapter(adapter);

    TuiDetailBuilder detailBuilder = new TuiDetailBuilder(ctx);
    TuiBrowserController browser = new TuiBrowserController(ctx, sessions, detailBuilder);
    browser.setTuiAdapter(adapter);
    TuiRenderer renderer = new TuiRenderer(ctx, sessions, detailBuilder);
    renderer.setTuiAdapter(adapter);
    TuiKeyHandler keyHandler =
        new TuiKeyHandler(ctx, backend::read, browser, executor, detailBuilder, sessions);

    executor.setBrowser(browser);
    executor.setDetailBuilder(detailBuilder);

    TuiEventLoop eventLoop =
        new TuiEventLoop(ctx, backend, tuiTerminal, renderer, keyHandler, executor, browser);

    executor.loadHistory();
    String prefix = adapter != null ? adapter.getPromptPrefix() : "shell";
    ctx.tabs.add(new TuiContext.ResultTab(prefix + ">"));
    ctx.activeTabIndex = 0;

    return new Components(eventLoop, executor, dispatcher, browser, renderer, ctx);
  }

  private TuiWiring() {}
}
