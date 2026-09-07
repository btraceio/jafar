package io.jafar.shell.core.llm;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * A source of model completions for the shell's LLM features.
 *
 * <p>This interface is the seam that keeps the Anthropic SDK out of {@code shell-core}. Backends
 * are discovered with {@link ServiceLoader}, so a shell that does not ship one still compiles,
 * starts and runs every non-LLM command unchanged — {@link #discover()} simply returns empty and
 * the {@code ask} command reports that LLM support is not installed.
 *
 * <p>It is also the seam for the planned agentic mode. Today {@link #complete} is one request and
 * one response, which is all the {@code ask} and {@code explain} commands need. A tool-using loop
 * adds a second method here and a second implementation; nothing in the command layer, the
 * redaction path or the configuration has to move.
 */
public interface LlmBackend {

  /** Stable identifier, e.g. {@code anthropic}. Shown by {@code llm status}. */
  String id();

  /** Human-readable name for diagnostics. */
  String displayName();

  /**
   * Reports whether this backend can currently serve a request, and why not when it cannot.
   *
   * <p>Called by {@code llm status} and before any request, so the user gets an actionable local
   * message ("no credentials — run `ant auth login` or set ANTHROPIC_API_KEY") rather than an
   * opaque 401 from the server.
   */
  Readiness readiness(LlmConfig config);

  /**
   * Performs one completion.
   *
   * @param request the prompt, already redacted by the caller
   * @return the model's reply plus usage accounting
   * @throws LlmException if the request fails
   */
  LlmResponse complete(LlmRequest request, LlmConfig config) throws LlmException;

  /** Whether the backend is ready, with a reason and a suggested remedy when it is not. */
  record Readiness(boolean ready, String detail, String remedy) {
    public static Readiness ready(String detail) {
      return new Readiness(true, detail, null);
    }

    public static Readiness notReady(String detail, String remedy) {
      return new Readiness(false, detail, remedy);
    }
  }

  /**
   * Loads every backend on the classpath, most preferred first.
   *
   * <p>Ordering is by {@link #id()} for determinism; with a single backend it does not matter, and
   * when a delegate backend is added the {@code llm.backend} setting selects explicitly rather than
   * relying on discovery order.
   */
  static List<LlmBackend> discover() {
    List<LlmBackend> backends = new java.util.ArrayList<>();
    for (LlmBackend backend : ServiceLoader.load(LlmBackend.class)) {
      backends.add(backend);
    }
    backends.sort(java.util.Comparator.comparing(LlmBackend::id));
    return List.copyOf(backends);
  }

  /**
   * Selects a backend by id, or the first discovered one when {@code preferredId} is {@code null},
   * blank or {@code auto}.
   */
  static Optional<LlmBackend> select(String preferredId) {
    List<LlmBackend> backends = discover();
    if (preferredId == null || preferredId.isBlank() || "auto".equalsIgnoreCase(preferredId)) {
      return backends.isEmpty() ? Optional.empty() : Optional.of(backends.get(0));
    }
    return backends.stream().filter(b -> b.id().equalsIgnoreCase(preferredId)).findFirst();
  }
}
