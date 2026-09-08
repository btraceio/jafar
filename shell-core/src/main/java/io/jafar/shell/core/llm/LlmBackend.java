package io.jafar.shell.core.llm;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * A source of model completions for the shell's LLM features.
 *
 * <p>This interface is the seam that keeps every provider SDK out of {@code shell-core}. Backends
 * are discovered with {@link ServiceLoader}, so a shell that does not ship one still compiles,
 * starts and runs every non-LLM command unchanged — {@link #discover()} simply returns empty and
 * the {@code ask} command reports that LLM support is not installed.
 *
 * <p>Nothing in this interface, or in {@link LlmRequest} and {@link LlmResponse}, is specific to a
 * provider: a request is a cacheable system prefix plus turns, and a response is text plus token
 * counts. Adapters exist for the Anthropic API and for any OpenAI-compatible endpoint (which covers
 * OpenAI itself, Ollama local and cloud, vLLM, LM Studio and the hosted gateways).
 *
 * <p>It is also the seam for the planned agentic mode. Today {@link #complete} is one request and
 * one response, which is all the {@code ask} and {@code explain} commands need. A tool-using loop
 * adds a second method here and a second implementation; nothing in the command layer, the
 * redaction path or the configuration has to move.
 */
public interface LlmBackend {

  /** Stable identifier, e.g. {@code anthropic}, {@code openai}, {@code ollama}. */
  String id();

  /** Human-readable name for diagnostics. */
  String displayName();

  /**
   * Reports whether this backend can currently serve a request, and why not when it cannot.
   *
   * <p>Called by {@code llm status} and before any request, so the user gets an actionable local
   * message — a missing key, an unreachable local server, a shadowed profile — rather than an
   * opaque error from the far end.
   */
  Readiness readiness(LlmConfig config);

  /**
   * The model this backend uses when {@code llm.model} is not set.
   *
   * <p>Each provider names its models differently and there is no sensible cross-provider default,
   * so the default belongs here rather than in {@link LlmConfig}.
   */
  String defaultModel();

  /**
   * One line telling the user how to authenticate to this backend, shown in help and when no
   * backend is ready. Returns {@code null} when the backend needs no credentials.
   */
  default String credentialHelp() {
    return null;
  }

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
   * Loads every backend on the classpath, ordered by {@link #id()} for determinism.
   *
   * <p>Discovery order is deliberately not a preference order — see {@link #select(String,
   * LlmConfig)}, which picks a backend that is actually usable rather than the alphabetically first
   * one.
   */
  static List<LlmBackend> discover() {
    List<LlmBackend> backends = new java.util.ArrayList<>();
    for (LlmBackend backend : ServiceLoader.load(LlmBackend.class)) {
      backends.add(backend);
    }
    backends.sort(java.util.Comparator.comparing(LlmBackend::id));
    return List.copyOf(backends);
  }

  /** Selects a backend by id. Exact match only; {@code auto} is not handled here. */
  static Optional<LlmBackend> byId(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return discover().stream().filter(b -> b.id().equalsIgnoreCase(id)).findFirst();
  }

  /**
   * Selects a backend by id, or picks one automatically when {@code preferredId} is {@code null},
   * blank or {@code auto}.
   *
   * <p>Automatic selection prefers a backend that is <em>ready</em> — one whose credentials or
   * local server are actually present. Taking the alphabetically first backend instead would mean
   * that installing the Anthropic adapter silently shadowed a configured local Ollama, which is
   * exactly the surprise this method exists to avoid. When none is ready, the first is returned so
   * that the caller can report its readiness detail and remedy rather than a bare "no backend".
   */
  static Optional<LlmBackend> select(String preferredId, LlmConfig config) {
    List<LlmBackend> backends = discover();
    if (backends.isEmpty()) {
      return Optional.empty();
    }
    if (preferredId != null && !preferredId.isBlank() && !"auto".equalsIgnoreCase(preferredId)) {
      return backends.stream().filter(b -> b.id().equalsIgnoreCase(preferredId)).findFirst();
    }
    return backends.stream()
        .filter(b -> b.readiness(config).ready())
        .findFirst()
        .or(() -> Optional.of(backends.get(0)));
  }
}
