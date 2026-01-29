package io.jafar.shell.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks for plugin updates and determines update type based on semantic versioning.
 *
 * <p>Update policy:
 *
 * <ul>
 *   <li>PATCH (0.9.0 → 0.9.1): Auto-install
 *   <li>MINOR (0.9.0 → 0.10.0): Notify only
 *   <li>MAJOR (0.9.0 → 1.0.0): Notify only
 * </ul>
 */
final class UpdateChecker {
  private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);
  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*");

  private final PluginRegistry registry;

  UpdateChecker(PluginRegistry registry) {
    this.registry = registry;
  }

  /**
   * Check for available updates for all installed plugins.
   *
   * @param installed Map of plugin ID to installed metadata
   * @return List of available updates
   */
  List<PluginUpdate> checkForUpdates(Map<String, PluginMetadata> installed) {
    log.debug("Checking for updates for {} installed plugins", installed.size());
    List<PluginUpdate> updates = new ArrayList<>();
    registry.refreshIfNeeded();

    for (Map.Entry<String, PluginMetadata> entry : installed.entrySet()) {
      String pluginId = entry.getKey();
      PluginMetadata currentMetadata = entry.getValue();

      // Look up latest version in registry
      registry
          .get(pluginId)
          .ifPresent(
              definition -> {
                String currentVersion = currentMetadata.version();
                String latestVersion = definition.version();

                log.debug(
                    "Plugin '{}': current={}, latest={}", pluginId, currentVersion, latestVersion);

                if (latestVersion != null && !currentVersion.equals(latestVersion)) {
                  UpdateType updateType = determineUpdateType(currentVersion, latestVersion);
                  log.debug("Plugin '{}' has {} update available", pluginId, updateType);
                  updates.add(
                      new PluginUpdate(pluginId, currentVersion, latestVersion, updateType));
                }
              });
    }

    log.info("Found {} plugin updates", updates.size());
    return updates;
  }

  /**
   * Determine the type of update based on semantic versioning.
   *
   * @param current Current version
   * @param latest Latest version
   * @return Update type (PATCH, MINOR, or MAJOR)
   */
  private UpdateType determineUpdateType(String current, String latest) {
    SemanticVersion currentVer = parseVersion(current);
    SemanticVersion latestVer = parseVersion(latest);

    if (currentVer == null || latestVer == null) {
      // Cannot parse - treat as MAJOR to avoid auto-install
      return UpdateType.MAJOR;
    }

    if (latestVer.major > currentVer.major) {
      return UpdateType.MAJOR;
    } else if (latestVer.minor > currentVer.minor) {
      return UpdateType.MINOR;
    } else if (latestVer.patch > currentVer.patch) {
      return UpdateType.PATCH;
    } else {
      // Same or older version
      return UpdateType.PATCH;
    }
  }

  /** Parse semantic version string (major.minor.patch). */
  private SemanticVersion parseVersion(String version) {
    Matcher matcher = VERSION_PATTERN.matcher(version);
    if (!matcher.matches()) {
      return null;
    }

    try {
      int major = Integer.parseInt(matcher.group(1));
      int minor = Integer.parseInt(matcher.group(2));
      int patch = Integer.parseInt(matcher.group(3));
      return new SemanticVersion(major, minor, patch);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Semantic version components. */
  private record SemanticVersion(int major, int minor, int patch) {}

  /** Type of version update. */
  enum UpdateType {
    PATCH, // 0.9.0 → 0.9.1 (auto-install)
    MINOR, // 0.9.0 → 0.10.0 (notify)
    MAJOR // 0.9.0 → 1.0.0 (notify)
  }

  /** Information about an available plugin update. */
  record PluginUpdate(
      String pluginId, String currentVersion, String availableVersion, UpdateType type) {

    /** Check if this update should be auto-installed. */
    boolean shouldAutoInstall() {
      return type == UpdateType.PATCH;
    }

    @Override
    public String toString() {
      String typeLabel =
          switch (type) {
            case PATCH -> "patch update";
            case MINOR -> "minor update";
            case MAJOR -> "major update";
          };
      return String.format(
          "Plugin '%s': %s → %s (%s)", pluginId, currentVersion, availableVersion, typeLabel);
    }
  }
}
