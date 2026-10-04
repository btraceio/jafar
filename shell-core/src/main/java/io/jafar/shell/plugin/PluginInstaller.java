package io.jafar.shell.plugin;

import io.jafar.shell.plugin.DependencyResolver.DependencyException;
import io.jafar.shell.plugin.DependencyResolver.ResolutionResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles plugin installation with checksum verification and rollback support.
 *
 * <p>Installation process:
 *
 * <ol>
 *   <li>Lookup plugin in registry
 *   <li>Resolve artifact via MavenResolver
 *   <li>Download checksum file (.sha256)
 *   <li>Verify checksum
 *   <li>Copy to ~/.jfr-shell/plugins/{groupId}/{artifactId}/{version}/
 *   <li>Update installed.json
 *   <li>Verify ServiceLoader discovery works
 *   <li>Rollback on failure
 * </ol>
 */
final class PluginInstaller {
  private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

  private final PluginRegistry registry;
  private final MavenResolver resolver;
  private final PluginStorageManager storageManager;

  PluginInstaller(
      PluginRegistry registry, MavenResolver resolver, PluginStorageManager storageManager) {
    this.registry = registry;
    this.resolver = resolver;
    this.storageManager = storageManager;
  }

  /**
   * Install a plugin with dependency resolution.
   *
   * @param pluginId Plugin identifier (e.g., "jdk")
   * @param confirmRecommendations Callback to confirm recommended plugins (returns which to
   *     install)
   * @param progressCallback Callback for installation progress messages
   * @return List of installed plugins (in installation order)
   * @throws PluginInstallException if installation fails
   */
  List<String> installWithDependencies(
      String pluginId,
      java.util.function.Function<List<String>, List<String>> confirmRecommendations,
      Consumer<String> progressCallback)
      throws PluginInstallException {

    Map<String, PluginMetadata> installed;
    try {
      installed = storageManager.loadInstalled();
    } catch (IOException e) {
      throw new PluginInstallException("Failed to load installed plugins: " + e.getMessage(), e);
    }
    DependencyResolver resolver = new DependencyResolver(registry, installed);

    // Resolve dependencies
    ResolutionResult resolution;
    try {
      resolution = resolver.resolve(pluginId);
    } catch (DependencyException e) {
      throw new PluginInstallException("Dependency resolution failed: " + e.getMessage(), e);
    }

    List<String> toInstall = new ArrayList<>(resolution.installOrder());

    // Handle recommendations
    if (resolution.hasRecommendations() && confirmRecommendations != null) {
      List<String> acceptedRecs = confirmRecommendations.apply(resolution.recommended());
      if (acceptedRecs != null && !acceptedRecs.isEmpty()) {
        // Resolve dependencies for accepted recommendations too
        for (String rec : acceptedRecs) {
          try {
            ResolutionResult recResolution = resolver.resolve(rec);
            for (String dep : recResolution.installOrder()) {
              if (!toInstall.contains(dep)) {
                // Insert before the recommendation
                int recIndex = toInstall.indexOf(rec);
                if (recIndex >= 0) {
                  toInstall.add(recIndex, dep);
                } else {
                  toInstall.add(dep);
                }
              }
            }
          } catch (DependencyException e) {
            log.warn(
                "Failed to resolve dependencies for recommended plugin {}: {}",
                rec,
                e.getMessage());
          }
        }
      }
    }

    // Install in order
    List<String> installedPlugins = new ArrayList<>();
    for (String plugin : toInstall) {
      if (progressCallback != null) {
        progressCallback.accept("Installing " + plugin + "...");
      }
      install(plugin);
      installedPlugins.add(plugin);
    }

    return installedPlugins;
  }

