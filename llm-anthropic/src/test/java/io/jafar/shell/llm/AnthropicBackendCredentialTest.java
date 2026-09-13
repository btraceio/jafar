package io.jafar.shell.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Credential resolution for the Anthropic backend.
 *
 * <p>These exist because of a bug that unit tests could not have caught while the backend only ever
 * asked the SDK: a key put in the settings file — the place the docs recommend, since an
 * environment variable is inherited by every child process — reached the OpenAI-compatible backends
 * and was silently ignored here, so {@code llm status} said "No credentials found" with the key
 * sitting right there in the file.
 *
 * <p>No request is made: readiness is a local decision, which is the whole point of it.
 */
class AnthropicBackendCredentialTest {

  /** A config with no shell variables, so only the explicit lookup below can supply a value. */
  private static LlmConfig configWith(Map<String, String> settings) {
    return new LlmConfig(settings::get);
  }

  @Test
  void aConfiguredKeyMakesTheBackendReady() {
    LlmBackend.Readiness readiness =
        new AnthropicBackend().readiness(configWith(Map.of("llm.api-key", "sk-ant-configured")));

    assertTrue(readiness.ready(), readiness.detail());
  }

  @Test
  void statusSaysWhereTheConfiguredKeyCameFrom() {
    // The source matters more than the value: a stale environment variable shadowing the settings
    // file looks identical to the file not being read at all.
    LlmBackend.Readiness readiness =
        new AnthropicBackend().readiness(configWith(Map.of("llm.api-key", "sk-ant-configured")));

    assertTrue(readiness.detail().contains("llm.api-key"), readiness.detail());
    assertTrue(readiness.detail().contains("set in this shell"), readiness.detail());
  }

  @Test
  void theKeyItselfIsNeverPrinted() {
    LlmBackend.Readiness readiness =
        new AnthropicBackend().readiness(configWith(Map.of("llm.api-key", "sk-ant-secret-value")));

    assertTrue(!readiness.detail().contains("sk-ant-secret-value"), readiness.detail());
  }

  @Test
  void aBlankConfiguredKeyIsNotACredential() {
    // An empty value must not count as configured, or it shadows a working OAuth profile and
    // authenticates as an empty key — the same trap the environment variable has.
    LlmBackend.Readiness readiness =
        new AnthropicBackend().readiness(configWith(Map.of("llm.api-key", "   ")));

    // Without credentials in this environment it is not ready; with them it is, but either way it
    // must not claim the blank key as the reason.
    assertTrue(!readiness.detail().contains("llm.api-key ("), readiness.detail());
  }

  @Test
  void theRemedyNamesTheSettingsFileAndBothOtherRoutes() {
    String help = new AnthropicBackend().credentialHelp();

    assertTrue(help.contains("llm.api-key"), help);
    assertTrue(help.contains("ANTHROPIC_API_KEY"), help);
    assertTrue(help.contains("ant auth login"), help);
  }

  @Test
  void theDefaultModelIsTheStrongestTier() {
    assertEquals("claude-opus-5", new AnthropicBackend().defaultModel());
  }
}
