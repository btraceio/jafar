package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SessionManager.SessionFactory} that routes file opens to the appropriate {@link
 * ShellModule} based on {@link ShellModule#canHandle(Path)}.
 *
 * <p>A module whose {@code canHandle} or {@code createSession} fails must not take the others down
 * with it, nor the shell: a module that throws from {@code canHandle} is treated as not-a-match,
 * and a failure from {@code createSession} surfaces as a plain {@link Exception} naming the module.
 * Virtual-machine errors and thread death still propagate.
 */
public final class ModuleSessionFactory implements SessionManager.SessionFactory<Session> {
  private static final Logger LOG = LoggerFactory.getLogger(ModuleSessionFactory.class);

  private final List<ShellModule> modules;

  public ModuleSessionFactory(List<ShellModule> modules) {
    this.modules = modules;
  }

  @Override
  public Session create(Path path, Object context) throws Exception {
    for (ShellModule module : modules) {
      if (!canHandle(module, path)) {
        continue;
      }
      try {
        return module.createSession(path, context);
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        if (t instanceof Exception e) {
          throw e;
        }
        // A module that throws something other than an exception (e.g. LinkageError from a
        // half-loaded jar) should surface as an ordinary open failure, not crash the shell.
        throw new Exception("Module '" + safeId(module) + "' failed to open " + path + ": " + t, t);
      }
    }
    throw new IllegalArgumentException("No module can handle file: " + path);
  }

  private static boolean canHandle(ShellModule module, Path path) {
    try {
      return module.canHandle(path);
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      LOG.warn("Module {} cannot decide support for {}: {}", safeId(module), path, t.toString());
      return false;
    }
  }

  private static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError || t instanceof ThreadDeath;
  }

  private static String safeId(ShellModule module) {
    try {
      return String.valueOf(module.getId());
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      return "(unknown)";
    }
  }
}
