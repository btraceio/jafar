package io.jafar.shell.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.jafar.shell.llm.QueryValidator.ValidationResult;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-based query repairer for minimal targeted fixes. Only repairs specific validation issues
 * without rewriting the entire query.
 */
public class QueryRepairer {

  private final LLMProvider provider;
  private final Gson gson = new Gson();
  private static final String REPAIR_PROMPT = loadRepairPrompt();

  /**
   * Creates a query repairer with the given LLM provider.
   *
   * @param provider LLM provider for repair
   */
  public QueryRepairer(LLMProvider provider) {
    this.provider = provider;
  }

  /**
   * Repair query with minimal changes to fix validation issues.
   *
   * @param originalQuery original query with issues
   * @param validation validation result with issues to fix
   * @return repair result with fixed query and changes made
   * @throws LLMException if repair fails
   */
  public RepairResult repair(String originalQuery, ValidationResult validation)
      throws LLMException {

    // Build repair request
    String userMessage = buildRepairMessage(originalQuery, validation);

    LLMProvider.LLMRequest request =
        new LLMProvider.LLMRequest(
            REPAIR_PROMPT,
            List.of(new LLMProvider.Message(LLMProvider.Role.USER, userMessage)),
            Map.of("temperature", 0.1, "max_tokens", 300));

    // Call with structured output
    LLMProvider.LLMResponse response = provider.completeStructured(request, ResponseSchemas.REPAIR);

    // Parse response
    JsonObject json = gson.fromJson(response.content(), JsonObject.class);

    String repairedQuery = json.get("query").getAsString();
    List<String> changes =
        gson.fromJson(json.get("changes"), new TypeToken<List<String>>() {}.getType());
    double confidence = json.get("confidence").getAsDouble();
    String warning = json.has("warning") ? json.get("warning").getAsString() : null;

    return new RepairResult(repairedQuery, changes, confidence, warning);
  }

  /**
   * Build repair message with query and issues.
   *
   * @param query query to repair
   * @param validation validation result with issues
   * @return repair message
   */
  private String buildRepairMessage(String query, ValidationResult validation) {
    StringBuilder sb = new StringBuilder();
    sb.append("ORIGINAL QUERY:\n").append(query).append("\n\n");
    sb.append("ISSUES DETECTED:\n");

    for (var issue : validation.issues()) {
      sb.append("- ")
          .append(issue.description())
          .append(" (")
          .append(issue.suggestion())
          .append(")\n");
    }

    sb.append("\nFix ONLY the issues listed. Do NOT rewrite the query logic.");
    return sb.toString();
  }

  /**
   * Load repair prompt from resources.
   *
   * @return repair prompt text
   */
  private static String loadRepairPrompt() {
    try (InputStream is =
        QueryRepairer.class.getResourceAsStream("/llm-prompts/pipeline/repair.txt")) {
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
            You are a JfrPath query repair specialist.

            Your ONLY job: fix syntax errors while preserving the query's intent.

            RULES:
            1. Fix ONLY the specific issues mentioned in the user message
            2. Do NOT change query logic or add new operations
            3. Do NOT rewrite the entire query from scratch
            4. Make minimal, targeted edits only
            5. Preserve field names, operators, and structure unless explicitly incorrect

            COMMON FIXES:
            - Array syntax: frames[0] → frames/0, stackTrace[1] → stackTrace/1
            - Operator syntax: filter(condition) → [condition]
            - Decorator prefix: decorator.field → $decorator.field
            - Event namespaces: ExecutionSample → jdk.ExecutionSample
            - Field names: bytes → bytesRead (for FileRead/SocketRead)
            - Thread fields: eventThread → sampledThread (for ExecutionSample)

            DO NOT:
            - Add new operators not in original
            - Change aggregation functions
            - Modify filter conditions
            - Reorganize query structure

            Output JSON with:
            - query: the repaired query string
            - changes: array of change descriptions ["changed X to Y", ...]
            - confidence: 0.0-1.0 based on certainty of fixes
            - warning: optional warning if repair was uncertain
            """;
  }

  /**
   * Result of query repair.
   *
   * @param query repaired query
   * @param changes list of changes made
   * @param confidence confidence in repair (0.0-1.0)
   * @param warning optional warning message
   */
  public record RepairResult(String query, List<String> changes, double confidence, String warning) {
  }
}
