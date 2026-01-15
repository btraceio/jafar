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
import java.util.ArrayList;
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

  // Event type groups by profiling kind - maps any event type to ALL alternatives in that group
  private static final Map<String, List<String>> EVENT_TYPE_GROUPS =
      Map.ofEntries(
          // CPU profiling group
          Map.entry(
              "jdk.ExecutionSample",
              List.of(
                  "jdk.ExecutionSample",
                  "datadog.ExecutionSample",
                  "jdk.NativeMethodSample",
                  "jdk.CPUTimeSample")),
          Map.entry(
              "datadog.ExecutionSample",
              List.of(
                  "jdk.ExecutionSample",
                  "datadog.ExecutionSample",
                  "jdk.NativeMethodSample",
                  "jdk.CPUTimeSample")),
          Map.entry(
              "jdk.NativeMethodSample",
              List.of(
                  "jdk.ExecutionSample",
                  "datadog.ExecutionSample",
                  "jdk.NativeMethodSample",
                  "jdk.CPUTimeSample")),
          Map.entry(
              "jdk.CPUTimeSample",
              List.of(
                  "jdk.ExecutionSample",
                  "datadog.ExecutionSample",
                  "jdk.NativeMethodSample",
                  "jdk.CPUTimeSample")),
          // Heap profiling group
          Map.entry(
              "jdk.OldObjectSample", List.of("jdk.OldObjectSample", "datadog.HeapLiveObject")),
          Map.entry(
              "datadog.HeapLiveObject", List.of("jdk.OldObjectSample", "datadog.HeapLiveObject")),
          // Allocation profiling group
          Map.entry(
              "jdk.ObjectAllocationInNewTLAB",
              List.of(
                  "jdk.ObjectAllocationInNewTLAB",
                  "jdk.ObjectAllocationSample",
                  "datadog.ObjectSample")),
          Map.entry(
              "jdk.ObjectAllocationSample",
              List.of(
                  "jdk.ObjectAllocationInNewTLAB",
                  "jdk.ObjectAllocationSample",
                  "datadog.ObjectSample")),
          Map.entry(
              "datadog.ObjectSample",
              List.of(
                  "jdk.ObjectAllocationInNewTLAB",
                  "jdk.ObjectAllocationSample",
                  "datadog.ObjectSample")));

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

    // First, ALWAYS try automatic event type group expansion for known profiling events
    RepairResult autoRepair = tryAutomaticEventTypeSubstitution(originalQuery);
    if (autoRepair != null) {
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.out.println("=== AUTO REPAIR (GROUP EXPANSION) ===");
        System.out.println("Original query: " + originalQuery);
        System.out.println("Repaired query: " + autoRepair.query());
        System.out.println("=====================================");
      }
      // Continue with other repairs on the expanded query
      originalQuery = autoRepair.query();
    }

    // Then, try other automatic repairs (projection before groupBy, etc.)
    if (hasNonEventTypeIssues(validation)) {
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.out.println("=== ATTEMPTING OTHER AUTOMATIC REPAIRS ===");
        System.out.println("Query: " + originalQuery);
        System.out.println("Issues: " + validation.issues().size());
        for (var issue : validation.issues()) {
          System.out.println("  - " + issue.type() + ": " + issue.description());
        }
      }
      RepairResult otherRepair = tryOtherAutomaticRepairs(originalQuery, validation);
      if (otherRepair != null) {
        if (Boolean.getBoolean("jfr.shell.debug")) {
          System.out.println("OTHER REPAIRS SUCCEEDED: " + otherRepair.query());
          System.out.println("==========================================");
        }
        return otherRepair;
      }
    }

    // If auto repairs succeeded, return the result
    if (autoRepair != null) {
      return autoRepair;
    }

    // Fall back to LLM-based repair for other issues
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
   * Check if validation has non-event-type issues that can be auto-repaired.
   *
   * @param validation validation result
   * @return true if there are PROJECTION_BEFORE_GROUPBY or other non-event-type issues
   */
  private boolean hasNonEventTypeIssues(ValidationResult validation) {
    return validation.issues().stream()
        .anyMatch(issue -> issue.type() == QueryValidator.IssueType.PROJECTION_BEFORE_GROUPBY);
  }

  /**
   * Try to automatically expand profiling event types to their full group using multi-type syntax.
   * This is ALWAYS applied for known profiling events, regardless of validation.
   *
   * @param query original query
   * @return repair result if substitution occurred, null if no profiling events found
   */
  private RepairResult tryAutomaticEventTypeSubstitution(String query) {
    String repairedQuery = query;
    List<String> changes = new ArrayList<>();

    // Scan query for any known profiling event types
    for (String eventType : EVENT_TYPE_GROUPS.keySet()) {
      String pattern = "events/" + eventType;
      if (repairedQuery.contains(pattern)) {
        // Get all alternatives in the group
        List<String> group = EVENT_TYPE_GROUPS.get(eventType);
        if (group != null) {
          // Replace single type with multi-type syntax: events/type -> events/(type1|type2|type3)
          String multiType = "(" + String.join("|", group) + ")";
          repairedQuery = repairedQuery.replace(pattern, "events/" + multiType);

          if (Boolean.getBoolean("jfr.shell.debug")) {
            System.out.println("  Expanded " + eventType + " to group: " + multiType);
          }
          changes.add("Expanded " + eventType + " to group " + multiType);
        }
      }
    }

    if (!changes.isEmpty()) {
      return new RepairResult(repairedQuery, changes, 0.99, null);
    }

    return null;
  }

  /**
   * Try other automatic repairs (projection before groupBy, etc.).
   *
   * @param query original query
   * @param validation validation result with issues
   * @return repair result if repairs successful, null otherwise
   */
  private RepairResult tryOtherAutomaticRepairs(String query, ValidationResult validation) {
    String repairedQuery = query;
    List<String> changes = new ArrayList<>();

    // Fix projection before groupBy anti-pattern
    for (var issue : validation.issues()) {
      if (issue.type() == QueryValidator.IssueType.PROJECTION_BEFORE_GROUPBY) {
        String fixed = fixProjectionBeforeGroupBy(repairedQuery);
        if (fixed != null) {
          repairedQuery = fixed;
          changes.add("Fixed projection before groupBy: moved field path into groupBy parameter");
        } else {
          return null; // Can't fix automatically
        }
      }
    }

    if (!changes.isEmpty()) {
      return new RepairResult(repairedQuery, changes, 0.95, null);
    }

    return null;
  }

  /**
   * Fix projection before groupBy anti-pattern.
   *
   * <p>Converts: events/type/field1/field2 | groupBy(value) To: events/type |
   * groupBy(field1/field2, agg=count)
   *
   * @param query query with anti-pattern
   * @return fixed query, or null if can't fix automatically
   */
  private String fixProjectionBeforeGroupBy(String query) {
    // Pattern: events/<type>/<field-path> | groupBy(value)
    // Capture: 1=event type, 2=field path (without leading /)
    var pattern =
        java.util.regex.Pattern.compile(
            "events/([^|/]+)(/[^|]+?)\\s*\\|\\s*groupBy\\s*\\(\\s*value\\s*\\)");
    var matcher = pattern.matcher(query);

    if (matcher.find()) {
      String eventType = matcher.group(1).trim();
      String fieldPath = matcher.group(2).substring(1).trim(); // Remove leading / and trim

      // Replace with correct pattern: events/<type> | groupBy(<field-path>, agg=count)
      String corrected = "events/" + eventType + " | groupBy(" + fieldPath + ", agg=count)";
      return matcher.replaceFirst(corrected);
    }

    return null; // Can't parse pattern
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
  public record RepairResult(
      String query, List<String> changes, double confidence, String warning) {}
}
