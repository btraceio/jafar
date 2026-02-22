package io.jafar.shell.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
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
   * Reinitialize the plugin system after new plugins have been installed at runtime.
   *
   * <p>This reloads all plugin JARs and recreates the ClassLoader so that newly installed plugins
   * become visible to ServiceLoader without restarting the application.
   */
  public static void reinitialize() {
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
   * Install a plugin from a local JAR file.
   *
   * <p>This is intended for offline/airgapped environments where plugins cannot be downloaded from
   * Maven repositories. The JAR filename should follow the convention: {artifactId}-{version}.jar
   *
   * @param jarPath Path to the plugin JAR file
   * @throws PluginInstallException if installation fails
   */
  public void installLocalPlugin(Path jarPath) throws PluginInstallException {
    if (!Files.exists(jarPath)) {
      throw new PluginInstallException("JAR file not found: " + jarPath);
    }

    String fileName = jarPath.getFileName().toString();
    if (!fileName.endsWith(".jar")) {
      throw new PluginInstallException("File must be a JAR: " + fileName);
    }

    // Validate JAR contains ServiceLoader config
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      ZipEntry serviceEntry = jar.getEntry("META-INF/services/io.jafar.shell.backend.JfrBackend");
      if (serviceEntry == null) {
        throw new PluginInstallException(
            "JAR does not contain a JfrBackend service provider (META-INF/services/io.jafar.shell.backend.JfrBackend)");
      }

      // Parse artifactId and version from filename: {artifactId}-{version}.jar
      String baseName = fileName.substring(0, fileName.length() - 4); // Remove .jar
      Pattern pattern = Pattern.compile("^(.+)-([0-9][0-9a-zA-Z.\\-]*)$");
      Matcher matcher = pattern.matcher(baseName);

      String artifactId;
      String version;
      if (matcher.matches()) {
        artifactId = matcher.group(1);
        version = matcher.group(2);
      } else {
        throw new PluginInstallException(
            "Cannot parse artifactId and version from filename: "
                + fileName
                + ". Expected format: {artifactId}-{version}.jar");
      }

      // Try to extract groupId from pom.properties
      String groupId = extractGroupId(jar, artifactId);
      if (groupId == null) {
        groupId = "io.btrace"; // Default groupId
        log.debug("Using default groupId: {}", groupId);
      }

      // Derive pluginId from artifactId (strip "jfr-shell-" prefix if present, normalize to
      // lowercase)
      String pluginId = artifactId;
      if (artifactId.startsWith("jfr-shell-")) {
        pluginId = artifactId.substring("jfr-shell-".length());
      }
      pluginId = pluginId.toLowerCase();

      // Create metadata for storage operations
      PluginMetadata metadata =
          PluginMetadata.installed(groupId, artifactId, version, "local", Instant.now());

      // Copy JAR to plugin directory
      Path targetPath = storageManager.getPluginJarPath(metadata);
      Files.createDirectories(targetPath.getParent());
      Files.copy(jarPath, targetPath, StandardCopyOption.REPLACE_EXISTING);

      // Update installed.json - rollback JAR on failure
      try {
        Map<String, PluginMetadata> installed = storageManager.loadInstalled();
        installed.put(pluginId, metadata);
        storageManager.saveInstalled(installed);
      } catch (IOException e) {
        // Rollback: remove copied JAR
        try {
          Files.deleteIfExists(targetPath);
        } catch (IOException ignored) {
          // Best effort cleanup
        }
        throw e;
      }

      log.info(
          "Installed plugin from local JAR: {} ({}:{}:{}) -> {}",
          pluginId,
          groupId,
          artifactId,
          version,
          targetPath);
    } catch (IOException e) {
      throw new PluginInstallException("Failed to install plugin from JAR: " + e.getMessage(), e);
    }
  }

  /**
   * Extract groupId from pom.properties inside the JAR.
   *
   * @param jar The JAR file
   * @param artifactId The expected artifactId
   * @return groupId if found, null otherwise
   */
  private String extractGroupId(JarFile jar, String artifactId) {
    // Look for META-INF/maven/{groupId}/{artifactId}/pom.properties
    var entries = jar.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();
      String name = entry.getName();
      if (name.startsWith("META-INF/maven/")
          && name.endsWith("/pom.properties")
          && name.contains("/" + artifactId + "/")) {
        try (InputStream is = jar.getInputStream(entry)) {
          Properties props = new Properties();
          props.load(is);
          String groupId = props.getProperty("groupId");
          if (groupId != null && !groupId.isEmpty()) {
            log.debug("Extracted groupId from pom.properties: {}", groupId);
            return groupId;
          }
        } catch (IOException e) {
          log.debug("Failed to read pom.properties: {}", e.getMessage());
        }
      }
    }
    return null;
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
