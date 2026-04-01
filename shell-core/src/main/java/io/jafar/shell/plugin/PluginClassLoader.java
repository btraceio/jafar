package io.jafar.shell.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Custom ClassLoader for loading plugin JAR files.
 *
 * <p>This ClassLoader extends URLClassLoader to load plugin JARs from the plugin storage directory.
 * It enables ServiceLoader to discover backend implementations from installed plugins.
 */
final class PluginClassLoader extends URLClassLoader {

  /**
   * Create a new PluginClassLoader with the specified plugin JARs.
   *
   * @param pluginJars List of paths to plugin JAR files
   * @param parent Parent ClassLoader for delegation
   */
  PluginClassLoader(List<Path> pluginJars, ClassLoader parent) {
    super(toURLs(pluginJars), parent);
  }

  /**
   * Convert a list of file paths to URLs for URLClassLoader.
   *
   * @param paths List of JAR file paths
   * @return Array of URLs
   * @throws RuntimeException if path cannot be converted to URL
   */
  private static URL[] toURLs(List<Path> paths) {
    return paths.stream()
        .map(
            path -> {
              try {
                return path.toUri().toURL();
              } catch (MalformedURLException e) {
                throw new RuntimeException("Invalid plugin JAR path: " + path, e);
              }
            })
        .toArray(URL[]::new);
  }
}
