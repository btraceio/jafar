package io.jafar.shell.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classifies natural language queries into categories using hybrid rule-based and LLM approaches.
 *
 * <p>Classification flow:
 *
 * <ol>
 *   <li>Try fast rule-based pattern matching (~85% of queries)
 *   <li>Fall back to LLM classification for ambiguous queries
 * </ol>
 */
public class QueryClassifier {
  private final LLMProvider provider;
  private final Gson gson;

  // Pattern matchers for rule-based classification
  private static final Pattern EXISTENCE_PATTERN =
      Pattern.compile(
          "^(is|are|does|do|did)\\s+(there\\s+)?(any\\s+)?.*\\s+(present|exist|available|recorded|in\\s+(the\\s+)?recording)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern METADATA_PATTERN =
      Pattern.compile(
          "^(what\\s+fields?|describe|show\\s+(the\\s+)?structure|show\\s+(the\\s+)?metadata|list\\s+(the\\s+)?fields)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SIMPLE_COUNT_PATTERN =
      Pattern.compile("^(count|how\\s+many\\s+total)\\s+[^,]+$", Pattern.CASE_INSENSITIVE);

  private static final Pattern TOPN_PATTERN =
      Pattern.compile(
          "^(top|hottest|most|slowest|longest|largest|biggest|highest|worst)\\s+\\d*\\s*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern STATISTICS_PATTERN =
      Pattern.compile(
          "^(average|mean|total|sum|stats|statistics|min(imum)?|max(imum)?|median)\\s+",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern DECORATOR_TEMPORAL_PATTERN =
      Pattern.compile(".*(during|while|when|causing|triggered).*", Pattern.CASE_INSENSITIVE);

  private static final Pattern DECORATOR_CORRELATION_PATTERN =
      Pattern.compile(
          ".*(requestId|spanId|traceId|transactionId|sessionId|correlat).*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern SIMPLE_FILTER_PATTERN =
      Pattern.compile(
          "^(show|list|get|find)\\s+.*\\s+(over|above|below|under|greater|less|where|with).*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern GROUPBY_SIMPLE_PATTERN =
      Pattern.compile(
          "^(how\\s+many\\s+(different|unique|distinct)|unique|distinct).*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern GROUPBY_AGGREGATED_PATTERN =
      Pattern.compile(".*\\s+by\\s+.*", Pattern.CASE_INSENSITIVE);

  private static final Pattern TIME_RANGE_PATTERN =
      Pattern.compile(
          ".*(between|after|before|longer\\s+than|shorter\\s+than|duration).*",
          Pattern.CASE_INSENSITIVE);

  public QueryClassifier(LLMProvider provider) {
    this.provider = provider;
    this.gson = new Gson();
  }

  /**
   * Classifies a query using hybrid approach: rules first, LLM fallback.
   *
   * @param query the natural language query
   * @return classification result with category and confidence
   * @throws LLMException if LLM classification fails
   */
  public ClassificationResult classify(String query) throws LLMException {
    // Try rule-based classification first
    Optional<ClassificationResult> ruleResult = classifyByRules(query);
    if (ruleResult.isPresent()) {
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.err.println("=== CLASSIFICATION (RULE-BASED) ===");
        System.err.println("Query: " + query);
        System.err.println("Category: " + ruleResult.get().category());
        System.err.println("Confidence: " + ruleResult.get().confidence());
        System.err.println("====================================");
      }
      return ruleResult.get();
    }

    // Fall back to LLM for ambiguous queries
    ClassificationResult llmResult = classifyByLLM(query);
    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.err.println("=== CLASSIFICATION (LLM) ===");
      System.err.println("Query: " + query);
      System.err.println("Category: " + llmResult.category());
      System.err.println("Confidence: " + llmResult.confidence());
      System.err.println("Reasoning: " + llmResult.reasoning().orElse("none"));
      System.err.println("=============================");
    }
    return llmResult;
  }

  /**
   * Classifies query using fast rule-based pattern matching.
   *
   * @param query the natural language query
   * @return classification result if confident, empty otherwise
   */
  public Optional<ClassificationResult> classifyByRules(String query) {
    String normalized = query.toLowerCase().trim();

    // Priority order (more specific first to avoid false positives)

    // METADATA_QUERY - very specific pattern
    if (METADATA_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.METADATA_QUERY, 0.95));
    }

    // EXISTENCE_CHECK - specific pattern
    if (EXISTENCE_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.EXISTENCE_CHECK, 0.92));
    }

    // DECORATOR_CORRELATION - check for correlation IDs
    if (DECORATOR_CORRELATION_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.DECORATOR_CORRELATION, 0.88));
    }

