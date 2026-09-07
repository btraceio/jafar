package io.jafar.shell.core.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Settings for the shell's LLM features.
 *
 * <p>Values are read from shell variables (so {@code set llm.model = ...} works and {@code vars}
 * shows them), falling back to environment variables and then to the defaults here. Every default
 * is chosen so that the safe behaviour is the one you get without configuring anything.
 */
public final class LlmConfig {

  /**
   * The default model. Deliberately the strongest tier: a wrong query wastes a user's turn and
   * teaches them the wrong syntax, which costs far more than the token difference. Users who want a
   * cheaper model for translation can set {@code llm.model}.
   */
  public static final String DEFAULT_MODEL = "claude-opus-5";

  /** Output ceiling for a single {@code ask}. Query plus rationale is small. */
  public static final int DEFAULT_MAX_TOKENS = 2048;

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

  /**
   * @param lookup resolves a setting name (e.g. {@code llm.model}) to a value, or {@code null}
   */
  public LlmConfig(Function<String, String> lookup) {
    this.lookup = lookup == null ? name -> null : lookup;
  }

  /** A config backed only by environment variables and defaults. */
  public static LlmConfig fromEnvironment() {
    return new LlmConfig(name -> null);
  }

  /** Whether LLM commands are permitted at all. Set {@code llm.enabled = false} to disable. */
  public boolean enabled() {
    return !"false".equalsIgnoreCase(resolve("llm.enabled", "LLM_ENABLED", "true"));
  }

  public String model() {
    return resolve("llm.model", "JAFAR_LLM_MODEL", DEFAULT_MODEL);
  }

  /** Backend id, or {@code auto} to take the first discovered one. */
  public String backendId() {
    return resolve("llm.backend", "JAFAR_LLM_BACKEND", "auto");
  }

  public int maxTokens() {
    return intValue("llm.max-tokens", "JAFAR_LLM_MAX_TOKENS", DEFAULT_MAX_TOKENS);
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
    return fallback;
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
        max tokens  : %d
        max rows    : %d
        confirm     : %s
        redaction   : %s
        redact keys : %s"""
        .formatted(
            enabled(),
            backendId(),
            model(),
            maxTokens(),
            maxRows(),
            confirmBeforeRun(),
            redactionEnabled() ? "on" : "OFF",
            String.join(", ", redactFields()));
  }
}
