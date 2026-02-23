package io.jafar.shell.tui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Terminal;
import java.io.IOException;

/**
 * Runs the TUI draw/event loop. Keeps the loop within the tui package where it can access
 * package-private fields and methods on TuiContext and the other TUI components.
 */
public final class TuiEventLoop {
  private final TuiContext ctx;
  private final Backend backend;
  private final Terminal<? extends Backend> tuiTerminal;
  private final TuiRenderer renderer;
  private final TuiKeyHandler keyHandler;
  private final TuiCommandExecutor executor;
  private final TuiBrowserController browser;

  TuiEventLoop(
      TuiContext ctx,
      Backend backend,
      Terminal<? extends Backend> tuiTerminal,
      TuiRenderer renderer,
      TuiKeyHandler keyHandler,
      TuiCommandExecutor executor,
      TuiBrowserController browser) {
    this.ctx = ctx;
    this.backend = backend;
    this.tuiTerminal = tuiTerminal;
    this.renderer = renderer;
    this.keyHandler = keyHandler;
    this.executor = executor;
    this.browser = browser;
  }

  public void run() throws IOException {
    backend.enterAlternateScreen();
    backend.enableRawMode();
    backend.hideCursor();

    try {
      while (ctx.running) {
        if (ctx.commandFuture != null && ctx.commandFuture.isDone()) {
          executor.finishAsyncCommand();
        }

        if (ctx.browserNavPending != null
            && System.nanoTime() - ctx.browserNavTime >= TuiContext.BROWSER_NAV_DEBOUNCE_NS) {
          String pending = ctx.browserNavPending;
          boolean keep = ctx.browserNavKeepFocus;
          ctx.browserNavPending = null;
          browser.loadBrowserEntries(pending, keep);
        }

        tuiTerminal.draw(renderer::render);
        backend.showCursor();

        int key = backend.read(100);
        if (key == TuiContext.READ_EXPIRED) continue;
        if (key == TuiContext.EOF) {
          ctx.running = false;
          continue;
        }
        if (ctx.commandRunning) continue;
        keyHandler.handleKey(key);
      }
    } finally {
      backend.showCursor();
      backend.disableRawMode();
      backend.leaveAlternateScreen();
    }
  }
}
