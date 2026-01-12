package io.jafar.shell.llm.providers;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;
import io.jafar.shell.llm.LLMProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM provider for Anthropic models (Claude) using LangChain4j. Requires an API key set in
 * configuration or ANTHROPIC_API_KEY environment variable.
 */
public class AnthropicProvider extends LLMProvider {

  private final ChatLanguageModel model;

  /**
   * Creates an Anthropic provider with the given configuration.
   *
   * @param config provider configuration
   */
  public AnthropicProvider(LLMConfig config) {
    super(config);

    // Get API key from config or environment
    String apiKey = config.apiKey();
    if (apiKey == null || apiKey.isEmpty()) {
      apiKey = System.getenv("ANTHROPIC_API_KEY");
    }

    this.model =
        AnthropicChatModel.builder()
            .baseUrl(config.endpoint())
            .apiKey(apiKey)
            .modelName(config.model())
            .temperature(config.temperature())
            .maxTokens(config.maxTokens())
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .build();
  }

  @Override
  public LLMResponse complete(LLMRequest request) throws LLMException {
    long startTime = System.currentTimeMillis();

    try {
      // Convert to LangChain4j messages
      List<ChatMessage> messages = new ArrayList<>();

      // Add system message if present
      if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
        messages.add(SystemMessage.from(request.systemPrompt()));
      }

      // Add conversation messages
      for (Message msg : request.messages()) {
        switch (msg.role()) {
          case USER -> messages.add(UserMessage.from(msg.content()));
          case ASSISTANT -> messages.add(AiMessage.from(msg.content()));
          case SYSTEM -> messages.add(SystemMessage.from(msg.content()));
        }
      }

      // Send request
      Response<AiMessage> response = model.generate(messages);

      // Extract response
      String content = response.content().text();
      int tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0;
      long duration = System.currentTimeMillis() - startTime;

      return new LLMResponse(content, config.model(), tokens, duration);

    } catch (RuntimeException e) {
      // Check for authentication errors
      if (e.getMessage() != null && e.getMessage().contains("401")) {
        throw new LLMException(
            LLMException.ErrorType.AUTH_FAILED,
            "Authentication failed. Check your Anthropic API key.",
            e);
      }
      // Check for rate limiting
      if (e.getMessage() != null && e.getMessage().contains("429")) {
        throw new LLMException(
            LLMException.ErrorType.RATE_LIMITED,
            "Rate limit exceeded. Wait a moment and try again.",
            e);
      }
      throw new LLMException(
          LLMException.ErrorType.NETWORK_ERROR, "Network error: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new LLMException(
          LLMException.ErrorType.NETWORK_ERROR, "Error calling Anthropic: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean isAvailable() {
    try {
      // Try a minimal request to check availability
      List<ChatMessage> messages = new ArrayList<>();
      messages.add(UserMessage.from("test"));
      model.generate(messages);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public String getModelName() {
    return config.model();
  }
}
