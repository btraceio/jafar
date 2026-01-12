package io.jafar.shell.llm.providers;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;
import io.jafar.shell.llm.LLMProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM provider for local Ollama models using LangChain4j. Communicates with Ollama at
 * http://localhost:11434 by default.
 */
public class OllamaProvider extends LLMProvider {

  private final ChatLanguageModel model;

  /**
   * Creates an Ollama provider with the given configuration.
   *
   * @param config provider configuration
   */
  public OllamaProvider(LLMConfig config) {
    super(config);
    this.model =
        OllamaChatModel.builder()
            .baseUrl(config.endpoint())
            .modelName(config.model())
            .temperature(config.temperature())
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
      // Check for model not found error
      if (e.getMessage() != null && e.getMessage().contains("not found")) {
        throw new LLMException(
            LLMException.ErrorType.INVALID_RESPONSE,
            "Model '"
                + config.model()
                + "' not found in Ollama. Run 'ollama list' to see available models, or 'ollama pull "
                + config.model()
                + "' to download it.",
            e);
      }
      // Check for connection errors (might be wrapped in RuntimeException)
      if (e.getCause() instanceof java.net.ConnectException) {
        throw new LLMException(
            LLMException.ErrorType.PROVIDER_UNAVAILABLE,
            "Cannot connect to Ollama at " + config.endpoint() + ". Is Ollama running?",
            e);
      }
      throw new LLMException(
          LLMException.ErrorType.NETWORK_ERROR, "Network error: " + e.getMessage(), e);
    } catch (Exception e) {
      // Check for connection errors
      if (e.getCause() instanceof java.net.ConnectException) {
        throw new LLMException(
            LLMException.ErrorType.PROVIDER_UNAVAILABLE,
            "Cannot connect to Ollama at " + config.endpoint() + ". Is Ollama running?",
            e);
      }
      throw new LLMException(
          LLMException.ErrorType.NETWORK_ERROR, "Error calling Ollama: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean isAvailable() {
    try {
      // Try a simple request to check availability
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
