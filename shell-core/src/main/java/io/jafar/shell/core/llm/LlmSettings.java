package io.jafar.shell.core.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The names of the shell's LLM settings, in one place.
 *
 * <p>These are <em>settings</em>, not query variables, and the distinction is load-bearing. A query
 * variable is referenced as <code>${name}</code>, where a dot means field access — so <code>
 * ${llm.backend}</code> would read field {@code backend} of a variable named {@code llm}. That
 * ambiguity is why the {@code set} command rejects dotted names, and why it has to make an
 * exception for exactly these: they are read back by name through the config lookup and are never
 * substituted into an expression.
 *
 * <p>The list lives here rather than in the shells because three places need to agree on it — the
 * {@code set} command's validation, tab completion, and {@link LlmConfig}'s own reads — and a
 * setting that one of them does not know about is the kind of gap that only shows up when someone
 * types it.
 */
public final class LlmSettings {

  /** A setting: the name {@code set} accepts, and what it does. */
  public record Setting(String name, String description) {}

  private static final List<Setting> ALL =
      List.of(
          new Setting("llm.enabled", "master switch"),
          new Setting("llm.backend", "anthropic | openai | ollama | auto"),
          new Setting("llm.model", "model id; defaults to the backend's own"),
          new Setting("llm.base-url", "endpoint, for the OpenAI-compatible backends"),
          new Setting("llm.api-key", "bearer token; overrides the provider's env var"),
          new Setting("llm.max-tokens", "output ceiling per request"),
          new Setting("llm.max-rows", "result rows shown to the model by 'explain'"),
          new Setting("llm.max-retries", "correction attempts after a query fails to parse (0-3)"),
          new Setting("llm.timeout", "request timeout in seconds"),
          new Setting("llm.confirm", "when true, 'ask' prints the query but does not run it"),
          new Setting("llm.redact", "redact sensitive fields before sending"),
          new Setting("llm.redact-fields", "replace the redaction list; a leading + extends it"));

  private LlmSettings() {}

  /** Every setting, in the order worth showing them. */
  public static List<Setting> all() {
    return ALL;
  }

  /** Just the names. */
  public static List<String> names() {
    return ALL.stream().map(Setting::name).toList();
  }

  /** Whether {@code name} is a setting the shell understands. Case-insensitive. */
  public static boolean isSetting(String name) {
    return lookup(name).isPresent();
  }

  /** The setting with this name, if there is one. */
  public static Optional<Setting> lookup(String name) {
    if (name == null) {
      return Optional.empty();
    }
    String needle = name.trim().toLowerCase(Locale.ROOT);
    return ALL.stream().filter(s -> s.name().equals(needle)).findFirst();
  }

  /**
   * Whether {@code name} looks like it was meant to be an LLM setting.
   *
   * <p>Used to tell a typo ({@code llm.backed}) apart from an ordinary variable name, so the error
   * can list the real names instead of just refusing.
   */
  public static boolean looksLikeSetting(String name) {
    return name != null && name.trim().toLowerCase(Locale.ROOT).startsWith("llm.");
  }
}
