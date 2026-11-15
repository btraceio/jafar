package io.jafar.shell;

import io.jafar.shell.core.ModuleSessionFactory;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.ShellModuleLoader;
import io.jafar.shell.core.TuiAdapter;
import io.jafar.shell.tui.TuiWiring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-screen TUI shell using TamboUI for rendering. Launched via {@code --tui} flag as an
 * alternative to the readline-based {@link Shell}.
 *
 * <p>This is a thin coordinator that wires together the extracted TUI components and runs the
 * draw/event loop. Layout: status bar | results (with optional detail split) | command input | tips
 * | hints.
 */
public final class TuiShell implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TuiShell.class);

  private final Terminal jlineTerminal;
  private final SessionManager<Session> sessions;
  private final List<ShellModule> modules;
  private final Map<String, ShellModule> moduleById;
  private final ExecutorService commandExecutor;
  private final TuiWiring.Components components;

  /** Tracks the last module ID so we only swap adapter when the module actually changes. */
  private volatile String currentModuleId;

  public TuiShell(Path targetPath) throws IOException {
    // Disable pager — TUI mode uses scrollable view buffer instead.
    System.setProperty("jfr.shell.pager", "off");

    this.jlineTerminal = TerminalBuilder.builder().system(true).build();
    JLineShellBackend shellBackend = new JLineShellBackend(jlineTerminal);
    dev.tamboui.terminal.Terminal<JLineShellBackend> tuiTerminal =
        new dev.tamboui.terminal.Terminal<>(shellBackend);

    this.commandExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "tui-command");
              t.setDaemon(true);
              return t;
            });

    this.modules = ShellModuleLoader.loadAll();
    this.moduleById = new HashMap<>();
    for (ShellModule m : modules) {
      moduleById.put(m.getId(), m);
    }
    this.sessions = new SessionManager<>(new ModuleSessionFactory(modules), null);

    // Find the initial adapter from the target file's module, or the first available module
    ShellModule initialModule = findModuleForFile(targetPath);
    TuiAdapter adapter = null;
    if (initialModule != null) {
      currentModuleId = initialModule.getId();
    }

    this.components =
        TuiWiring.wire(
            shellBackend, tuiTerminal, sessions, commandExecutor, adapter, this::onSessionChanged);

    // Now that dispatcher exists, create proper adapter with dispatcher as context
    if (initialModule != null) {
      swapToModule(initialModule);
    }
  }

  private void onSessionChanged(SessionManager.SessionRef<? extends Session> current) {
    if (current == null) return;
    String sessionType = current.session.getType();
    // Only swap if the module type actually changed
    if (sessionType.equals(currentModuleId)) return;
    ShellModule module = moduleById.get(sessionType);
    if (module != null) {
      swapToModule(module);
    }
  }

  private void swapToModule(ShellModule module) {
    currentModuleId = moduleId(module);
    TuiAdapter adapter = createTuiAdapter(module);
    components.swapAdapter(adapter);
    components.dispatcher.setModuleEvaluator(moduleQueryEvaluator(module));
  }

  /** A broken module must not take the TUI down, not even for a log line. */
  private static String moduleId(ShellModule module) {
    try {
      String id = module.getId();
      return id == null ? "(unknown)" : id;
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      return "(unknown)";
    }
  }

  private TuiAdapter createTuiAdapter(ShellModule module) {
    try {
      return module.createTuiAdapter(sessions, components.dispatcher);
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      LOG.error("Module '{}' failed to create a TUI adapter: {}", moduleId(module), t.toString());
      return null;
    }
  }

  /** A broken module must not take the TUI down; it just cannot answer queries. */
  private static QueryEvaluator moduleQueryEvaluator(ShellModule module) {
    try {
      return module.getQueryEvaluator();
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      LOG.error(
          "Module '{}' failed to provide a query evaluator: {}", moduleId(module), t.toString());
      return null;
    }
  }

  private static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError || t instanceof ThreadDeath;
  }

  private ShellModule findModuleForFile(Path path) {
    if (path != null && Files.exists(path)) {
      for (ShellModule m : modules) {
        try {
          if (m.canHandle(path)) {
            return m;
          }
        } catch (Throwable t) {
          if (isFatal(t)) {
            throw (Error) t;
          }
          LOG.error("Module '{}' failed the file check: {}", moduleId(m), t.toString());
        }
      }
    }
    // Default to first module
    return modules.isEmpty() ? null : modules.get(0);
  }

  /** Pre-open a file if the path exists. */
  public void openIfPresent(Path filePath) {
    if (filePath == null || !Files.exists(filePath)) return;
    try {
      components.dispatcher.dispatch("open " + filePath);
    } catch (Exception ignore) {
      // best-effort auto-open: failures are reported by the open command itself
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
      // best-effort cleanup: keep closing the rest
    }
    try {
      sessions.closeAll();
    } catch (Exception ignore) {
      // best-effort cleanup: keep closing the rest
    }
    commandExecutor.shutdownNow();

    for (ShellModule module : modules) {
      try {
        module.shutdown();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        // one module failing to shut down must not stop the others
      }
    }

    jlineTerminal.close();
  }
}
