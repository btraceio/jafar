package io.jafar.shell.llm;

import java.util.List;
import java.util.Map;

/**
 * Abstract base class for LLM providers. Implementations connect to local or cloud LLMs to generate
 * responses.
 */
public abstract class LLMProvider implements AutoCloseable {

  /** Configuration for this provider. */
  protected final LLMConfig config;

  /**
   * Creates a new LLM provider with the given configuration.
   *
   * @param config provider configuration
   */
  protected LLMProvider(LLMConfig config) {
    this.config = config;
  }

  /**
   * Sends a completion request to the LLM and returns the response.
   *
   * @param request the request to send
   * @return the LLM response
   * @throws LLMException if the request fails
   */
  public abstract LLMResponse complete(LLMRequest request) throws LLMException;

  /**
   * Complete with structured output enforcement.
   *
   * @param request LLM request
   * @param schema JSON schema for response format
   * @return LLM response guaranteed to match schema
   * @throws LLMException if the request fails or response doesn't match schema
   */
  public abstract LLMResponse completeStructured(LLMRequest request, JsonSchema schema)
      throws LLMException;

  /**
   * Checks if this provider is available and ready to handle requests.
   *
   * @return true if the provider is available
   */
  public abstract boolean isAvailable();

  /**
   * Gets the name of the model being used.
   *
   * @return model name
   */
  public abstract String getModelName();

  /**
   * Gets the configuration for this provider.
   *
   * @return provider configuration
   */
  public LLMConfig getConfig() {
    return config;
  }

  /** Default implementation: no cleanup needed. */
  @Override
  public void close() throws Exception {
    // Default: no resources to clean up
  }

  /**
   * Factory method to create an LLM provider from configuration.
   *
   * @param config provider configuration
   * @return appropriate provider instance
   */
  public static LLMProvider create(LLMConfig config) {
    return LLMProviderFactory.create(config);
  }

  /** Role of a message in a conversation. */
  public enum Role {
    /** Message from the user. */
    USER,
    /** Message from the assistant (LLM). */
    ASSISTANT,
    /** System instruction or context. */
    SYSTEM
  }

  /**
   * A message in a conversation.
   *
   * @param role the role of the message sender
   * @param content the message content
   */
  public record Message(Role role, String content) {}

  /**
   * Request to send to an LLM.
   *
   * @param systemPrompt optional system prompt providing context and instructions
   * @param messages conversation history
   * @param options provider-specific options (temperature, max_tokens, etc.)
   */
  public record LLMRequest(
      String systemPrompt, List<Message> messages, Map<String, Object> options) {

    /**
     * Creates a simple request with just a user message.
     *
     * @param userMessage the user's message
     * @return request with default options
     */
    public static LLMRequest of(String userMessage) {
      return new LLMRequest(null, List.of(new Message(Role.USER, userMessage)), Map.of());
    }

    /**
     * Creates a request with system prompt and user message.
     *
     * @param systemPrompt system prompt
     * @param userMessage user message
     * @return request with default options
     */
    public static LLMRequest of(String systemPrompt, String userMessage) {
      return new LLMRequest(systemPrompt, List.of(new Message(Role.USER, userMessage)), Map.of());
    }
  }

  /**
   * Response from an LLM.
   *
   * @param content the generated text
   * @param model the model that generated the response
   * @param tokensUsed number of tokens used (input + output)
   * @param durationMs time taken to generate the response in milliseconds
   */
  public record LLMResponse(String content, String model, int tokensUsed, long durationMs) {}

  /**
   * JSON schema for structured outputs.
   *
   * @param name Schema name (for debugging)
   * @param description Human-readable description
   * @param properties Map of property name to property schema
   * @param required List of required property names
   */
  public record JsonSchema(
      String name,
      String description,
      Map<String, PropertySchema> properties,
      List<String> required) {}

  /**
   * Property schema within a JSON schema.
   *
   * @param type Property type ("string", "number", "boolean", "array", "object")
   * @param description Human-readable description
   * @param additionalConstraints Additional constraints (minimum, maximum, items, etc.)
   */
  public record PropertySchema(
      String type, String description, Map<String, Object> additionalConstraints) {}
}
