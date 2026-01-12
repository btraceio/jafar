package io.jafar.shell.llm;

import io.jafar.shell.llm.providers.AnthropicProvider;
import io.jafar.shell.llm.providers.MockProvider;
import io.jafar.shell.llm.providers.OllamaProvider;
import io.jafar.shell.llm.providers.OpenAIProvider;

/** Factory for creating LLM provider instances based on configuration. */
public final class LLMProviderFactory {

  private LLMProviderFactory() {
    // Utility class
  }

  /**
   * Creates an LLM provider instance from configuration.
   *
   * @param config provider configuration
   * @return appropriate provider implementation
   * @throws IllegalArgumentException if provider type is unknown
   */
  public static LLMProvider create(LLMConfig config) {
    return switch (config.provider()) {
      case LOCAL -> new OllamaProvider(config);
      case OPENAI -> new OpenAIProvider(config);
      case ANTHROPIC -> new AnthropicProvider(config);
      case MOCK -> new MockProvider(config);
    };
  }
}