    // DECORATOR_TEMPORAL - check for temporal keywords
    if (DECORATOR_TEMPORAL_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.DECORATOR_TEMPORAL, 0.87));
    }

    // TOPN_RANKING - strong indicator
    if (TOPN_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.TOPN_RANKING, 0.90));
    }

    // STATISTICS - strong indicator
    if (STATISTICS_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.STATISTICS, 0.89));
    }

    // GROUPBY_AGGREGATED - check for "by" pattern with aggregation keywords
    if (GROUPBY_AGGREGATED_PATTERN.matcher(normalized).find()
        && (normalized.contains("total")
            || normalized.contains("sum")
            || normalized.contains("count")
            || normalized.contains("average")
            || normalized.contains("max")
            || normalized.contains("min"))) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.GROUPBY_AGGREGATED, 0.86));
    }

    // GROUPBY_SIMPLE - unique/distinct patterns
    if (GROUPBY_SIMPLE_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.GROUPBY_SIMPLE, 0.88));
    }

    // TIME_RANGE_FILTER - temporal filtering keywords
    if (TIME_RANGE_PATTERN.matcher(normalized).find()
        && !DECORATOR_TEMPORAL_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.TIME_RANGE_FILTER, 0.85));
    }

    // SIMPLE_FILTER - show/list with conditions
    if (SIMPLE_FILTER_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.SIMPLE_FILTER, 0.84));
    }

    // SIMPLE_COUNT - basic count pattern (strict match to avoid false positives)
    if (SIMPLE_COUNT_PATTERN.matcher(normalized).find()) {
      return Optional.of(ClassificationResult.fromRules(QueryCategory.SIMPLE_COUNT, 0.91));
    }

    // Ambiguous - needs LLM
    return Optional.empty();
  }

  /**
   * Classifies query using LLM when rules are insufficient.
   *
   * @param query the natural language query
   * @return classification result from LLM
   * @throws LLMException if LLM call fails
   */
  public ClassificationResult classifyByLLM(String query) throws LLMException {
    String systemPrompt = buildClassificationPrompt();

    String userMessage =
        String.format(
            "Classify this query:\n\n\"%s\"\n\nRespond with JSON only, no other text.", query);

    // Low temperature for deterministic classification
    Map<String, Object> options = Map.of("temperature", 0.1, "max_tokens", 500);

    LLMProvider.LLMRequest request =
        new LLMProvider.LLMRequest(
            systemPrompt,
            List.of(new LLMProvider.Message(LLMProvider.Role.USER, userMessage)),
            options);

    LLMProvider.LLMResponse response =
        provider.completeStructured(
            request, io.jafar.shell.llm.schemas.ResponseSchemas.CLASSIFICATION);
    String content = response.content();

    // Parse JSON response
    try {
      JsonObject json = gson.fromJson(content, JsonObject.class);
      String categoryStr = json.get("category").getAsString();
      double confidence = json.get("confidence").getAsDouble();
      String reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "";

      QueryCategory category = QueryCategory.valueOf(categoryStr);
      return ClassificationResult.fromLLM(category, confidence, reasoning);

    } catch (Exception e) {
      throw new LLMException(
          LLMException.ErrorType.PARSE_ERROR,
          "Failed to parse LLM classification response: " + content + " - " + e.getMessage(),
          e);
    }
  }

  /**
   * Builds the classification system prompt.
   *
   * @return system prompt for LLM classification
   */
  private String buildClassificationPrompt() {
    return """
You are a query classifier for JFR (Java Flight Recording) analysis. Classify the natural language query into ONE category.

CATEGORIES:
- SIMPLE_COUNT: Count total events of a type (e.g., "count GC events")
- EXISTENCE_CHECK: Check if events exist (e.g., "is ExecutionSample present?")
- METADATA_QUERY: Inspect event structure (e.g., "what fields does ExecutionSample have?")
- SIMPLE_FILTER: Filter without aggregation (e.g., "show file reads over 1MB")
- STATISTICS: Statistical aggregation (e.g., "average GC pause time")
- GROUPBY_SIMPLE: Group without top-N (e.g., "how many different threads")
- GROUPBY_AGGREGATED: Group with aggregation (e.g., "total bytes by thread")
- TOPN_RANKING: Top-N ranking queries (e.g., "top 10 allocating classes")
- TIME_RANGE_FILTER: Temporal filtering (e.g., "GCs longer than 50ms")
- DECORATOR_TEMPORAL: Temporal correlation (e.g., "methods during pinning")
- DECORATOR_CORRELATION: ID-based correlation (e.g., "samples with requestId")
- COMPLEX_MULTIOP: Complex multi-operation queries
- CONVERSATIONAL: Not a data query (e.g., "hello", "help")

CLASSIFICATION RULES:
1. CONVERSATIONAL for greetings, help requests, or non-analytical questions
2. METADATA_QUERY for structure/field inspection queries
3. EXISTENCE_CHECK for "is X present/available/recorded" questions
4. SIMPLE_COUNT for pure counting without filters
5. TOPN_RANKING for "top N", "hottest", "most", "slowest" queries
6. DECORATOR_TEMPORAL for "during", "while", "when", "causing" patterns
7. DECORATOR_CORRELATION for requestId/spanId/traceId/correlation queries
8. GROUPBY patterns require "by" keyword or "total/sum/count per X"
9. STATISTICS for average/mean/total/sum/min/max/stats queries
10. TIME_RANGE_FILTER for "between", "after", "before", "longer than" temporal filters
11. COMPLEX_MULTIOP for queries requiring multiple operations or nested queries

Respond with JSON:
{
  "category": "<category>",
  "confidence": <0.0-1.0>,
  "reasoning": "<brief explanation>"
}

Example:
Query: "top 10 threads by CPU time"
Response: {"category": "TOPN_RANKING", "confidence": 0.95, "reasoning": "Clear top-N pattern with ranking criterion"}
""";
  }
}
