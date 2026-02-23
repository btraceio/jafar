package io.jafar.shell.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages plugin storage on the file system.
 *
 * <p>Directory structure:
 *
 * <pre>
 * ~/.jfr-shell/
 * ├── plugins/
 * │   ├── io.btrace/
 * │   │   └── jfr-shell-jdk/
 * │   │       ├── 0.9.0/
 * │   │       │   ├── jfr-shell-jdk-0.9.0.jar
 * │   │       │   └── metadata.json
 * │   │       └── 0.9.1/
 * │   ├── plugin-registry.json
 * │   └── installed.json
 * └── config.json
 * </pre>
 */
final class PluginStorageManager {

  private static final String JFR_SHELL_DIR = ".jfr-shell";
  private static final String PLUGINS_DIR = "plugins";
  private static final String INSTALLED_JSON = "installed.json";
  private static final String REGISTRY_CACHE_JSON = "plugin-registry.json";
  private static final String PLUGIN_DIR_PROPERTY = "jfr.shell.plugin.dir";
  private static final String PLUGIN_DIR_ENV = "JFRSHELL_PLUGIN_DIR";

  private final Path pluginsDir;
  private final Path installedJsonPath;
  private final Path registryCachePath;
  private final Gson gson;

  PluginStorageManager() {
    this.pluginsDir = resolvePluginDirectory();
    this.installedJsonPath = pluginsDir.resolve(INSTALLED_JSON);
    this.registryCachePath = pluginsDir.resolve(REGISTRY_CACHE_JSON);
    this.gson = new GsonBuilder().setPrettyPrinting().create();

    try {
      Files.createDirectories(pluginsDir);
    } catch (IOException e) {
      // Log but don't fail - will retry on actual operations
    }
  }

  /**
   * Resolve plugin directory from (in order): 1. System property: jfr.shell.plugin.dir 2.
   * Environment variable: JFRSHELL_PLUGIN_DIR 3. Default: ~/.jfr-shell/plugins
   */
  private Path resolvePluginDirectory() {
    // Check system property
    String sysProp = System.getProperty(PLUGIN_DIR_PROPERTY);
    if (sysProp != null && !sysProp.isBlank()) {
      return Paths.get(sysProp);
    }

    // Check environment variable
    String envVar = System.getenv(PLUGIN_DIR_ENV);
    if (envVar != null && !envVar.isBlank()) {
      return Paths.get(envVar);
    }

    // Default
    String userHome = System.getProperty("user.home");
    Path jfrShellDir = Paths.get(userHome, JFR_SHELL_DIR);
    return jfrShellDir.resolve(PLUGINS_DIR);
  }

  /** Get the plugins directory path. */
  Path getPluginsDir() {
    return pluginsDir;
  }

  /**
   * Get the directory for a specific plugin version.
   *
   * @param metadata Plugin metadata
   * @return Path to plugin version directory
   */
  Path getPluginVersionDir(PluginMetadata metadata) {
    return pluginsDir
        .resolve(metadata.groupId())
        .resolve(metadata.artifactId())
        .resolve(metadata.version());
  }

  /**
   * Get the JAR file path for a plugin.
   *
   * @param metadata Plugin metadata
   * @return Path to plugin JAR file
   */
  Path getPluginJarPath(PluginMetadata metadata) {
    String jarName = metadata.artifactId() + "-" + metadata.version() + ".jar";
    return getPluginVersionDir(metadata).resolve(jarName);
  }

  /**
   * Get all installed plugin JARs.
   *
   * @return List of JAR file paths
   */
  List<Path> getAllInstalledJars() throws IOException {
    List<Path> jars = new ArrayList<>();
    Map<String, PluginMetadata> installed = loadInstalled();

    for (PluginMetadata metadata : installed.values()) {
      Path jarPath = getPluginJarPath(metadata);
      if (Files.exists(jarPath)) {
        jars.add(jarPath);
      }
    }

    return jars;
  }

  /**
   * Load installed plugins metadata from installed.json.
   *
   * @return Map of plugin ID to metadata
   */
  Map<String, PluginMetadata> loadInstalled() throws IOException {
    Map<String, PluginMetadata> result = new HashMap<>();

    if (!Files.exists(installedJsonPath)) {
      return result;
    }

    try (Reader reader = Files.newBufferedReader(installedJsonPath)) {
      JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
      JsonObject plugins = root.getAsJsonObject("plugins");

      if (plugins != null) {
        for (String pluginId : plugins.keySet()) {
          JsonObject pluginData = plugins.getAsJsonObject(pluginId);
          String groupId = pluginData.get("groupId").getAsString();
          String artifactId = pluginData.get("artifactId").getAsString();
          String version = pluginData.get("currentVersion").getAsString();
          String repository = pluginData.get("repository").getAsString();
          String installedAtStr = pluginData.get("installedAt").getAsString();
          Instant installedAt = Instant.parse(installedAtStr);

          result.put(
              pluginId,
              PluginMetadata.installed(groupId, artifactId, version, repository, installedAt));
        }
      }
    }

    return result;
  }

  /**
   * Save installed plugins metadata to installed.json.
   *
   * @param installed Map of plugin ID to metadata
   */
  void saveInstalled(Map<String, PluginMetadata> installed) throws IOException {
    Files.createDirectories(pluginsDir);

    JsonObject root = new JsonObject();
    root.addProperty("version", 1);

    JsonObject plugins = new JsonObject();
    for (Map.Entry<String, PluginMetadata> entry : installed.entrySet()) {
      String pluginId = entry.getKey();
      PluginMetadata metadata = entry.getValue();

      JsonObject pluginData = new JsonObject();
      pluginData.addProperty("groupId", metadata.groupId());
      pluginData.addProperty("artifactId", metadata.artifactId());
      pluginData.addProperty("currentVersion", metadata.version());
      pluginData.addProperty("repository", metadata.repository());
      pluginData.addProperty("installedAt", metadata.installedAt().toString());
      pluginData.addProperty("autoUpdate", true);

      plugins.add(pluginId, pluginData);
    }

    root.add("plugins", plugins);
    root.addProperty("lastRegistryUpdate", Instant.now().toString());

    try (Writer writer = Files.newBufferedWriter(installedJsonPath)) {
      gson.toJson(root, writer);
    }
  }

  /**
   * Load cached plugin registry from plugin-registry.json.
   *
   * @return Cached registry JSON, or null if not cached
   */
  String loadRegistryCache() throws IOException {
    if (!Files.exists(registryCachePath)) {
      return null;
    }
    return Files.readString(registryCachePath);
  }

  /**
   * Save plugin registry to cache.
   *
   * @param registryJson Registry JSON content
   */
  void saveRegistryCache(String registryJson) throws IOException {
    Files.createDirectories(pluginsDir);
    Files.writeString(registryCachePath, registryJson);
  }

  /**
   * Get timestamp of last registry cache update.
   *
   * @return Instant of last modification, or null if not cached
   */
  Instant getRegistryCacheTime() throws IOException {
    if (!Files.exists(registryCachePath)) {
      return null;
    }
    return Files.getLastModifiedTime(registryCachePath).toInstant();
  }
}