  /**
   * Install a plugin from Maven repositories (single plugin, no dependency resolution).
   *
   * @param pluginId Plugin identifier (e.g., "jdk")
   * @throws PluginInstallException if installation fails
   */
  void install(String pluginId) throws PluginInstallException {
    log.debug("Installing plugin: {}", pluginId);
    // 1. Lookup plugin in registry
    PluginRegistry.PluginDefinition definition =
        registry
            .get(pluginId)
            .orElseThrow(
                () -> new PluginInstallException("Plugin not found in registry: " + pluginId));

    PluginMetadata metadata = definition.toMetadata();
    log.debug("Found plugin metadata: {}:{}", metadata.artifactId(), metadata.version());

    // 2. Resolve the artifact and everything its POM needs on the classpath. The installer copies
    // the dependency jars into the plugin's storage directory next to the root jar; the plugin
    // classloader includes them, so plugins can be thin jars that depend on real libraries.
    log.debug("Resolving Maven artifact");
    MavenResolver.ResolvedPlugin resolved =
        resolver.resolveWithDependencies(
            metadata.groupId(), metadata.artifactId(), metadata.version());
    Path downloadedJar = resolved.rootJar();
    log.debug(
        "Artifact resolved: {} (with {} dependencies)",
        downloadedJar,
        resolved.dependencies().size());

    // 3. Download and verify checksum
    log.debug("Verifying checksum");
    verifyChecksum(downloadedJar, metadata);
    log.debug("Checksum verified");

    // 4. Copy to plugin storage
    Path targetVersionDir = storageManager.getPluginVersionDir(metadata);
    Path targetJar = storageManager.getPluginJarPath(metadata);

    try {
      Files.createDirectories(targetVersionDir);
      Files.copy(downloadedJar, targetJar, StandardCopyOption.REPLACE_EXISTING);

      // Dependency jars go into a deps/ subdirectory of the version directory: flat names would
      // risk collisions between differing coordinates sharing an artifactId-version pair.
      if (!resolved.dependencies().isEmpty()) {
        Path dependenciesDir = targetVersionDir.resolve("deps");
        for (Artifact dependency : resolved.dependencies()) {
          Path targetPath =
              dependenciesDir
                  .resolve(dependency.getGroupId().replace('.', '/'))
                  .resolve(dependency.getArtifactId())
                  .resolve(dependency.getBaseVersion())
                  .resolve(dependency.getArtifactId() + "-" + dependency.getBaseVersion() + ".jar");
          Files.createDirectories(targetPath.getParent());
          if (dependency.getFile() == null) {
            throw new PluginInstallException(
                "Resolved dependency has no file: "
                    + dependency.getGroupId()
                    + ":"
                    + dependency.getArtifactId()
                    + ":"
                    + dependency.getBaseVersion());
          }
          Files.copy(
              dependency.getFile().toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
          log.debug("Dependency jar written: {}", targetPath);
        }
      }

      // 5. Update installed.json
      PluginMetadata installedMetadata =
          PluginMetadata.installed(
              metadata.groupId(),
              metadata.artifactId(),
              metadata.version(),
              metadata.repository(),
              Instant.now());

      Map<String, PluginMetadata> installed = storageManager.loadInstalled();
      installed.put(pluginId.toLowerCase(), installedMetadata);
      storageManager.saveInstalled(installed);

      // 6. Verify ServiceLoader discovery (optional validation step)
      // Note: This requires reloading the ClassLoader, which won't happen until restart
      // So we skip this check for now - validation happens on next startup

    } catch (IOException e) {
      // Rollback on failure
      rollback(targetVersionDir, pluginId);
      throw new PluginInstallException("Failed to install plugin to storage", e);
    }
  }

  /** Verify JAR checksum against downloaded .sha256 file. */
  private void verifyChecksum(Path jarPath, PluginMetadata metadata) throws PluginInstallException {
    Path checksumPath =
        resolver.resolveChecksum(metadata.groupId(), metadata.artifactId(), metadata.version());

    if (checksumPath == null) {
      // No checksum available - skip verification
      // (Maven Central provides SHA1, not always SHA256)
      return;
    }

    try {
      // Read expected checksum
      String expectedChecksum = Files.readString(checksumPath).trim();

      // Calculate actual checksum using streaming to avoid OOM on large JARs
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream is = Files.newInputStream(jarPath);
          DigestInputStream dis = new DigestInputStream(is, digest)) {
        byte[] buffer = new byte[8192];
        while (dis.read(buffer) != -1) {
          // Just reading to compute digest
        }
      }
      String actualChecksum = HexFormat.of().formatHex(digest.digest());

      if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
        throw new PluginInstallException(
            "Checksum verification failed for " + metadata.getCoordinate());
      }
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new PluginInstallException("Failed to verify checksum", e);
    }
  }

  /** Rollback installation on failure. */
  private void rollback(Path versionDir, String pluginId) {
    try {
      // Delete version directory
      if (Files.exists(versionDir)) {
        Files.walk(versionDir)
            .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException ignored) {
                    // Best effort
                  }
                });
      }

      // Remove from installed.json
      Map<String, PluginMetadata> installed = storageManager.loadInstalled();
      installed.remove(pluginId.toLowerCase());
      storageManager.saveInstalled(installed);

    } catch (IOException ignored) {
      // Best effort rollback
    }
  }
}
