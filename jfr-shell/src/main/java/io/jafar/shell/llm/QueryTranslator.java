package io.jafar.shell.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.llm.LLMProvider.Message;
import io.jafar.shell.llm.LLMProvider.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translates natural language queries into JfrPath queries using an LLM provider. Validates
 * generated queries and provides explanations and confidence scores.
 */
public class QueryTranslator {

  private final LLMProvider provider;
  private final ContextBuilder contextBuilder;
  private final ConversationHistory history;
  private final Gson gson;

  /**
   * Creates a query translator.
   *
   * @param provider LLM provider
   * @param contextBuilder context builder for prompts
   * @param history conversation history for context
   */
  public QueryTranslator(
      LLMProvider provider, ContextBuilder contextBuilder, ConversationHistory history) {
    this.provider = provider;
    this.contextBuilder = contextBuilder;
    this.history = history;
    this.gson = new Gson();
  }

  /**
   * Translates a natural language query into a JfrPath query.
   *
   * @param naturalLanguageQuery the user's question
   * @return translation result with query, explanation, and confidence
   * @throws LLMException if translation fails
   */
  public TranslationResult translate(String naturalLanguageQuery) throws LLMException {
    // Build the request
    String systemPrompt = contextBuilder.buildSystemPrompt();
    List<Message> messages = buildMessages(naturalLanguageQuery);

    Map<String, Object> options =
        Map.of(
            "temperature", provider.getConfig().temperature(),
            "max_tokens", provider.getConfig().maxTokens());

    LLMProvider.LLMRequest request = new LLMProvider.LLMRequest(systemPrompt, messages, options);

    // Get LLM response
    LLMProvider.LLMResponse response = provider.complete(request);

    // Parse the response
    return parseResponse(response.content());
  }

  /**
   * Builds message list including conversation history.
   *
   * @param naturalLanguageQuery current user query
   * @return list of messages
   */
  private List<Message> buildMessages(String naturalLanguageQuery) {
    List<Message> messages = new ArrayList<>();

    // Include conversation history for context
    if (history.size() > 0) {
      // Add previous turns
      messages.addAll(history.toMessages());
    }

    // Add current user query
    messages.add(new Message(Role.USER, naturalLanguageQuery));

    return messages;
  }

  /**
   * Parses LLM response to extract query, explanation, and confidence.
   *
   * @param responseContent LLM response content
   * @return translation result
   * @throws LLMException if parsing fails
   */
  private TranslationResult parseResponse(String responseContent) throws LLMException {
    try {
      // Extract JSON object from response (may have extra text)
      String json = extractJson(responseContent);
      if (json == null) {
        // No JSON found - treat as conversational response
        // This happens for ambiguous queries like "help" or general questions
        return new TranslationResult(null, null, 0.0, Optional.empty(), responseContent);
      }

      // Parse JSON using Gson
      JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

      // Extract fields
      String query =
          jsonObject.has("query") && !jsonObject.get("query").isJsonNull()
              ? jsonObject.get("query").getAsString()
              : null;
      String explanation =
          jsonObject.has("explanation") && !jsonObject.get("explanation").isJsonNull()
              ? jsonObject.get("explanation").getAsString()
              : null;
      double confidence =
          jsonObject.has("confidence") && !jsonObject.get("confidence").isJsonNull()
              ? jsonObject.get("confidence").getAsDouble()
              : 0.5;
      String warning =
          jsonObject.has("warning") && !jsonObject.get("warning").isJsonNull()
              ? jsonObject.get("warning").getAsString()
              : null;

      if (query == null || query.isEmpty()) {
        throw new LLMException(
            LLMException.ErrorType.INVALID_RESPONSE, "LLM did not provide a query in response");
      }

      // Validate query syntax
      if (!validateQuery(query)) {
        throw new LLMException(
            LLMException.ErrorType.INVALID_RESPONSE,
            "Generated query has invalid syntax: " + query);
      }

      return new TranslationResult(
          query, explanation, confidence, Optional.ofNullable(warning), null);

    } catch (JsonParseException e) {
      throw new LLMException(
          LLMException.ErrorType.PARSE_ERROR,
          "Failed to parse JSON response: " + e.getMessage(),
          e);
    } catch (LLMException e) {
      throw e;
    } catch (Exception e) {
      throw new LLMException(
          LLMException.ErrorType.PARSE_ERROR, "Failed to parse LLM response: " + e.getMessage(), e);
    }
  }

  /**
   * Extracts JSON object from response text.
   *
   * @param text response text
   * @return JSON object or null
   */
  private String extractJson(String text) {
    // Find balanced braces - simple brace counting approach
    int firstBrace = text.indexOf('{');
    if (firstBrace == -1) {
      return null;
    }

    int depth = 0;
    boolean inString = false;
    boolean escaped = false;

    for (int i = firstBrace; i < text.length(); i++) {
      char c = text.charAt(i);

      if (escaped) {
        escaped = false;
        continue;
      }

      if (c == '\\') {
        escaped = true;
        continue;
      }

      if (c == '"') {
        inString = !inString;
        continue;
      }

      if (!inString) {
        if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
          if (depth == 0) {
            return text.substring(firstBrace, i + 1);
          }
        }
      }
    }

    return null;
  }

  /**
   * Validates a JfrPath query by attempting to parse it.
   *
   * @param query query string
   * @return true if valid
   */
  private boolean validateQuery(String query) {
    try {
      JfrPathParser.parse(query);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Result of query translation.
   *
   * @param jfrPathQuery the generated JfrPath query (null for conversational responses)
   * @param explanation explanation of what the query does
   * @param confidence confidence score 0.0-1.0
   * @param warning optional warning about ambiguity or limitations
   * @param conversationalResponse non-null when LLM responds conversationally instead of with a
   *     query
   */
  public record TranslationResult(
      String jfrPathQuery,
      String explanation,
      double confidence,
      Optional<String> warning,
      String conversationalResponse) {
    /**
     * Returns true if this is a conversational response (no query generated).
     *
     * @return true if conversational
     */
    public boolean isConversational() {
      return conversationalResponse != null;
    }
  }
}
