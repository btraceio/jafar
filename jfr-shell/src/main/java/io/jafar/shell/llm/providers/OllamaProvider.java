package io.jafar.shell.llm.providers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
  private final Gson gson = new Gson();

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
            .format("json") // Enable JSON mode for structured outputs
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
  public LLMResponse completeStructured(LLMRequest request, JsonSchema schema) throws LLMException {
    long startTime = System.currentTimeMillis();

    try {
      // Convert schema to prompt-embedded JSON schema
      String schemaPrompt = buildSchemaPrompt(schema);

      // Prepend schema to system prompt (schema must come FIRST for JSON mode to work)
      String enhancedSystemPrompt =
          schemaPrompt + "\n\n" + (request.systemPrompt() != null ? request.systemPrompt() : "");

      // Debug: Show prompt size and schema
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.err.println("=== OLLAMA REQUEST DEBUG ===");
        System.err.println("System prompt size: " + enhancedSystemPrompt.length() + " chars");
        System.err.println("Schema prompt (first 500 chars):");
        System.err.println(schemaPrompt.substring(0, Math.min(500, schemaPrompt.length())));
        System.err.println("...");
        System.err.println("============================");
      }

      // Build LangChain4j messages
      List<ChatMessage> messages = new ArrayList<>();
      messages.add(SystemMessage.from(enhancedSystemPrompt));

      // Add conversation messages
      for (Message msg : request.messages()) {
        switch (msg.role()) {
          case USER -> messages.add(UserMessage.from(msg.content()));
          case ASSISTANT -> messages.add(AiMessage.from(msg.content()));
          case SYSTEM -> messages.add(SystemMessage.from(msg.content()));
        }
      }

      // Call with JSON format enforcement
      Response<AiMessage> response = model.generate(messages);

      // Extract response content
      String content = response.content().text();

      // Validate response matches schema
      validateAgainstSchema(content, schema);

      int tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0;
      long duration = System.currentTimeMillis() - startTime;

      return new LLMResponse(content, config.model(), tokens, duration);

    } catch (LLMException e) {
      // Re-throw LLMException as-is
      throw e;
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
      // Check for connection errors
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

  private String buildSchemaPrompt(JsonSchema schema) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("=".repeat(80)).append("\n");
    prompt.append("MANDATORY OUTPUT FORMAT - YOU MUST FOLLOW THIS EXACTLY\n");
    prompt.append("=".repeat(80)).append("\n\n");

    prompt.append("Your response MUST be a JSON object with these EXACT field names:\n\n");

    // List required fields with descriptions and example values
    for (String fieldName : schema.required()) {
      PropertySchema prop = schema.properties().get(fieldName);
      prompt.append("  \"").append(fieldName).append("\": ");

      // Show example value based on type
      switch (prop.type()) {
        case "string" -> {
          if (fieldName.equals("query")) {
            prompt.append("\"events/<event-type> | <operation>\"");
          } else if (fieldName.equals("explanation")) {
            prompt.append("\"Brief explanation of what the query does\"");
          } else if (fieldName.equals("category")) {
            prompt.append("\"TOPN_RANKING\"");
          } else if (fieldName.equals("reasoning")) {
            prompt.append("\"Brief reasoning for classification\"");
          } else {
            prompt.append("\"your string value here\"");
          }
        }
        case "number" -> {
          if (fieldName.equals("confidence")) {
            prompt.append("0.95");
          } else {
            prompt.append("0.0");
          }
        }
        case "boolean" -> prompt.append("true");
        case "array" -> prompt.append("[\"item1\", \"item2\"]");
        case "object" -> prompt.append("{}");
        default -> prompt.append("null");
      }

      prompt.append("  // REQUIRED - ").append(prop.description()).append("\n");
    }

    // List optional fields
    for (var entry : schema.properties().entrySet()) {
      if (!schema.required().contains(entry.getKey())) {
        String fieldName = entry.getKey();
        PropertySchema prop = entry.getValue();
        prompt.append("  \"").append(fieldName).append("\": ");

        switch (prop.type()) {
          case "string" -> prompt.append("\"optional value\"");
          case "number" -> prompt.append("0.0");
          case "boolean" -> prompt.append("false");
          case "array" -> prompt.append("[]");
          case "object" -> prompt.append("{}");
          default -> prompt.append("null");
        }

        prompt.append("  // OPTIONAL - ").append(prop.description()).append("\n");
      }
    }

    prompt.append("\n").append("=".repeat(80)).append("\n");
    prompt.append("DO NOT include any text before or after the JSON object.\n");
    prompt.append("DO NOT omit any REQUIRED fields.\n");
    prompt.append("=".repeat(80)).append("\n");

    return prompt.toString();
  }

  private void validateAgainstSchema(String responseContent, JsonSchema schema)
      throws LLMException {
    // Debug output
    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.err.println("=== LLM RESPONSE ===");
      System.err.println("Schema: " + schema.name());
      System.err.println("Response: " + responseContent);
      System.err.println("====================");
    }

    try {
      // Parse response as JSON
      JsonObject jsonResponse = JsonParser.parseString(responseContent).getAsJsonObject();

      // Validate required fields are present
      for (String requiredField : schema.required()) {
        if (!jsonResponse.has(requiredField)) {
          throw new LLMException(
              LLMException.ErrorType.INVALID_RESPONSE,
              "Response missing required field: " + requiredField);
        }
      }

      // Basic type validation for each property
      for (var entry : schema.properties().entrySet()) {
        String fieldName = entry.getKey();
        PropertySchema propSchema = entry.getValue();

        if (jsonResponse.has(fieldName)) {
          var element = jsonResponse.get(fieldName);

          // Validate type
          switch (propSchema.type()) {
            case "string" -> {
              if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new LLMException(
                    LLMException.ErrorType.INVALID_RESPONSE,
                    "Field '" + fieldName + "' must be a string");
              }
            }
            case "number" -> {
              if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new LLMException(
                    LLMException.ErrorType.INVALID_RESPONSE,
                    "Field '" + fieldName + "' must be a number");
              }
            }
            case "boolean" -> {
              if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                throw new LLMException(
                    LLMException.ErrorType.INVALID_RESPONSE,
                    "Field '" + fieldName + "' must be a boolean");
              }
            }
            case "array" -> {
              if (!element.isJsonArray()) {
                throw new LLMException(
                    LLMException.ErrorType.INVALID_RESPONSE,
                    "Field '" + fieldName + "' must be an array");
              }
            }
            case "object" -> {
              if (!element.isJsonObject()) {
                throw new LLMException(
                    LLMException.ErrorType.INVALID_RESPONSE,
                    "Field '" + fieldName + "' must be an object");
              }
            }
          }
        }
      }

    } catch (JsonSyntaxException e) {
      throw new LLMException(
          LLMException.ErrorType.INVALID_RESPONSE,
          "Response is not valid JSON: " + e.getMessage(),
          e);
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
