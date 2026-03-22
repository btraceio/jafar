package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility for discovering and initializing {@link ShellModule} instances via ServiceLoader.
 */
public final class ShellModuleLoader {
  private static final Logger LOG = LoggerFactory.getLogger(ShellModuleLoader.class);

  /** Loads, initializes, and returns all available modules sorted by priority (highest first). */
  public static List<ShellModule> loadAll() {
    List<ShellModule> loaded = new ArrayList<>();
    for (ShellModule module : ServiceLoader.load(ShellModule.class)) {
      try {
        module.initialize();
        loaded.add(module);
        LOG.info("Loaded module: {} ({})", module.getDisplayName(), module.getId());
      } catch (Exception e) {
        LOG.error("Failed to initialize module: {}", module.getId(), e);
      }
    }
    loaded.sort(Comparator.comparingInt(ShellModule::getPriority).reversed());
    return loaded;
  }

  private ShellModuleLoader() {}
}
