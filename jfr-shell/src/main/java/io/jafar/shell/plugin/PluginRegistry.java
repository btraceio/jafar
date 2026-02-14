package io.jafar.shell.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of available plugins with both hardcoded (offline fallback) and remote definitions.
 *
 * <p>The registry maintains a hybrid approach:
 *
 * <ul>
 *   <li>Hardcoded plugins: Built-in definitions that work offline
 *   <li>Remote plugins: Fetched from GitHub, cached for 24 hours
 * </ul>
 *
 * <p>Remote registry URL: {@code
 * https://raw.githubusercontent.com/btraceio/jafar/main/jfr-shell-plugins.json}
 */
final class PluginRegistry {
  private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

  private static final String REMOTE_REGISTRY_URL =
      "https://raw.githubusercontent.com/btraceio/jafar/main/jfr-shell-plugins.json";
  private static final Duration CACHE_DURATION = Duration.ofHours(24);

  private final PluginStorageManager storageManager;
  private final Path mavenRepositoryPath;
  private Map<String, PluginDefinition> localMavenPlugins;
  private Map<String, PluginDefinition> remotePlugins;
  private Instant lastRemoteFetch;
  private Instant lastLocalScan;

  PluginRegistry(PluginStorageManager storageManager) {
    this(storageManager, getDefaultMavenRepository());
  }

  PluginRegistry(PluginStorageManager storageManager, Path mavenRepositoryPath) {
    this.storageManager = storageManager;
    this.mavenRepositoryPath = mavenRepositoryPath;
    this.localMavenPlugins = new HashMap<>();
    this.remotePlugins = new HashMap<>();
    refreshDiscovery();
  }

  private static Path getDefaultMavenRepository() {
    String userHome = System.getProperty("user.home");
    return Paths.get(userHome, ".m2", "repository");
  }

  /**
   * Get plugin definition by ID.
   *
   * @param pluginId Plugin identifier (e.g., "jdk")
   * @return Plugin definition if found
   */
  Optional<PluginDefinition> get(String pluginId) {
    log.debug(
        "Looking up plugin '{}' (local={}, remote={})",
        pluginId,
        localMavenPlugins.size(),
        remotePlugins.size());

    // Priority: Remote > Local Maven
    PluginDefinition remote = remotePlugins.get(pluginId.toLowerCase());
    if (remote != null) {
      log.debug("Found plugin '{}' in remote registry: {}", pluginId, remote.version());
      return Optional.of(remote);
    }

    PluginDefinition localMaven = localMavenPlugins.get(pluginId.toLowerCase());
    if (localMaven != null) {
      log.debug("Found plugin '{}' in local Maven: {}", pluginId, localMaven.version());
    } else {
      log.debug("Plugin '{}' not found in registry", pluginId);
    }
    return Optional.ofNullable(localMaven);
  }

  /** Refresh plugin discovery if cache is stale. */
  void refreshIfNeeded() {
    try {
      log.debug("Checking if plugin registry refresh needed");
      boolean needsRefresh = false;

      // Check remote registry
      if (lastRemoteFetch == null
          || Duration.between(lastRemoteFetch, Instant.now()).compareTo(CACHE_DURATION) > 0) {
        needsRefresh = true;
      }

      // Check local maven scan
      if (lastLocalScan == null
          || Duration.between(lastLocalScan, Instant.now()).compareTo(CACHE_DURATION) > 0) {
        needsRefresh = true;
      }

      if (needsRefresh) {
        log.debug("Refreshing plugin registry");
        refreshDiscovery();
        log.debug("Plugin registry refresh complete");
      }
    } catch (Exception e) {
      log.warn("Failed to refresh plugin registry", e);
    }
  }

  /** Refresh both local and remote plugin discovery. */
  private void refreshDiscovery() {
    log.debug("Discovering local Maven plugins");
    this.localMavenPlugins = discoverLocalMavenPlugins();
    this.lastLocalScan = Instant.now();
    log.debug("Loading remote plugin registry");
    loadRemotePlugins();
    log.debug(
        "Plugin discovery complete: {} local, {} remote",
        localMavenPlugins.size(),
        remotePlugins.size());
  }

  /** Discover plugins from local Maven repository. */
  private Map<String, PluginDefinition> discoverLocalMavenPlugins() {
    Map<String, PluginDefinition> plugins = new HashMap<>();

    try {
      // Scan <mavenRepo>/io/btrace/ for jfr-shell-* artifacts
      Path btraceRepo = mavenRepositoryPath.resolve("io/btrace");

      if (!Files.exists(btraceRepo)) {
        log.debug("Maven repository path does not exist: {}", btraceRepo);
        return plugins;
      }

      try (var stream = Files.list(btraceRepo)) {
        stream
            .filter(Files::isDirectory)
            .filter(p -> p.getFileName().toString().startsWith("jfr-shell-"))
            .forEach(
                artifactDir -> {
                  String artifactId = artifactDir.getFileName().toString();

                  // Extract plugin ID from artifact ID (e.g., "jfr-shell-jdk" -> "jdk")
                  String pluginId = artifactId.substring("jfr-shell-".length());

                  // Find latest version
                  String latestVersion = findLatestVersion(artifactDir);
                  if (latestVersion != null) {
                    log.debug("Discovered plugin: {} @ {}", pluginId, latestVersion);
                    plugins.put(
                        pluginId.toLowerCase(),
                        new PluginDefinition("io.btrace", artifactId, latestVersion, "local"));
                  }
                });
      }
    } catch (Exception e) {
      log.debug("Error discovering local Maven plugins", e);
      // Ignore discovery errors - return what we found
    }

    return plugins;
  }

  /** Find the latest version in an artifact directory using semantic versioning. */
  private String findLatestVersion(Path artifactDir) {
    try {
      String latest =
          Files.list(artifactDir)
              .filter(Files::isDirectory)
              .map(p -> p.getFileName().toString())
              .filter(v -> !v.equals("maven-metadata-local.xml"))
              .max(this::compareVersions)
              .orElse(null);
      log.debug("Latest version in {}: {}", artifactDir.getFileName(), latest);
      return latest;
    } catch (Exception e) {
      log.debug("Failed to find latest version in {}", artifactDir, e);
      return null;
    }
  }

  /**
   * Compare two version strings using semantic versioning rules.
   *
   * @param v1 First version string (e.g., "0.9.0-SNAPSHOT")
   * @param v2 Second version string (e.g., "0.11.0-SNAPSHOT")
   * @return negative if v1 < v2, positive if v1 > v2, zero if equal
   */
  private int compareVersions(String v1, String v2) {
    // Extract numeric parts and qualifiers
    String[] parts1 = v1.split("-", 2);
    String[] parts2 = v2.split("-", 2);

    String[] nums1 = parts1[0].split("\\.");
    String[] nums2 = parts2[0].split("\\.");

    // Compare major.minor.patch
    int maxLen = Math.max(nums1.length, nums2.length);
    for (int i = 0; i < maxLen; i++) {
      int n1 = i < nums1.length ? parseIntSafe(nums1[i]) : 0;
      int n2 = i < nums2.length ? parseIntSafe(nums2[i]) : 0;
      if (n1 != n2) {
        return Integer.compare(n1, n2);
      }
    }

    // If numeric parts are equal, compare qualifiers (SNAPSHOT, etc.)
    String qual1 = parts1.length > 1 ? parts1[1] : "";
    String qual2 = parts2.length > 1 ? parts2[1] : "";
    return qual1.compareTo(qual2);
  }

  private int parseIntSafe(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Load remote plugin registry from GitHub or cache. */
  private void loadRemotePlugins() {
    try {
      log.debug("Loading remote plugin registry");
      // Try loading from cache first
      String cachedJson = storageManager.loadRegistryCache();
      Instant cacheTime = storageManager.getRegistryCacheTime();

      boolean useCached =
          cachedJson != null
              && cacheTime != null
              && Duration.between(cacheTime, Instant.now()).compareTo(CACHE_DURATION) < 0;

      String registryJson;
      if (useCached) {
        log.debug("Using cached remote registry");
        registryJson = cachedJson;
      } else {
        // Fetch from remote
        log.debug("Fetching remote plugin registry from {}", REMOTE_REGISTRY_URL);
        registryJson = fetchRemoteRegistry();
        if (registryJson != null) {
          storageManager.saveRegistryCache(registryJson);
          log.debug("Remote registry fetched and cached");
        } else {
          // Network failure - use cache if available
          if (cachedJson != null) {
            log.info("Failed to fetch remote registry, using stale cache");
            registryJson = cachedJson;
          } else {
            log.info("No remote registry available (network failure, no cache)");
            // Mark remote fetch as attempted to avoid retrying on every call
            this.lastRemoteFetch = Instant.now();
            return;
          }
        }
      }

      parseRemoteRegistry(registryJson);
      this.lastRemoteFetch = Instant.now();
      log.debug("Remote plugin registry loaded successfully");

    } catch (IOException e) {
      log.warn("Failed to load remote plugin registry", e);
      // Mark as attempted to avoid infinite retries
      this.lastRemoteFetch = Instant.now();
    }
  }

  /** Fetch remote registry from GitHub. */
  private String fetchRemoteRegistry() {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(REMOTE_REGISTRY_URL))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        return response.body();
      }
      return null;
    } catch (Exception e) {
      // Network failure - return null
      return null;
    }
  }

  /** Parse remote registry JSON and populate remotePlugins map. */
  private void parseRemoteRegistry(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject plugins = root.getAsJsonObject("plugins");

      if (plugins != null) {
        Map<String, PluginDefinition> newRemote = new HashMap<>();
        for (String pluginId : plugins.keySet()) {
          JsonObject pluginData = plugins.getAsJsonObject(pluginId);
          String groupId = pluginData.get("groupId").getAsString();
          String artifactId = pluginData.get("artifactId").getAsString();
          String latestVersion = pluginData.get("latestVersion").getAsString();
          String repository = pluginData.get("repository").getAsString();

          newRemote.put(
              pluginId.toLowerCase(),
              new PluginDefinition(groupId, artifactId, latestVersion, repository));
        }
        this.remotePlugins = newRemote;
      }
    } catch (Exception e) {
      // Parse error - keep existing remote plugins
    }
  }

  /** Plugin definition from registry (not yet installed). */
  record PluginDefinition(String groupId, String artifactId, String version, String repository) {
    /**
     * Convert to PluginMetadata for installation.
     *
     * @return Plugin metadata
     */
    PluginMetadata toMetadata() {
      return PluginMetadata.available(groupId, artifactId, version, repository);
    }
  }
}
