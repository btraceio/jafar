package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmConfigTest {

  private static LlmConfig of(Map<String, String> settings) {
    return new LlmConfig(settings::get);
  }

  @Test
  void defaultsAreTheSafeOnes() {
    LlmConfig config = of(Map.of());
    assertTrue(config.enabled());
    assertTrue(config.redactionEnabled(), "redaction must be on unless explicitly disabled");
    assertFalse(config.confirmBeforeRun());
    assertEquals(LlmConfig.DEFAULT_MODEL, config.model());
    assertEquals(LlmConfig.DEFAULT_MAX_ROWS, config.maxRows());
    assertEquals("auto", config.backendId());
  }

  @Test
  void settingsOverrideDefaults() {
    LlmConfig config =
        of(
            Map.of(
                "llm.model", "claude-haiku-4-5",
                "llm.max-rows", "5",
                "llm.confirm", "true",
                "llm.enabled", "false"));
    assertEquals("claude-haiku-4-5", config.model());
    assertEquals(5, config.maxRows());
    assertTrue(config.confirmBeforeRun());
    assertFalse(config.enabled());
  }

  @Test
  void redactionOnlyOffWhenExplicitlyFalse() {
    assertTrue(of(Map.of("llm.redact", "yes")).redactionEnabled());
    assertTrue(of(Map.of("llm.redact", "")).redactionEnabled());
    assertFalse(of(Map.of("llm.redact", "false")).redactionEnabled());
    assertFalse(of(Map.of("llm.redact", "FALSE")).redactionEnabled());
  }

  @Test
  void redactFieldListReplacesByDefault() {
    Set<String> fields = of(Map.of("llm.redact-fields", "secret, token")).redactFields();
    assertEquals(Set.of("secret", "token"), fields);
  }

  @Test
  void leadingPlusAddsToTheDefaults() {
    Set<String> fields = of(Map.of("llm.redact-fields", "+secret")).redactFields();
    assertTrue(fields.contains("secret"));
    assertTrue(fields.containsAll(LlmConfig.DEFAULT_REDACT_FIELDS));
  }

  @Test
  void invalidNumbersFallBackRatherThanThrowing() {
    LlmConfig config = of(Map.of("llm.max-rows", "not-a-number", "llm.max-tokens", "-5"));
    assertEquals(LlmConfig.DEFAULT_MAX_ROWS, config.maxRows());
    assertEquals(LlmConfig.DEFAULT_MAX_TOKENS, config.maxTokens());
  }

  @Test
  void describeShowsRedactionOffLoudly() {
    assertTrue(of(Map.of("llm.redact", "false")).describe().contains("OFF"));
    assertTrue(of(Map.of()).describe().contains("on"));
  }

  @Test
  void classAndMethodNamesAreNotRedactedByDefault() {
    // Deliberate: without them there is no performance question left to answer.
    Set<String> fields = of(Map.of()).redactFields();
    assertFalse(fields.contains("class"));
    assertFalse(fields.contains("method"));
    assertTrue(fields.contains("path"));
  }
}
