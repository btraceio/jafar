package io.jafar.shell.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates clarification questions for ambiguous queries. Helps users specify exactly what they
 * want when the query is too vague or has multiple interpretations.
 */
public class QueryClarifier {

  private final LLMProvider provider;
  private final Gson gson = new Gson();
  private static final String CLARIFY_PROMPT = loadClarifyPrompt();

  /**
   * Creates a query clarifier with the given LLM provider.
   *
   * @param provider LLM provider for clarification
   */
  public QueryClarifier(LLMProvider provider) {
    this.provider = provider;
  }

  /**
   * Generate clarification question for ambiguous query.
   *
   * @param userQuery user's natural language query
   * @param category classified category for context
   * @return clarification result with question and choices
   * @throws LLMException if clarification generation fails
   */
  public ClarificationResult clarify(String userQuery, QueryCategory category) throws LLMException {

    String userMessage = buildClarifyMessage(userQuery, category);

    LLMProvider.LLMRequest request =
        new LLMProvider.LLMRequest(
            CLARIFY_PROMPT,
            List.of(new LLMProvider.Message(LLMProvider.Role.USER, userMessage)),
            Map.of("temperature", 0.3, "max_tokens", 200));

    LLMProvider.LLMResponse response =
        provider.completeStructured(request, ResponseSchemas.CLARIFICATION);

    JsonObject json = gson.fromJson(response.content(), JsonObject.class);

    String question = json.get("clarificationQuestion").getAsString();
    List<String> choices =
        gson.fromJson(json.get("suggestedChoices"), new TypeToken<List<String>>() {}.getType());
    double ambiguityScore = json.get("ambiguityScore").getAsDouble();

    return new ClarificationResult(question, choices, ambiguityScore);
  }

  /**
   * Build clarification message with query and category.
   *
   * @param query user query
   * @param category classified category
   * @return clarification message
   */
  private String buildClarifyMessage(String query, QueryCategory category) {
    return String.format(
        "USER QUERY: %s\nCLASSIFIED AS: %s\n\n"
            + "Generate ONE clarification question with 2-4 specific choices.",
        query, category.name());
  }

  /**
   * Load clarifier prompt from resources.
   *
   * @return clarifier prompt text
   */
  private static String loadClarifyPrompt() {
    try (InputStream is =
        QueryClarifier.class.getResourceAsStream("/llm-prompts/pipeline/clarifier.txt")) {
      if (is != null) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
          return reader.lines().collect(Collectors.joining("\n"));
        }
      }
    } catch (IOException e) {
      // Fall through to default
    }

    // Fallback prompt if resource not found
    return """
            You generate clarification questions for ambiguous JFR analysis queries.

            GOAL: Help user specify exactly what they want to analyze.

            OUTPUT FORMAT:
            {
              "clarificationQuestion": "One clear, specific question",
              "suggestedChoices": ["Choice 1", "Choice 2", "Choice 3", "Choice 4"],
              "ambiguityScore": 0.7
            }

            RULES:
            1. Ask ONE question only - the most important clarification needed
            2. Provide 2-4 concrete, specific answer choices
            3. Choices should be mutually exclusive options
            4. AmbiguityScore: 0.0 (crystal clear) to 1.0 (very ambiguous)

            EXAMPLES:

            User: "show threads"
            {
              "clarificationQuestion": "What do you want to see about threads?",
              "suggestedChoices": [
                "List of thread names with sample counts",
                "Thread states over time",
                "CPU usage by thread",
                "Thread creation and termination events"
              ],
              "ambiguityScore": 0.8
            }

            User: "GC events"
            {
              "clarificationQuestion": "How do you want to analyze GC events?",
              "suggestedChoices": [
                "Count total GC events",
                "Show GC events with longest pause times",
                "Calculate average GC pause duration",
                "Group GC events by cause"
              ],
              "ambiguityScore": 0.9
            }

            User: "allocations"
            {
              "clarificationQuestion": "What aspect of allocations do you want to see?",
              "suggestedChoices": [
                "Total allocation size",
                "Allocations grouped by class",
                "Top allocating methods",
                "Allocations during GC phases"
              ],
              "ambiguityScore": 0.85
            }
            """;
  }

  /**
   * Result of clarification.
   *
   * @param question clarification question to ask user
   * @param choices suggested answer choices (2-4)
   * @param ambiguityScore how ambiguous the query is (0.0-1.0)
   */
  public record ClarificationResult(String question, List<String> choices, double ambiguityScore) {}
}
