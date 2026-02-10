package io.jafar.shell.plugin;

import io.jafar.shell.plugin.PluginRegistry.PluginDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves plugin dependencies using topological sort with cycle detection.
 *
 * <p>Supports:
 *
 * <ul>
 *   <li><b>depends</b>: Hard dependencies that must be installed first
 *   <li><b>recommends</b>: Soft dependencies suggested during install
 *   <li><b>provides</b>: Capabilities for abstract dependency satisfaction
 * </ul>
 */
final class DependencyResolver {
  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

  private final PluginRegistry registry;
  private final Map<String, PluginMetadata> installedPlugins;

  // Cache of capability -> plugins providing it
  private Map<String, Set<String>> capabilityProviders;

  DependencyResolver(PluginRegistry registry, Map<String, PluginMetadata> installedPlugins) {
    this.registry = registry;
    this.installedPlugins = installedPlugins;
    buildCapabilityIndex();
  }

  /** Build index of capability -> providing plugins. */
  private void buildCapabilityIndex() {
    capabilityProviders = new HashMap<>();
    for (Map.Entry<String, PluginDefinition> entry : registry.getAll().entrySet()) {
      String pluginId = entry.getKey();
      PluginDefinition def = entry.getValue();
      for (String capability : def.provides()) {
        capabilityProviders.computeIfAbsent(capability, k -> new HashSet<>()).add(pluginId);
      }
    }
    log.debug("Built capability index: {}", capabilityProviders);
  }

  /**
   * Resolve dependencies for installing a plugin.
   *
   * @param pluginId Plugin to install
   * @return Resolution result containing install order and recommendations
   * @throws DependencyException if dependencies cannot be resolved (e.g., cycle detected)
   */
  ResolutionResult resolve(String pluginId) throws DependencyException {
    log.debug("Resolving dependencies for: {}", pluginId);

    // Validate plugin exists
    PluginDefinition target =
        registry
            .get(pluginId)
            .orElseThrow(() -> new DependencyException("Plugin not found: " + pluginId));

    // Build dependency graph starting from target
    Set<String> toInstall = new LinkedHashSet<>();
    Set<String> recommended = new LinkedHashSet<>();

    // DFS with cycle detection
    Set<String> visiting = new HashSet<>(); // Currently in recursion stack
    Set<String> visited = new HashSet<>(); // Fully processed
    Deque<String> path = new ArrayDeque<>(); // For error reporting

    collectDependencies(pluginId, visiting, visited, path, toInstall, recommended);

    // Topological sort the dependencies
    List<String> installOrder = topologicalSort(toInstall);

    log.debug("Resolved install order: {}", installOrder);
    log.debug("Recommended plugins: {}", recommended);

    return new ResolutionResult(installOrder, new ArrayList<>(recommended));
  }

  /**
   * Recursively collect dependencies using DFS.
   *
   * @param pluginId Current plugin being processed
   * @param visiting Plugins currently in the recursion stack (for cycle detection)
   * @param visited Plugins fully processed
   * @param path Current path for error reporting
   * @param toInstall Accumulated plugins to install
   * @param recommended Accumulated recommended plugins
   * @throws DependencyException if cycle detected
   */
  private void collectDependencies(
      String pluginId,
      Set<String> visiting,
      Set<String> visited,
      Deque<String> path,
      Set<String> toInstall,
      Set<String> recommended)
      throws DependencyException {

    if (visited.contains(pluginId)) {
      return; // Already processed
    }

    if (visiting.contains(pluginId)) {
      // Cycle detected - build error message
      List<String> cycle = new ArrayList<>();
      boolean foundStart = false;
      for (String p : path) {
        if (p.equals(pluginId)) {
          foundStart = true;
        }
        if (foundStart) {
          cycle.add(p);
        }
      }
      cycle.add(pluginId); // Close the cycle
      throw new DependencyException("Circular dependency detected: " + String.join(" → ", cycle));
    }

    // Check if already installed
    if (installedPlugins.containsKey(pluginId)) {
      visited.add(pluginId);
      return;
    }

    // Get plugin definition
    PluginDefinition def =
        registry
            .get(pluginId)
            .orElseThrow(
                () -> new DependencyException("Dependency not found in registry: " + pluginId));

    // Mark as visiting
    visiting.add(pluginId);
    path.addLast(pluginId);

    // Process hard dependencies
    for (String dep : def.depends()) {
      String resolved = resolveDependency(dep);
      collectDependencies(resolved, visiting, visited, path, toInstall, recommended);
    }

    // Collect recommendations (don't recurse into them - they're optional)
    for (String rec : def.recommends()) {
      if (!installedPlugins.containsKey(rec) && registry.get(rec).isPresent()) {
        recommended.add(rec);
      }
    }

    // Mark as visited
    visiting.remove(pluginId);
    path.removeLast();
    visited.add(pluginId);

    // Add to install set
    toInstall.add(pluginId);
  }

