package io.jafar.shell.core.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Settings for the shell's LLM features.
 *
 * <p>Resolution order, first match wins:
 *
 * <ol>
 *   <li>a shell variable, so {@code set llm.model = ...} works and {@code vars} shows it
 *   <li>an environment variable, which is the practical route in CI
 *   <li>the settings file — see {@link LlmSettingsFile} — which is where a long-lived credential
 *       belongs, because a file only its owner can read beats a variable every child process
 *       inherits
 *   <li>the defaults here
 * </ol>
 *
 * <p>Every default is chosen so that the safe behaviour is the one you get without configuring
 * anything. {@link #sourceOf} reports which layer answered, because a setting coming from somewhere
 * unexpected is the hardest kind of misconfiguration to see.
 */
public final class LlmConfig {

  /**
   * How many times a query that fails to parse is sent back for correction.
   *
   * <p>One retry, because the second attempt sees the parser's own error message and usually fixes
   * it; a third rarely adds anything but cost. This matters most with smaller local models, which
   * produce invalid queries far more often than a frontier model does.
   */
  public static final int DEFAULT_MAX_RETRIES = 1;

  /**
   * Output ceiling for a single {@code ask}, before anything is known about the model.
   *
   * <p>Small on purpose. A query and one line of rationale really is small, and a ceiling is what
   * caps the damage when a model loops — you are billed for what it generates, so a high ceiling
   * everywhere makes a runaway eight times more expensive.
   *
   * <p>It is wrong for a reasoning model, which spends this budget thinking before it writes
   * anything. Rather than guess from the model's name — a list that would be stale within a month —
   * {@link LlmService} escalates when the reply itself says it was cut off mid-thought, and
   * remembers that for the rest of the session. See {@link #MAX_TOKENS_WHEN_THINKING}.
   */
  public static final int DEFAULT_MAX_TOKENS = 2048;

  /**
   * The ceiling used once a model has shown that it reasons before answering.
   *
   * <p>Reached by escalation, never by default: the evidence is a reply that stopped on its token
   * limit without producing a query.
   */
  public static final int MAX_TOKENS_WHEN_THINKING = 16384;

  /**
   * Rows of a query result shown to the model by {@code explain}. Results are the one place where
   * recording-derived data enters the prompt, so the cap is both a cost control and a blast-radius
   * control.
   */
  public static final int DEFAULT_MAX_ROWS = 50;

  /**
   * Event fields redacted before anything leaves the process, unless the user overrides.
   *
   * <p>These are the fields most likely to carry deployment or customer detail in a real recording:
   * filesystem layout, network peers, and the free-text of exception messages. Class and method
   * names are deliberately *not* redacted by default — without them the model cannot answer a
   * performance question at all, and they are the least sensitive part of a recording.
   */
  public static final List<String> DEFAULT_REDACT_FIELDS =
      List.of("path", "address", "host", "hostname", "message", "description", "value", "string");

  private final Function<String, String> lookup;
  private final java.util.function.Supplier<java.util.Optional<LlmSettingsFile>> settingsFile;

  /**
   * @param lookup resolves a setting name (e.g. {@code llm.model}) to a value, or {@code null}
   */
  public LlmConfig(Function<String, String> lookup) {
    this(lookup, LlmSettingsFile::find);
  }

  /** Package-private seam: lets a test supply a settings file without setting an env var. */
  LlmConfig(
      Function<String, String> lookup,
      java.util.function.Supplier<java.util.Optional<LlmSettingsFile>> settingsFile) {
    this.lookup = lookup == null ? name -> null : lookup;
    this.settingsFile = settingsFile;
  }

  /** A config backed only by environment variables and defaults. */
  public static LlmConfig fromEnvironment() {
    return new LlmConfig(name -> null);
  }

  /** Whether LLM commands are permitted at all. Set {@code llm.enabled = false} to disable. */
  public boolean enabled() {
    return !"false".equalsIgnoreCase(resolve("llm.enabled", "LLM_ENABLED", "true"));
  }

  /**
   * The configured model, or {@code null} to let the backend choose.
   *
   * <p>There is no cross-provider default worth having — model names are provider-specific — so the
   * fallback lives on {@link LlmBackend#defaultModel()}.
   */
  public String model() {
    return resolve("llm.model", "JAFAR_LLM_MODEL", null);
  }

  /** The model to use with a given backend: the configured one, else that backend's default. */
  public String modelFor(LlmBackend backend) {
    String configured = model();
    return configured != null ? configured : backend.defaultModel();
  }

  /**
   * Base URL override for backends that speak to a configurable endpoint.
   *
   * <p>This is what makes one OpenAI-compatible adapter cover OpenAI, Ollama, vLLM, LM Studio and
   * the hosted gateways: they differ by URL, not by protocol.
   */
  public String baseUrl() {
    return resolve("llm.base-url", "JAFAR_LLM_BASE_URL", null);
  }

  /**
   * An API key supplied through shell configuration rather than the environment.
   *
   * <p>Prefer the provider's environment variable. This exists for endpoints that have no
   * conventional variable, and {@code llm status} never prints its value.
   */
  public String apiKey() {
    return resolve("llm.api-key", "JAFAR_LLM_API_KEY", null);
  }

  /** Request timeout in seconds. Local models on modest hardware can be slow to first token. */
  public int timeoutSeconds() {
    return intValue("llm.timeout", "JAFAR_LLM_TIMEOUT", 120);
  }

  /** How many times an invalid query is sent back for correction. Zero disables the retry. */
  public int maxRetries() {
    String value = resolve("llm.max-retries", "JAFAR_LLM_MAX_RETRIES", null);
    if (value == null) {
      return DEFAULT_MAX_RETRIES;
    }
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(0, Math.min(parsed, 3));
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_RETRIES;
    }
  }

  /** Backend id, or {@code auto} to take the first discovered one. */
  public String backendId() {
    return resolve("llm.backend", "JAFAR_LLM_BACKEND", "auto");
  }

  /**
   * How many moves one {@code analyze} may make.
   *
   * <p>Six is enough for a real investigation — look, narrow, confirm, conclude — and small enough
   * that a loop which learns nothing stops before it costs much. The model is told the remaining
   * count each turn, so the cap shapes its behaviour rather than merely truncating it.
   */
  public int maxSteps() {
    int value = intValue("llm.max-steps", "JAFAR_LLM_MAX_STEPS", 6);
    return Math.max(1, Math.min(value, 20));
  }

  /**
   * Token ceiling for a whole {@code analyze} run, across every step. Zero means no cap.
   *
   * <p>The step cap alone does not bound spend: a step that sends fifty rows of a wide result costs
   * many times one that sends a single number. This is the backstop that makes an investigation
   * safe to start without watching it.
   */
  public long maxTotalTokens() {
    String value = resolve("llm.max-total-tokens", "JAFAR_LLM_MAX_TOTAL_TOKENS", null);
    if (value == null) {
      return 200_000;
    }
    try {
      return Math.max(0, Long.parseLong(value));
    } catch (NumberFormatException e) {
      return 200_000;
    }
  }

  public int maxTokens() {
    return intValue("llm.max-tokens", "JAFAR_LLM_MAX_TOKENS", DEFAULT_MAX_TOKENS);
  }

  /**
   * Characters of one analysis result shown to the model.
   *
   * <p>A full {@code diagnose} with its sub-analyses embedded dwarfs a query result and would
   * swallow the step budget in a single move. Capped in characters rather than rows because these
   * are nested structures.
   */
  public int maxAnalysisChars() {
    return intValue("llm.max-analysis-chars", "JAFAR_LLM_MAX_ANALYSIS_CHARS", 6000);
  }

  public int maxRows() {
    return intValue("llm.max-rows", "JAFAR_LLM_MAX_ROWS", DEFAULT_MAX_ROWS);
  }

  /**
   * Whether {@code ask} runs the generated query automatically. Queries are read-only, so the
   * default is to run; {@code llm.confirm = true} makes the shell print the query and stop.
   */
  public boolean confirmBeforeRun() {
    return "true".equalsIgnoreCase(resolve("llm.confirm", "JAFAR_LLM_CONFIRM", "false"));
  }

  /** Whether redaction is applied on the egress path. Off only if a user explicitly says so. */
  public boolean redactionEnabled() {
    return !"false".equalsIgnoreCase(resolve("llm.redact", "JAFAR_LLM_REDACT", "true"));
  }

  /**
   * Field names redacted before egress. {@code llm.redact-fields} replaces the default list; a
   * leading {@code +} adds to it instead.
   */
  public Set<String> redactFields() {
    Set<String> fields = new LinkedHashSet<>(DEFAULT_REDACT_FIELDS);
    String configured = resolve("llm.redact-fields", "JAFAR_LLM_REDACT_FIELDS", null);
    if (configured == null || configured.isBlank()) {
      return fields;
    }
    String spec = configured.trim();
    boolean additive = spec.startsWith("+");
    if (additive) {
      spec = spec.substring(1);
    } else {
      fields.clear();
    }
    for (String field : spec.split(",")) {
      String trimmed = field.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty()) {
        fields.add(trimmed);
      }
    }
    return fields;
  }

  private String resolve(String setting, String envVar, String fallback) {
    String value = lookup.apply(setting);
    if (value != null && !value.isBlank()) {
      return value.trim();
    }
    value = System.getenv(envVar);
    if (value != null && !value.isBlank()) {
      return value.trim();
    }
    value = settingsFile.get().map(file -> file.get(setting)).orElse(null);
    if (value != null && !value.isBlank()) {
      return value.trim();
    }
    return fallback;
  }

  /** Where a setting's value came from. Reported by {@code llm status}. */
  public enum Source {
    /** A {@code set} command in this shell. */
    SHELL_VARIABLE,
    /** An environment variable. */
    ENVIRONMENT,
    /** The settings file. */
    SETTINGS_FILE,
    /** Nothing configured it; the built-in default applies. */
    DEFAULT
  }

  /**
   * Which layer supplies {@code setting}.
   *
   * <p>Worth reporting because the failure this prevents is silent: a stale environment variable
   * quietly overriding the settings file looks identical to the file not being read at all.
   */
  public Source sourceOf(String setting, String envVar) {
    String value = lookup.apply(setting);
    if (value != null && !value.isBlank()) {
      return Source.SHELL_VARIABLE;
    }
    value = System.getenv(envVar);
    if (value != null && !value.isBlank()) {
      return Source.ENVIRONMENT;
    }
    value = settingsFile.get().map(file -> file.get(setting)).orElse(null);
    if (value != null && !value.isBlank()) {
      return Source.SETTINGS_FILE;
    }
    return Source.DEFAULT;
  }

  /** The settings file in use, if there is one. */
  public java.util.Optional<LlmSettingsFile> settingsFile() {
    return settingsFile.get();
  }

  private int intValue(String setting, String envVar, int fallback) {
    String value = resolve(setting, envVar, null);
    if (value == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** Renders the effective settings, for {@code llm status}. */
  public String describe() {
    return """
        enabled     : %s
        backend     : %s
        model       : %s
        base url    : %s
        max tokens  : %d
        max rows    : %d
        retries     : %d
        timeout     : %ds
        confirm     : %s
        redaction   : %s
        redact keys : %s"""
        .formatted(
            enabled(),
            backendId(),
            model() == null ? "(backend default)" : model(),
            baseUrl() == null ? "(backend default)" : baseUrl(),
            maxTokens(),
            maxRows(),
            maxRetries(),
            timeoutSeconds(),
            confirmBeforeRun(),
            redactionEnabled() ? "on" : "OFF",
            String.join(", ", redactFields()));
  }
}
