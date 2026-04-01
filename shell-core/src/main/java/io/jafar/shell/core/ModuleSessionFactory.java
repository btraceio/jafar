package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.List;

/**
 * A {@link SessionManager.SessionFactory} that routes file opens to the appropriate {@link
 * ShellModule} based on {@link ShellModule#canHandle(Path)}.
 */
public final class ModuleSessionFactory implements SessionManager.SessionFactory<Session> {
  private final List<ShellModule> modules;

  public ModuleSessionFactory(List<ShellModule> modules) {
    this.modules = modules;
  }

  @Override
  public Session create(Path path, Object context) throws Exception {
    for (ShellModule module : modules) {
      if (module.canHandle(path)) {
        return module.createSession(path, context);
      }
    }
    throw new IllegalArgumentException("No module can handle file: " + path);
  }
}
