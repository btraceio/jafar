package io.jafar.shell.backend;

import io.jafar.shell.plugin.PluginManager;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers and manages JFR backends via ServiceLoader. Supports selection via environment
 * variable, system property, or automatic priority-based selection.
 *
 * <p>Backend selection order:
 *
 * <ol>
 *   <li>JFRSHELL_BACKEND environment variable
 *   <li>jfr.shell.backend system property
 *   <li>Highest priority backend
 * </ol>
 */
public final class BackendRegistry {

  private static final String ENV_BACKEND = "JFRSHELL_BACKEND";
  private static final String PROP_BACKEND = "jfr.shell.backend";

  private final Map<String, JfrBackend> backends = new LinkedHashMap<>();
  private final JfrBackend current;

  private BackendRegistry() {
    discoverBackends();
    this.current = selectInitialBackend();
  }

  private static final class Holder {
    static final BackendRegistry INSTANCE = new BackendRegistry();
  }

  /**
   * Returns the singleton registry instance.
   *
   * @return the backend registry
   */
  public static BackendRegistry getInstance() {
    return Holder.INSTANCE;
  }

  private void discoverBackends() {
    // Use plugin-aware ClassLoader to discover backends from both built-in and plugin JARs
    // Falls back to thread context classloader for testing when PluginManager isn't initialized
    ClassLoader classLoader;
    try {
      classLoader = PluginManager.getInstance().getPluginClassLoader();
    } catch (IllegalStateException e) {
      // PluginManager not initialized - fall back to thread context classloader (for tests)
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = BackendRegistry.class.getClassLoader();
      }
    }
    ServiceLoader<JfrBackend> loader = ServiceLoader.load(JfrBackend.class, classLoader);
    for (JfrBackend backend : loader) {
      backends.put(backend.getId().toLowerCase(Locale.ROOT), backend);
    }
  }

  private JfrBackend selectInitialBackend() {
    // 1. Check environment variable
    String envBackend = System.getenv(ENV_BACKEND);
    if (envBackend != null && !envBackend.isBlank()) {
      JfrBackend backend = backends.get(envBackend.toLowerCase(Locale.ROOT));
      if (backend != null) {
        return backend;
      }
    }

    // 2. Check system property
    String propBackend = System.getProperty(PROP_BACKEND);
    if (propBackend != null && !propBackend.isBlank()) {
      JfrBackend backend = backends.get(propBackend.toLowerCase(Locale.ROOT));
      if (backend != null) {
        return backend;
      }
    }

    // 3. Select by highest priority
    return backends.values().stream()
        .max(Comparator.comparingInt(JfrBackend::getPriority))
        .orElseThrow(() -> new IllegalStateException("No JFR backends available"));
  }

  /**
   * Returns the current backend.
   *
   * @return the selected backend
   */
  public JfrBackend getCurrent() {
    return current;
  }

  /**
   * Get a backend by ID.
   *
   * @param backendId the backend ID (case-insensitive)
   * @return optional containing the backend if found
   */
  public Optional<JfrBackend> get(String backendId) {
    return Optional.ofNullable(backends.get(backendId.toLowerCase(Locale.ROOT)));
  }

  /**
   * List all available backends.
   *
   * @return unmodifiable collection of backends
   */
  public Collection<JfrBackend> listAll() {
    return Collections.unmodifiableCollection(backends.values());
  }

  /**
   * List backends supporting a specific capability.
   *
   * @param capability the required capability
   * @return list of backends supporting the capability
   */
  public List<JfrBackend> listWithCapability(BackendCapability capability) {
    return backends.values().stream().filter(b -> b.supports(capability)).toList();
  }

  /**
   * Check if a specific backend is available.
   *
   * @param backendId the backend ID (case-insensitive)
   * @return true if the backend is available
   */
  public boolean isAvailable(String backendId) {
    return backends.containsKey(backendId.toLowerCase(Locale.ROOT));
  }
}
