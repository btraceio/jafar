package io.jafar.shell.backend;

import io.jafar.shell.core.discovery.ProviderDiscovery;
import io.jafar.shell.plugin.PluginManager;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);

  private final Map<String, JfrBackend> backends = new LinkedHashMap<>();
  private volatile List<ProviderDiscovery.Failure> discoveryFailures = List.of();
  private volatile JfrBackend current;

  private BackendRegistry() {
    discoverBackends();
    // Defer backend selection to getCurrent() to allow system property to be set after
    // initialization
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

  /**
   * Re-run backend discovery after new plugins have been installed and the PluginManager has been
   * reinitialized. Clears the current backend selection so it will be re-evaluated on next access.
   */
  public void rediscover() {
    synchronized (this) {
      backends.clear();
      current = null;
      discoverBackends();
    }
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
    LoadedBackends loaded = loadBackends(classLoader);
    backends.clear();
    for (int i = 0; i < loaded.providers().size(); i++) {
      // Ids were vetted during loadBackends; reuse them instead of calling getId() again.
      backends.put(loaded.ids().get(i).toLowerCase(Locale.ROOT), loaded.providers().get(i));
    }
    discoveryFailures = loaded.failures();
    if (!discoveryFailures.isEmpty()) {
      log.warn(
          "Skipped {} broken JFR backend provider(s): {}",
          discoveryFailures.size(),
          ProviderDiscovery.summarize(discoveryFailures));
    }
  }

  /** Loaded backends paired with their vetted ids, plus the providers that had to be skipped. */
  record LoadedBackends(
      List<JfrBackend> providers, List<String> ids, List<ProviderDiscovery.Failure> failures) {}

  /**
   * Loads and vets backend providers from {@code loader}. Never throws for a broken provider: it is
   * skipped and reported in the returned failures. A provider whose {@code getId()} or {@code
   * getPriority()} throws — or whose id is blank — is dropped, because map insertion and selection
   * cannot work without them.
   */
  static LoadedBackends loadBackends(ClassLoader loader) {
    ProviderDiscovery.Outcome<JfrBackend> raw = ProviderDiscovery.loadAll(JfrBackend.class, loader);
    List<ProviderDiscovery.Failure> failures = new java.util.ArrayList<>(raw.failures());
    List<JfrBackend> usable = new java.util.ArrayList<>();
    List<String> ids = new java.util.ArrayList<>();
    for (JfrBackend backend : raw.providers()) {
      String name = ProviderDiscovery.providerName(backend);
      String id = ProviderDiscovery.call(backend::getId, null, failures, name, "getId()");
      if (id == null) {
        continue; // getId() threw; failure already recorded
      }
      if (id.isBlank()) {
        failures.add(new ProviderDiscovery.Failure(name, "getId() returned a blank id"));
        continue;
      }
      Integer priority =
          ProviderDiscovery.call(backend::getPriority, null, failures, name, "getPriority()");
      if (priority == null) {
        continue; // getPriority() threw; failure already recorded
      }
      usable.add(backend);
      ids.add(id);
    }
    return new LoadedBackends(List.copyOf(usable), List.copyOf(ids), List.copyOf(failures));
  }

  /**
   * Providers that were skipped during the last discovery because they failed to load or answer
   * basic calls. Empty when every installed provider is healthy.
   */
  public List<ProviderDiscovery.Failure> discoveryFailures() {
    return discoveryFailures;
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

    // 3. Select by highest priority. getPriority() is called again here (loadBackends only
    // vetted it), so a provider that breaks between the two calls sorts last instead of killing
    // selection for every other backend.
    return backends.values().stream()
        .max(
            Comparator.comparingInt(
                (JfrBackend backend) -> {
                  Integer priority = ProviderDiscovery.call(backend::getPriority, null);
                  if (priority == null) {
                    log.warn(
                        "Backend '{}' failed the getPriority() re-check; sorting it last",
                        backend.getClass().getName());
                    return Integer.MIN_VALUE;
                  }
                  return priority;
                }))
        .orElseThrow(() -> new IllegalStateException(noBackendsMessage()));
  }

  private String noBackendsMessage() {
    if (discoveryFailures.isEmpty()) {
      return "No JFR backends available";
    }
    return "No JFR backends available — "
        + discoveryFailures.size()
        + " installed provider(s) failed to load: "
        + ProviderDiscovery.summarize(discoveryFailures)
        + ". Reinstall or remove the broken jar (plugins live in ~/.jfr-shell/plugins).";
  }

  /**
   * Returns the current backend. Selection is deferred until first call to allow system properties
   * to be set after registry initialization.
   *
   * @return the selected backend
   */
  public JfrBackend getCurrent() {
    JfrBackend result = current;
    if (result == null) {
      synchronized (this) {
        result = current;
        if (result == null) {
          current = result = selectInitialBackend();
        }
      }
    }
    return result;
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
    return backends.values().stream()
        .filter(
            b -> {
              try {
                return b.supports(capability);
              } catch (RuntimeException | LinkageError e) {
                // supports() is a default method over getCapabilities() — a broken provider
                // must not take the query down with it.
                log.warn(
                    "Backend '{}' failed the {} capability check: {}",
                    b.getClass().getName(),
                    capability,
                    e.toString());
                return false;
              }
            })
        .toList();
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
