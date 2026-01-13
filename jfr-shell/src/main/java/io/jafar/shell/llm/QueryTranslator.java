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
 *
 * <p>Multi-level translation architecture:
 *
 * <ol>
 *   <li>Classification: Categorizes queries into 12 types (count, topN, decorator, etc.)
 *   <li>Prompt selection: Chooses appropriate prompt size (MINIMAL/ENHANCED/FULL)
 *   <li>Translation: Generates JfrPath query using category-specific context
 *   <li>Progressive escalation: Retries with larger prompts if confidence is low
 * </ol>
 *
 * <p>Benefits:
 *
 * <ul>
 *   <li>70-80% token reduction for simple queries
 *   <li>Faster response times with smaller prompts
 *   <li>Automatic fallback for complex queries
 *   <li>Clarification requests for ambiguous input
 * </ul>
 */
public class QueryTranslator {

  private final LLMProvider provider;
  private final ContextBuilder contextBuilder;
  private final ConversationHistory history;
  private final Gson gson;
  private final LLMConfig config;
  private final QueryClassifier classifier;
  private final PromptStrategy promptStrategy;

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
    this.config = provider.getConfig();
    this.gson = new Gson();
    this.classifier = new QueryClassifier(provider);
    this.promptStrategy = new PromptStrategy(contextBuilder);
  }

  /**
   * Translates a natural language query into a JfrPath query.
   *
   * <p>Uses multi-level prompting if enabled in config, otherwise falls back to legacy full-prompt
   * approach.
   *
   * @param naturalLanguageQuery the user's question
   * @return translation result with query, explanation, and confidence
   * @throws LLMException if translation fails
   */
  public TranslationResult translate(String naturalLanguageQuery) throws LLMException {
    if (!config.multiLevelEnabled()) {
      return translateLegacy(naturalLanguageQuery);
    }

    // Phase 4: Multi-level translation with classification and escalation

    // Step 1: Classify the query
    ClassificationResult classification = classifier.classify(naturalLanguageQuery);

    // Step 2: Check if clarification is needed
    if (classification.needsClarification()) {
      ClarificationRequest clarification = generateClarification(naturalLanguageQuery, classification);
      return TranslationResult.needsClarification(clarification);
    }

    // Step 3: Handle conversational queries
    if (classification.category() == QueryCategory.CONVERSATIONAL) {
      return handleConversational(naturalLanguageQuery);
    }

    // Step 4: Translate with progressive escalation
    return translateWithEscalation(naturalLanguageQuery, classification);
  }

  /**
   * Translates using legacy full-prompt approach (backward compatibility).
   *
   * @param naturalLanguageQuery the user's question
   * @return translation result
   * @throws LLMException if translation fails
   */
  private TranslationResult translateLegacy(String naturalLanguageQuery) throws LLMException {
    // Build the request
    String systemPrompt = contextBuilder.buildSystemPrompt();
    List<Message> messages = buildMessages(naturalLanguageQuery);

    Map<String, Object> options =
        Map.of(
            "temperature", provider.getConfig().temperature(),
            "max_tokens", provider.getConfig().maxTokens());

    LLMProvider.LLMRequest request = new LLMProvider.LLMRequest(systemPrompt, messages, options);

    // Debug logging
    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.err.println("=== LLM DEBUG: System Prompt ===");
      System.err.println(systemPrompt);
      System.err.println("=== LLM DEBUG: Messages ===");
      for (Message msg : messages) {
        System.err.println(msg.role() + ": " + msg.content());
      }
      System.err.println("================================");
    }

    // Get LLM response
    LLMProvider.LLMResponse response = provider.complete(request);

    // Debug logging
    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.err.println("=== LLM DEBUG: Response ===");
      System.err.println(response.content());
      System.err.println("===========================");
    }

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
        return TranslationResult.conversational(responseContent);
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

      if (warning != null) {
        return TranslationResult.successWithWarning(query, explanation, confidence, warning);
      } else {
        return TranslationResult.success(query, explanation, confidence);
      }

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
   * Generates a clarification request for an ambiguous query.
   *
   * @param query the ambiguous query
   * @param classification the classification result
   * @return clarification request
   * @throws LLMException if clarification generation fails
   */
  private ClarificationRequest generateClarification(
      String query, ClassificationResult classification) throws LLMException {
    // For now, create a basic clarification request
    // In Phase 5, we can use LLM to generate better clarifications
    return new ClarificationRequest(
        query,
        "Your query is ambiguous. Could you be more specific?",
        List.of(
            "Count events of a specific type",
            "Find top N results",
            "Filter events by criteria",
            "Get metadata about event types"),
        classification.confidence());
  }

  /**
   * Handles conversational queries that don't require JfrPath translation.
   *
   * @param query the conversational query
   * @return conversational response
   * @throws LLMException if response generation fails
   */
  private TranslationResult handleConversational(String query) throws LLMException {
    // Use legacy translation but expect a conversational response
    return translateLegacy(query);
  }

  /**
   * Translates a query with progressive escalation through prompt levels.
   *
   * @param query the natural language query
   * @param classification the query classification
   * @return translation result
   * @throws LLMException if all attempts fail
   */
  private TranslationResult translateWithEscalation(
      String query, ClassificationResult classification) throws LLMException {
    PromptStrategy.PromptLevel level = promptStrategy.selectStartLevel(classification);
    TranslationResult result = null;

    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        // Build category-specific prompt
        String systemPrompt = promptStrategy.buildPrompt(classification.category(), level);

        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.err.println("=== TRANSLATION ATTEMPT " + attempt + " ===");
          System.err.println("Prompt level: " + level);
          System.err.println("Prompt size: " + systemPrompt.length() + " chars (~" + (systemPrompt.length() / 4) + " tokens)");
          System.err.println("====================================");
        }

        // Build messages
        List<Message> messages = buildMessages(query);

        // Create request
        Map<String, Object> options =
            Map.of(
                "temperature", config.temperature(),
                "max_tokens", config.maxTokens());
        LLMProvider.LLMRequest request = new LLMProvider.LLMRequest(systemPrompt, messages, options);

        // Get response
        LLMProvider.LLMResponse response = provider.complete(request);

        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.err.println("=== LLM RESPONSE (attempt " + attempt + ") ===");
          System.err.println("Response length: " + response.content().length() + " chars");
          System.err.println("Response preview: " + response.content().substring(0, Math.min(200, response.content().length())));
          System.err.println("====================================");
        }

        // Parse response
        result = parseResponse(response.content());

        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.err.println("=== PARSED RESULT (attempt " + attempt + ") ===");
          System.err.println("Has query: " + result.hasQuery());
          System.err.println("Is conversational: " + result.isConversational());
          System.err.println("Confidence: " + result.confidence());
          if (result.hasQuery()) {
            System.err.println("Query: " + result.jfrPathQuery());
          }
          System.err.println("====================================");
        }

        // Check if escalation is needed
        if (!promptStrategy.shouldEscalate(result, attempt, level)) {
          if (Boolean.getBoolean("jfr.shell.debug")) {
            System.err.println("=== NO ESCALATION NEEDED ===");
            System.err.println("Returning result from attempt " + attempt);
            System.err.println("============================");
          }
          return result;
        }

        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.err.println("=== ESCALATING ===");
          System.err.println("From level: " + level);
        }

        // Escalate to next level
        level = level.next();
        if (level == null) {
          if (Boolean.getBoolean("jfr.shell.debug")) {
            System.err.println("No more levels to escalate to");
            System.err.println("==================");
          }
          break; // No more levels to try
        }

        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.err.println("To level: " + level);
          System.err.println("==================");
        }

      } catch (LLMException e) {
        // On last attempt, rethrow
        if (attempt == 3 || level == null || level.next() == null) {
          throw e;
        }
        // Otherwise escalate and retry
        level = level.next();
      }
    }

    // Return last result if all attempts completed without success
    return result != null ? result : translateLegacy(query);
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

}