  /**
   * Resolve a dependency, handling capability references.
   *
   * @param dependency Plugin ID or capability name
   * @return Resolved plugin ID
   * @throws DependencyException if dependency cannot be satisfied
   */
  private String resolveDependency(String dependency) throws DependencyException {
    // Direct plugin reference
    if (registry.get(dependency).isPresent()) {
      return dependency;
    }

    // Check if already installed
    if (installedPlugins.containsKey(dependency)) {
      return dependency;
    }

    // Try as capability
    Set<String> providers = capabilityProviders.get(dependency);
    if (providers != null && !providers.isEmpty()) {
      // Check if any provider is installed
      for (String provider : providers) {
        if (installedPlugins.containsKey(provider)) {
          return provider;
        }
      }
      // Return first available provider
      return providers.iterator().next();
    }

    throw new DependencyException("Cannot resolve dependency: " + dependency);
  }

  /**
   * Topological sort using Kahn's algorithm.
   *
   * @param plugins Plugins to sort
   * @return Sorted list (dependencies before dependents)
   */
  private List<String> topologicalSort(Set<String> plugins) {
    // Build in-degree map
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, Set<String>> dependents = new HashMap<>();

    for (String pluginId : plugins) {
      inDegree.putIfAbsent(pluginId, 0);
      dependents.putIfAbsent(pluginId, new HashSet<>());

      PluginDefinition def = registry.get(pluginId).orElse(null);
      if (def != null) {
        for (String dep : def.depends()) {
          if (plugins.contains(dep) || installedPlugins.containsKey(dep)) {
            String resolved = dep;
            if (!plugins.contains(dep) && installedPlugins.containsKey(dep)) {
              continue; // Already installed, skip
            }
            dependents.computeIfAbsent(resolved, k -> new HashSet<>()).add(pluginId);
            inDegree.merge(pluginId, 1, Integer::sum);
          }
        }
      }
    }

    // Kahn's algorithm
    List<String> result = new ArrayList<>();
    Deque<String> queue = new ArrayDeque<>();

    for (String pluginId : plugins) {
      if (inDegree.getOrDefault(pluginId, 0) == 0) {
        queue.add(pluginId);
      }
    }

    while (!queue.isEmpty()) {
      String current = queue.poll();
      result.add(current);

      for (String dependent : dependents.getOrDefault(current, Set.of())) {
        int newDegree = inDegree.merge(dependent, -1, Integer::sum);
        if (newDegree == 0) {
          queue.add(dependent);
        }
      }
    }

    return result;
  }

  /**
   * Check which installed plugins depend on the given plugin.
   *
   * @param pluginId Plugin to check
   * @return List of plugin IDs that depend on it
   */
  List<String> findDependents(String pluginId) {
    List<String> dependents = new ArrayList<>();

    for (Map.Entry<String, PluginMetadata> entry : installedPlugins.entrySet()) {
      String installedId = entry.getKey();
      PluginDefinition def = registry.get(installedId).orElse(null);
      if (def != null) {
        // Check direct dependency
        if (def.depends().contains(pluginId)) {
          dependents.add(installedId);
          continue;
        }

        // Check capability dependency
        PluginDefinition targetDef = registry.get(pluginId).orElse(null);
        if (targetDef != null) {
          for (String capability : targetDef.provides()) {
            if (def.depends().contains(capability)) {
              // Check if this is the only provider
              Set<String> providers = capabilityProviders.get(capability);
              if (providers != null) {
                boolean hasOtherProvider =
                    providers.stream()
                        .anyMatch(p -> !p.equals(pluginId) && installedPlugins.containsKey(p));
                if (!hasOtherProvider) {
                  dependents.add(installedId);
                  break;
                }
              }
            }
          }
        }
      }
    }

    return dependents;
  }

  /** Result of dependency resolution. */
  record ResolutionResult(List<String> installOrder, List<String> recommended) {

    /** Check if there are plugins to install (beyond what's already installed). */
    boolean hasPluginsToInstall() {
      return !installOrder.isEmpty();
    }

    /** Check if there are recommendations. */
    boolean hasRecommendations() {
      return !recommended.isEmpty();
    }
  }

  /** Exception thrown when dependency resolution fails. */
  static class DependencyException extends Exception {
    DependencyException(String message) {
      super(message);
    }
  }
}
