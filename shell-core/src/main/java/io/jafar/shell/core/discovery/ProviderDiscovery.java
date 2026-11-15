package io.jafar.shell.core.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Shared guard for {@link ServiceLoader}-based plugin discovery.
 *
 * <p>An installed provider jar can be broken in many ways: the provider class is missing, its
 * static initializer throws, it is not a subtype of the SPI, it has no public no-arg constructor,
 * or its {@code META-INF/services} file is unreadable. {@code ServiceLoader} reports all of these
 * by throwing {@link ServiceConfigurationError} — an {@code Error}, not an {@code Exception} — so
 * it sails straight past every {@code catch (Exception)} in the command layer and takes the whole
 * command down with it. Provider methods called after discovery ({@code id()}, {@code readiness()},
 * …) can likewise fail with a raw {@link RuntimeException} or {@link LinkageError} when the jar is
 * only half-loadable (a lazily-linked dependency is missing).
 *
 * <p>Discovery through this class never throws for a broken provider: the provider is skipped and
 * the failure is recorded, so healthy providers on the same classpath are still found. What was
 * skipped and why is reported, never silently swallowed — a fallback that hides a misconfiguration
 * is a bug. Only failures no provider could have caused ({@link VirtualMachineError}, {@link
 * ThreadDeath}) propagate.
 */
public final class ProviderDiscovery {

  /** Cap on recorded failures; beyond this a single count is kept instead. */
  private static final int MAX_FAILURES = 50;

  private ProviderDiscovery() {}

  /** A provider that could not be used, and why. */
  public record Failure(String provider, String detail) {
    @Override
    public String toString() {
      return provider + " (" + detail + ")";
    }
  }

  /**
   * The outcome of one discovery pass: the usable providers and the ones that had to be skipped.
   */
  public record Outcome<T>(List<T> providers, List<Failure> failures) {
    public Outcome {
      providers = List.copyOf(providers);
      failures = List.copyOf(failures);
    }
  }

  /**
   * Iterates the service loader once, collecting what loads and what does not.
   *
   * <p>Order follows the class path, as the unguarded iteration did. A failure to load one provider
   * does not stop the scan: providers listed after the broken one — in the same jar or in later
   * ones — are still returned. Failures are capped so a pathological classpath cannot grow the
   * failure list without bound.
   */
  public static <T> Outcome<T> loadAll(Class<T> spi, ClassLoader loader) {
    List<T> providers = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();
    int dropped = 0;
    var it = ServiceLoader.load(spi, loader).iterator();
    while (true) {
      final T provider;
      try {
        if (!it.hasNext()) {
          break;
        }
        provider = it.next();
      } catch (Throwable t) {
        if (isFatal(t)) {
          throw sneaky(t);
        }
        dropped++;
        if (failures.size() < MAX_FAILURES) {
          failures.add(failure(t));
        }
        continue;
      }
      if (provider != null) {
        providers.add(provider);
      }
    }
    if (dropped > failures.size()) {
      failures.add(
          new Failure("(more)", (dropped - failures.size()) + " further broken provider(s)"));
    }
    return new Outcome<>(providers, failures);
  }

  /**
   * Runs {@code call}; on any non-fatal failure records it in {@code failures} and returns {@code
   * fallback}.
   */
  public static <R> R call(
      Supplier<R> call, R fallback, List<Failure> failures, String provider, String what) {
    try {
      return call.get();
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw sneaky(t);
      }
      failures.add(new Failure(provider, what + ": " + describe(t)));
      return fallback;
    }
  }

  /** Runs {@code call}; on any non-fatal failure returns {@code fallback} without recording it. */
  public static <R> R call(Supplier<R> call, R fallback) {
    try {
      return call.get();
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw sneaky(t);
      }
      return fallback;
    }
  }

  /** Best-effort identification of a provider instance for failure reporting. */
  public static String providerName(Object provider) {
    try {
      return provider.getClass().getName();
    } catch (Throwable t) {
      return "(unknown)";
    }
  }

  /** One-line rendering of a throwable: {@code SimpleName: message (caused by ...)}, flattened. */
  public static String describe(Throwable t) {
    StringBuilder sb = new StringBuilder();
    appendOneLine(sb, t);
    Throwable cause = t.getCause();
    if (cause != null && cause != t) {
      sb.append(" (caused by ");
      appendOneLine(sb, cause);
      sb.append(')');
    }
    String s = sb.toString();
    return s.length() > 300 ? s.substring(0, 297) + "..." : s;
  }

  /** Formats a short list of failures for display, capping the number shown. */
  public static String summarize(List<Failure> failures) {
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

  /** True for throwables that must never be swallowed: VM errors and thread death. */
  public static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError || t instanceof ThreadDeath;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T sneaky(Throwable t) throws T {
    throw (T) t;
  }

  private static void appendOneLine(StringBuilder sb, Throwable t) {
    sb.append(t.getClass().getSimpleName());
    String message = t.getMessage();
    if (message != null && !message.isBlank()) {
      sb.append(": ").append(message.replaceAll("\\s+", " ").trim());
    }
  }

  /** Builds a failure from an error raised by the loader itself. */
  private static Failure failure(Throwable t) {
    String message = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ").trim();
    String provider = null;
    if (t instanceof ServiceConfigurationError) {
      // JDK messages embed the offending class name, e.g. "Svc: Provider foo.Bar could not be
      // instantiated" or "Svc: jar:file:...!/META-INF/services/x:1: Illegal provider-class name:
      // y".
      int i = message.indexOf("Provider ");
      if (i >= 0) {
        int start = i + "Provider ".length();
        int end = message.indexOf(' ', start);
        provider = message.substring(start, end < 0 ? message.length() : end);
      }
    }
    return new Failure(provider != null ? provider : "(unidentified provider)", describe(t));
  }
}
