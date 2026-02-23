package io.jafar.shell.plugin;

import java.time.Instant;

/**
 * Metadata for an installed or available plugin.
 *
 * @param groupId Maven groupId (e.g., "io.btrace")
 * @param artifactId Maven artifactId (e.g., "jfr-shell-jdk")
 * @param version Semantic version (e.g., "0.9.0")
 * @param repository Repository ID ("central", "snapshots", "local")
 * @param installedAt Timestamp when plugin was installed (null if not installed)
 */
public record PluginMetadata(
    String groupId, String artifactId, String version, String repository, Instant installedAt) {

  /**
   * Create metadata for an available (not installed) plugin.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @param repository Repository ID
   * @return Plugin metadata
   */
  public static PluginMetadata available(
      String groupId, String artifactId, String version, String repository) {
    return new PluginMetadata(groupId, artifactId, version, repository, null);
  }

  /**
   * Create metadata for an installed plugin.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @param repository Repository ID
   * @param installedAt Installation timestamp
   * @return Plugin metadata
   */
  public static PluginMetadata installed(
      String groupId, String artifactId, String version, String repository, Instant installedAt) {
    return new PluginMetadata(groupId, artifactId, version, repository, installedAt);
  }

  /** Check if this plugin is installed. */
  public boolean isInstalled() {
    return installedAt != null;
  }

  /** Get Maven coordinate string (groupId:artifactId:version). */
  public String getCoordinate() {
    return groupId + ":" + artifactId + ":" + version;
  }
}
