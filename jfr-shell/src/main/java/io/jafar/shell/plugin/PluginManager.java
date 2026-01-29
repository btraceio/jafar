package io.jafar.shell.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the plugin system including installation, updates, and ClassLoader management.
 *
 * <p>This is the main entry point for plugin operations. It coordinates between storage, registry,
 * and installation components.
 *
 * <p>Usage:
 *
 * <pre>
 * // Initialize before BackendRegistry
 * PluginManager.initialize();
 *
 * // Get ClassLoader for ServiceLoader discovery
 * ClassLoader loader = PluginManager.getInstance().getPluginClassLoader();
 * </pre>
 */
public final class PluginManager {
  private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
  private static final PluginManager INSTANCE = new PluginManager();

  private final PluginStorageManager storageManager;
  private final PluginRegistry registry;
  private final MavenResolver resolver;
  private final PluginInstaller installer;
  private final UpdateChecker updateChecker;
  private ClassLoader pluginClassLoader;

  private PluginManager() {
    this.storageManager = new PluginStorageManager();
    this.resolver = new MavenResolver();
    this.registry = new PluginRegistry(storageManager);
    this.installer = new PluginInstaller(registry, resolver, storageManager);
    this.updateChecker = new UpdateChecker(registry);
  }

  /**
   * Get the singleton PluginManager instance.
   *
   * @return PluginManager instance
   */
  public static PluginManager getInstance() {
    return INSTANCE;
  }

  /**
   * Initialize the plugin system.
   *
   * <p>This should be called from Main.main() before BackendRegistry initializes. It loads all
   * installed plugins and creates the plugin ClassLoader.
   */
  public static void initialize() {
    INSTANCE.loadInstalledPlugins();
  }

  /**
   * Load all installed plugins and create the plugin ClassLoader.
   *
   * <p>This reads the plugin storage directory and constructs a ClassLoader with all installed
   * plugin JARs. If loading fails, creates an empty ClassLoader so the application can still run
   * with built-in backends.
   */
  private void loadInstalledPlugins() {
    try {
      List<Path> pluginJars = storageManager.getAllInstalledJars();

      // Create ClassLoader with plugin JARs (or empty if no plugins installed)
      ClassLoader parent = PluginManager.class.getClassLoader();
      this.pluginClassLoader = new PluginClassLoader(pluginJars, parent);

      if (!pluginJars.isEmpty()) {
        log.info("Loaded {} plugin(s)", pluginJars.size());
      }
    } catch (IOException e) {
      // Log but don't fail - create empty ClassLoader so app can use built-in backends
      log.warn("Failed to load plugins: {}", e.getMessage());
      this.pluginClassLoader = PluginManager.class.getClassLoader();
    }
  }

  /**
   * Get the plugin ClassLoader for ServiceLoader discovery.
   *
   * <p>This ClassLoader includes all installed plugin JARs and should be used by BackendRegistry
   * when discovering backends via ServiceLoader.
   *
   * @return ClassLoader with plugin JARs loaded
   */
  public ClassLoader getPluginClassLoader() {
    if (pluginClassLoader == null) {
      throw new IllegalStateException(
          "PluginManager not initialized. Call PluginManager.initialize() first.");
    }
    return pluginClassLoader;
  }

  /**
   * Check if a plugin can be installed.
   *
   * <p>This checks if the plugin exists in the plugin registry (either hardcoded or remote).
   *
   * @param pluginId Plugin identifier (e.g., "jdk")
   * @return true if plugin can be installed
   */
  public boolean canInstall(String pluginId) {
    registry.refreshIfNeeded();
    return registry.get(pluginId).isPresent();
  }

  /**
   * Install a plugin from Maven repositories.
   *
   * <p>This downloads the plugin JAR from Maven Central/Sonatype, verifies its checksum, and
   * installs it to the plugin storage directory.
   *
   * @param pluginId Plugin identifier (e.g., "jdk")
   * @throws PluginInstallException if installation fails
   */
  public void installPlugin(String pluginId) throws PluginInstallException {
    installer.install(pluginId);
  }

  /**
   * Check for updates and apply them according to update policy.
   *
   * <p>This is called in the background on startup with a 500ms timeout. It checks for patch
   * version updates and automatically downloads them in the background, notifying about minor/major
   * updates. Downloaded updates are available on next restart.
   */
  public void checkAndApplyUpdates() {
    // Launch background thread for update checking
    java.util.concurrent.CompletableFuture<Void> updateFuture =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              try {
                Map<String, PluginMetadata> installed = storageManager.loadInstalled();
                log.debug("Checking updates for {} installed plugins", installed.size());

                List<UpdateChecker.PluginUpdate> updates;
                try {
                  updates = updateChecker.checkForUpdates(installed);
                } catch (Exception e) {
                  log.debug("Failed to check for updates", e);
                  return;
                }

                if (updates.isEmpty()) {
                  return;
                }

                // Separate auto-install vs notify-only updates
                List<UpdateChecker.PluginUpdate> autoInstall =
                    updates.stream().filter(UpdateChecker.PluginUpdate::shouldAutoInstall).toList();
                List<UpdateChecker.PluginUpdate> notifyOnly =
                    updates.stream().filter(u -> !u.shouldAutoInstall()).toList();

                // Auto-download patch updates in background
                for (UpdateChecker.PluginUpdate update : autoInstall) {
                  try {
                    log.info(
                        "Downloading update in background: {} {}",
                        update.pluginId(),
                        update.availableVersion());
                    installer.install(update.pluginId());
                    log.info(
                        "Plugin updated to {}. Restart jfr-shell to use new version.",
                        update.availableVersion());
                  } catch (PluginInstallException e) {
                    log.warn("Background update failed: {}", e.getMessage());
                  }
                }

                // Notify about minor/major updates
                if (!notifyOnly.isEmpty()) {
                  log.info("Updates available (manual update required):");
                  for (UpdateChecker.PluginUpdate update : notifyOnly) {
                    log.info("  {}", update);
                  }
                }

              } catch (IOException e) {
                log.debug("Failed to load installed plugins for update check", e);
              }
            });

    // Wait max 500ms for initial check, then let it continue in background
    try {
      updateFuture.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      log.debug("Update check continuing in background");
      // Let it continue in background
    } catch (Exception e) {
      log.debug("Update check failed", e);
    }
  }
}
