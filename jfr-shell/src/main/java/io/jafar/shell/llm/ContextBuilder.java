package io.jafar.shell.llm;

import io.jafar.shell.core.SessionManager.SessionRef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds context and prompts for LLM query translation. Provides JfrPath syntax documentation,
 * available event types, session context, and example translations.
 */
public class ContextBuilder {

  private final SessionRef session;
  private final LLMConfig config;

  private static final String PROMPT_BASE = "/llm-prompts/";

  /**
   * Creates a context builder for the given session.
   *
   * @param session active JFR session
   * @param config LLM configuration
   */
  public ContextBuilder(SessionRef session, LLMConfig config) {
    this.session = session;
    this.config = config;
  }

  /**
   * Loads a resource file from the llm-prompts directory.
   *
   * @param path relative path to resource file
   * @return resource content as string
   * @throws IOException if resource cannot be loaded
   */
  private String loadResource(String path) throws IOException {
    try (InputStream is = getClass().getResourceAsStream(PROMPT_BASE + path)) {
      if (is == null) {
        throw new IOException("Resource not found: " + PROMPT_BASE + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Builds the complete system prompt for query translation.
   *
   * @return system prompt
   */
  public String buildSystemPrompt() {
    try {
      StringBuilder prompt = new StringBuilder();

      // Load static sections from resources
      prompt.append(loadResource("system-intro.txt"));
      prompt.append("\n\n");

      prompt.append(loadResource("critical-understanding.txt"));
      prompt.append("\n\n");

      prompt.append(loadResource("important-guidelines.txt"));
      prompt.append("\n\n");

      // Add JfrPath grammar
      prompt.append(loadResource("jfrpath-grammar.txt"));
      prompt.append("\n\n");

      // Dynamic: available event types (stays in code)
      prompt.append("AVAILABLE EVENT TYPES:\n");
      prompt.append(buildEventTypesList());
      prompt.append("\n\n");

      // Dynamic: session context (stays in code)
      prompt.append("CURRENT SESSION:\n");
      prompt.append(buildSessionContext());
      prompt.append("\n\n");

      // Load examples from multiple files
      prompt.append("CORRECT EXAMPLES:\n\n");
      prompt.append(loadResource("examples/basic-queries.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("examples/groupby-top.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("examples/decorator-queries.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("examples/existence-checks.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("examples/metadata-queries.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("examples/yesno-comparison.txt"));
      prompt.append("\n\n");

      prompt.append(loadResource("examples/incorrect-examples.txt"));
      prompt.append("\n\n");

      // Load rules from resources
      prompt.append(loadResource("rules/field-names.txt"));
      prompt.append("\n\n");
      prompt.append(loadResource("rules/decorator-syntax.txt"));
      prompt.append("\n\n");

      // Response format
      prompt.append(loadResource("response-format.txt"));
      prompt.append("\n\n");

      // Query type rules
      prompt.append(loadResource("rules/query-type-rules.txt"));

      return prompt.toString();

    } catch (IOException e) {
      throw new RuntimeException("Failed to load LLM prompt resources", e);
    }
  }

  /**
   * Builds a compact JfrPath grammar reference.
   *
   * @return grammar documentation
   */
  public String buildJfrPathGrammar() {
    try {
      return loadResource("jfrpath-grammar.txt");
    } catch (IOException e) {
      throw new RuntimeException("Failed to load JfrPath grammar resource", e);
    }
  }

  /**
   * Builds a list of available event types in the current session.
   *
   * @return event types list
   */
  public String buildEventTypesList() {
    List<String> eventTypes = new ArrayList<>(session.session.getAvailableEventTypes());

    if (eventTypes.isEmpty()) {
      return "(No event types available - recording may be empty)";
    }

    // Group by common prefixes for readability
    return eventTypes.stream()
        .filter(Objects::nonNull)
        .sorted()
        .limit(50) // Limit to avoid huge prompts
        .collect(Collectors.joining(", "));
  }

  /**
   * Builds session context information.
   *
   * @return session context
   */
  public String buildSessionContext() {
    StringBuilder context = new StringBuilder();

    context.append("Recording: ").append(session.session.getRecordingPath()).append("\n");
    context.append("Event types: ").append(session.session.getAvailableEventTypes().size());

    // Add variable names (but not values for privacy)
    if (!session.variables.isEmpty()) {
      context
          .append("\n")
          .append("Available variables: ")
          .append(String.join(", ", session.variables.names()));
    }

    return context.toString();
  }

  /**
   * Builds example queries for few-shot learning.
   *
   * @return examples
   */
  public String buildExamples() {
    try {
      StringBuilder examples = new StringBuilder();

      examples.append("CORRECT EXAMPLES:\n\n");
      examples.append(loadResource("examples/basic-queries.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/groupby-top.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/decorator-queries.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/existence-checks.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/metadata-queries.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/yesno-comparison.txt"));
      examples.append("\n\n");
      examples.append(loadResource("examples/incorrect-examples.txt"));
      examples.append("\n\n");
      examples.append(loadResource("rules/field-names.txt"));
      examples.append("\n\n");
      examples.append(loadResource("rules/decorator-syntax.txt"));

      return examples.toString();

    } catch (IOException e) {
      throw new RuntimeException("Failed to load example resources", e);
    }
  }

  /**
   * Builds response format specification.
   *
   * @return format specification
   */
  public String buildResponseFormat() {
    try {
      return loadResource("response-format.txt");
    } catch (IOException e) {
      throw new RuntimeException("Failed to load response format resource", e);
    }
  }

  /**
   * Builds translation rules.
   *
   * @return rules
   */
  public String buildRules() {
    try {
      return loadResource("rules/query-type-rules.txt");
    } catch (IOException e) {
      throw new RuntimeException("Failed to load rules resource", e);
    }
  }

  /**
   * Gets available event types for context inclusion.
   *
   * @return list of event type names
   */
  public List<String> getAvailableEventTypes() {
    return new ArrayList<>(session.session.getAvailableEventTypes());
  }

  // ===== Multi-Level Prompt Building (Phase 3 Implementation) =====

  /**
   * Builds a minimal category-specific prompt (~2-3KB).
   *
   * <p>Includes: system intro, grammar, event types, session context, category-specific
   * examples/rules, and response format.
   *
   * @param category the query category
   * @return minimal prompt string
   * @throws IOException if resource loading fails
   */
  public String buildMinimalPrompt(QueryCategory category) throws IOException {
    StringBuilder prompt = new StringBuilder();

    // Core system components
    prompt.append(loadResource("system-intro.txt"));
    prompt.append("\n\n");

    prompt.append(loadResource("critical-understanding.txt"));
    prompt.append("\n\n");

    prompt.append(loadResource("jfrpath-grammar.txt"));
    prompt.append("\n\n");

    // Dynamic: available event types
    prompt.append("AVAILABLE EVENT TYPES:\n");
    prompt.append(buildEventTypesList());
    prompt.append("\n\n");

    // Dynamic: session context
    prompt.append("CURRENT SESSION:\n");
    prompt.append(buildSessionContext());
    prompt.append("\n\n");

    // Category-specific examples
    prompt.append("EXAMPLES FOR THIS QUERY TYPE:\n\n");
    prompt.append(loadCategoryExamples(category));
    prompt.append("\n\n");

    // Category-specific rules (if they exist)
    String rules = loadCategoryRulesIfExists(category);
    if (!rules.isEmpty()) {
      prompt.append("CATEGORY-SPECIFIC RULES:\n\n");
      prompt.append(rules);
      prompt.append("\n\n");
    }

    // Response format
    prompt.append(loadResource("response-format.txt"));

    return prompt.toString();
  }

  /**
   * Builds an enhanced prompt with related categories (~6-8KB).
   *
   * <p>Includes everything from minimal plus related category examples, shared incorrect examples,
   * and decorator rules if needed.
   *
   * @param category the primary query category
   * @param relatedCategories related categories to include
   * @return enhanced prompt string
   * @throws IOException if resource loading fails
   */
  public String buildEnhancedPrompt(QueryCategory category, Set<QueryCategory> relatedCategories)
      throws IOException {
    StringBuilder prompt = new StringBuilder();

    // Core system components
    prompt.append(loadResource("system-intro.txt"));
    prompt.append("\n\n");

    prompt.append(loadResource("critical-understanding.txt"));
    prompt.append("\n\n");

    prompt.append(loadResource("important-guidelines.txt"));
    prompt.append("\n\n");

    prompt.append(loadResource("jfrpath-grammar.txt"));
    prompt.append("\n\n");

    // Dynamic: available event types
    prompt.append("AVAILABLE EVENT TYPES:\n");
    prompt.append(buildEventTypesList());
    prompt.append("\n\n");

    // Dynamic: session context
    prompt.append("CURRENT SESSION:\n");
    prompt.append(buildSessionContext());
    prompt.append("\n\n");

    // Primary category examples
    prompt.append("EXAMPLES FOR PRIMARY QUERY TYPE:\n\n");
    prompt.append(loadCategoryExamples(category));
    prompt.append("\n\n");

    // Related category examples
    if (!relatedCategories.isEmpty()) {
      prompt.append("RELATED QUERY TYPES:\n\n");
      for (QueryCategory related : relatedCategories) {
        prompt.append("--- ").append(related.name()).append(" ---\n");
        prompt.append(loadCategoryExamples(related));
        prompt.append("\n");
      }
      prompt.append("\n");
    }

    // Shared incorrect examples
    prompt.append(loadResource("incorrect-examples.txt"));
    prompt.append("\n\n");

    // Category-specific rules
    String rules = loadCategoryRulesIfExists(category);
    if (!rules.isEmpty()) {
      prompt.append("CATEGORY-SPECIFIC RULES:\n\n");
      prompt.append(rules);
      prompt.append("\n\n");
    }

    // Decorator syntax rules if category needs decorators
    if (category.needsDecorator()) {
      prompt.append(loadResource("rules/decorator-syntax.txt"));
      prompt.append("\n\n");
    }

    // Field name rules (important for all categories)
    prompt.append(loadResource("rules/field-names.txt"));
    prompt.append("\n\n");

    // Response format
    prompt.append(loadResource("response-format.txt"));

    return prompt.toString();
  }

  /**
   * Loads examples for a specific category.
   *
   * @param category the query category
   * @return category examples as string
   * @throws IOException if resource cannot be loaded
   */
  private String loadCategoryExamples(QueryCategory category) throws IOException {
    return loadResource(category.getExamplesPath());
  }

  /**
   * Loads rules for a specific category if they exist.
   *
   * @param category the query category
   * @return category rules, or empty string if no rules file exists
   */
  private String loadCategoryRulesIfExists(QueryCategory category) {
    try {
      return loadResource(category.getRulesPath());
    } catch (IOException e) {
      // No rules file for this category - that's okay
      return "";
    }
  }
}
