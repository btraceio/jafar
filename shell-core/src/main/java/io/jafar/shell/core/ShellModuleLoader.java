package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility for discovering and initializing {@link ShellModule} instances via ServiceLoader.
 *
 * <p>A module jar can be broken in the same ways a backend or LLM-provider jar can be: the provider
 * class can be missing, its static initializer can throw, or it can answer {@code getId()}/{@code
 * getPriority()}/{@code initialize()} with something the shell cannot use. Such a module is
 * skipped, not allowed to take the shell down with it — one bad format jar must not take the others
 * with it, and must not be silently swallowed either. Skipped providers are logged at WARN and
 * reported via {@link #lastDiscoveryFailures()}.
 */
public final class ShellModuleLoader {
  private static final Logger LOG = LoggerFactory.getLogger(ShellModuleLoader.class);

  private static volatile List<Failure> lastDiscoveryFailures = List.of();

  /** A module provider that was skipped during discovery, and why. */
  public record Failure(String provider, String detail) {
    @Override
    public String toString() {
      return provider + " (" + detail + ")";
    }
  }

  /** Loads, initializes, and returns all available modules sorted by priority (highest first). */
  public static List<ShellModule> loadAll() {
    return loadAll(threadContextLoader());
  }

  /** Visible for tests: same as {@link #loadAll()} but with an explicit class loader. */
  static List<ShellModule> loadAll(ClassLoader loader) {
    record Vetted(ShellModule module, int priority) {}

    List<ShellModule> loaded = new ArrayList<>();
    List<Vetted> vetted = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();

    Iterator<ShellModule> it = ServiceLoader.load(ShellModule.class, loader).iterator();
    while (true) {
      final ShellModule module;
      try {
        if (!it.hasNext()) {
          break;
        }
        module = it.next();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        failures.add(new Failure(providerNameThatFailed(t), describe(t)));
        continue;
      }

      String providerName = providerName(module);

      String id;
      try {
        id = module.getId();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        addFailure(failures, providerName, detail("getId()", t));
        continue;
      }
      if (id == null || id.isBlank()) {
        failures.add(new Failure(providerName, "getId() returned a blank id"));
        continue;
      }

      int priority;
      try {
        priority = module.getPriority();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        addFailure(failures, providerName, detail("getPriority()", t));
        continue;
      }

      try {
        module.initialize();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw (Error) t;
        }
        addFailure(failures, providerName, detail("initialize()", t));
        continue;
      }

      LOG.debug("Loaded module: {} ({})", safeDisplayName(module), id);
      vetted.add(new Vetted(module, priority));
    }

    vetted.sort(Comparator.comparingInt(Vetted::priority).reversed());
    for (Vetted v : vetted) {
      loaded.add(v.module());
    }
    lastDiscoveryFailures = List.copyOf(failures);
    if (!failures.isEmpty()) {
      LOG.warn("Skipped {} broken shell module(s): {}", failures.size(), summarize(failures));
    }
    return loaded;
  }

  /**
   * The modules that were skipped during the most recent {@link #loadAll()}, and why. Empty when
   * every installed module is healthy.
   */
  public static List<Failure> lastDiscoveryFailures() {
    return lastDiscoveryFailures;
  }

  /** VirtualMachineError and ThreadDeath must never be swallowed; everything else can be. */
  private static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError || t instanceof ThreadDeath;
  }

  private static ClassLoader threadContextLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return cl != null ? cl : ShellModuleLoader.class.getClassLoader();
  }

  private static String providerNameThatFailed(Throwable t) {
    // ServiceLoader reports the offending class as "... provider com.foo.Bar ..." in its message.
    String m = t.getMessage();
    if (m == null) {
      return "(unloadable provider)";
    }
    int i = m.indexOf("provider ");
    if (i < 0) {
      i = m.indexOf("Provider ");
    }
    if (i < 0) {
      return "(unloadable provider)";
    }
    int start = i + "provider ".length();
    int end = m.indexOf(' ', start);
    return m.substring(start, end < 0 ? m.length() : end);
  }

  private static void addFailure(List<Failure> failures, String provider, String detail) {
    failures.add(new Failure(provider, detail));
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    String s =
        t.getClass().getSimpleName()
            + (message == null ? "" : ": " + message.replaceAll("\\s+", " ").trim());
    Throwable cause = t.getCause();
    if (cause != null && cause != t) {
      s +=
          " (caused by "
              + cause.getClass().getSimpleName()
              + (cause.getMessage() == null
                  ? ""
                  : ": " + cause.getMessage().replaceAll("\\s+", " ").trim())
              + ")";
    }
    return s.length() > 300 ? s.substring(0, 297) + "..." : s;
  }

  private static String detail(String call, Throwable t) {
    return call + " -> " + describe(t);
  }

  private static String providerName(Object provider) {
    try {
      return provider.getClass().getName();
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      return "(unknown)";
    }
  }

  private static String safeDisplayName(ShellModule module) {
    try {
      return String.valueOf(module.getDisplayName());
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      return "unknown";
    }
  }

  private static String summarize(List<Failure> failures) {
    StringBuilder sb = new StringBuilder();
    int shown = Math.min(failures.size(), 5);
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        sb.append("; ");
      }
      sb.append(failures.get(i));
    }
    if (failures.size() > shown) {
      sb.append("; … and ").append(failures.size() - shown).append(" more");
    }
    return sb.toString();
  }

  private ShellModuleLoader() {}
}
