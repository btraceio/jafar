package io.jafar.shell.llm;

import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.providers.MetadataProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  // Cache for event fields reference by prompt level
  private final Map<PromptStrategy.PromptLevel, String> eventFieldsCache = new HashMap<>();

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

      // Dynamic: event fields with importance-based prioritization
      prompt.append(buildEventFieldsReference(PromptStrategy.PromptLevel.FULL));
      prompt.append("\n\n");

      // Dynamic: session context (stays in code)
      prompt.append("CURRENT SESSION:\n");
      prompt.append(buildSessionContext());
      prompt.append("\n\n");

      // Event type selection guide
      prompt.append(loadResource("event-type-selection.txt"));
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
      prompt.append(loadResource("rules/unit-conversions.txt"));
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

    var eventTypes = session.session.getAvailableEventTypes();
    context.append("Total event types: ").append(eventTypes.size()).append("\n");

    // List profiling-relevant event types explicitly (critical for choosing correct events)
    context.append("\nProfiling events present:\n");

    // CPU/Execution profiling
    boolean hasJdkExecution = eventTypes.contains("jdk.ExecutionSample");
    boolean hasDatadogExecution = eventTypes.contains("datadog.ExecutionSample");
    boolean hasCpuTimeSample = eventTypes.contains("jdk.CPUTimeSample");

    if (hasJdkExecution || hasDatadogExecution || hasCpuTimeSample) {
      context.append("  CPU profiling: ");
      if (hasJdkExecution) context.append("jdk.ExecutionSample ");
      if (hasDatadogExecution) context.append("datadog.ExecutionSample ");
      if (hasCpuTimeSample) context.append("jdk.CPUTimeSample ");
      context.append("\n");
    } else {
      context.append("  CPU profiling: NONE\n");
    }

    // Heap profiling
    boolean hasOldObjectSample = eventTypes.contains("jdk.OldObjectSample");
    boolean hasHeapLiveObject = eventTypes.contains("datadog.HeapLiveObject");

    if (hasOldObjectSample || hasHeapLiveObject) {
      context.append("  Heap profiling: ");
      if (hasOldObjectSample) context.append("jdk.OldObjectSample ");
      if (hasHeapLiveObject) context.append("datadog.HeapLiveObject ");
      context.append("\n");
    } else {
      context.append("  Heap profiling: NONE\n");
    }

    // Allocation profiling
    boolean hasAllocationSample = eventTypes.contains("jdk.ObjectAllocationSample");
    boolean hasAllocationInNewTLAB = eventTypes.contains("jdk.ObjectAllocationInNewTLAB");
    boolean hasDatadogAllocation = eventTypes.contains("datadog.ObjectSample");

    if (hasAllocationSample || hasAllocationInNewTLAB || hasDatadogAllocation) {
      context.append("  Allocation profiling: ");
      if (hasAllocationSample) context.append("jdk.ObjectAllocationSample ");
      if (hasAllocationInNewTLAB) context.append("jdk.ObjectAllocationInNewTLAB ");
      if (hasDatadogAllocation) context.append("datadog.ObjectSample ");
      context.append("\n");
    }

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

    // Dynamic: event fields (minimal set)
    prompt.append(buildEventFieldsReference(PromptStrategy.PromptLevel.MINIMAL));
    prompt.append("\n\n");

    // Dynamic: session context
    prompt.append("CURRENT SESSION:\n");
    prompt.append(buildSessionContext());
    prompt.append("\n\n");

    // Event type selection guide
    prompt.append(loadResource("event-type-selection.txt"));
    prompt.append("\n\n");

    // Unit conversions
    prompt.append(loadResource("rules/unit-conversions.txt"));
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

    // Dynamic: event fields (enhanced set with annotations)
    prompt.append(buildEventFieldsReference(PromptStrategy.PromptLevel.ENHANCED));
    prompt.append("\n\n");

    // Dynamic: session context
    prompt.append("CURRENT SESSION:\n");
    prompt.append(buildSessionContext());
    prompt.append("\n\n");

    // Event type selection guide
    prompt.append(loadResource("event-type-selection.txt"));
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

    // Unit conversions (critical for accuracy)
    prompt.append(loadResource("rules/unit-conversions.txt"));
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

  /**
   * Builds a dynamic event fields reference based on available metadata.
   *
   * @param level the prompt level determining detail depth
   * @return formatted event fields documentation
   */
  private String buildEventFieldsReference(PromptStrategy.PromptLevel level) {
    return eventFieldsCache.computeIfAbsent(level, l -> generateEventFieldsReference(l));
  }

  /**
   * Generates event fields reference based on metadata.
   *
   * @param level the prompt level
   * @return formatted event fields string
   */
  private String generateEventFieldsReference(PromptStrategy.PromptLevel level) {
    try {
      StringBuilder sb = new StringBuilder();
      sb.append("AVAILABLE EVENT FIELDS:\n\n");

      Map<String, Long> eventCounts = session.session.getEventTypeCounts();
      Path recordingPath = session.session.getRecordingPath();

      // Check for null or empty recording
      if (eventCounts == null || eventCounts.isEmpty()) {
        return "AVAILABLE EVENT FIELDS:\n"
            + "(No events in recording - metadata not available)\n\n";
      }

      // Determine how many events and fields to include
      int maxEvents;
      int maxFields;
      switch (level) {
        case MINIMAL:
          maxEvents = 5;
          maxFields = 5;
          break;
        case ENHANCED:
          maxEvents = 15;
          maxFields = 12;
          break;
        case FULL:
          maxEvents = 30;
          maxFields = -1; // All important fields
          break;
        default:
          maxEvents = 10;
          maxFields = 8;
      }

      // Get top N events by occurrence
      List<String> topEvents =
          eventCounts.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(maxEvents)
              .map(Map.Entry::getKey)
              .collect(Collectors.toList());

      // Build field reference for each event
      for (String eventType : topEvents) {
        try {
          Map<String, Object> metadata = MetadataProvider.loadClass(recordingPath, eventType);

          if (metadata == null) {
            continue;
          }

          sb.append(eventType).append(":\n");

          @SuppressWarnings("unchecked")
          Map<String, Map<String, Object>> fieldsByName =
              (Map<String, Map<String, Object>>) metadata.get("fieldsByName");

          if (fieldsByName != null) {
            List<FieldInfo> fields = buildFieldList(fieldsByName, eventType);

            // Sort by importance
            fields.sort((a, b) -> Integer.compare(b.importance, a.importance));

            // Take top N fields
            int fieldCount = maxFields < 0 ? fields.size() : Math.min(maxFields, fields.size());

            for (int i = 0; i < fieldCount; i++) {
              FieldInfo field = fields.get(i);
              sb.append("  - ")
                  .append(field.name)
                  .append(": ")
                  .append(field.type)
                  .append(field.arraySuffix);

              // Add important annotations in ENHANCED and FULL
              if (level != PromptStrategy.PromptLevel.MINIMAL && !field.annotations.isEmpty()) {
                sb.append(" ").append(String.join(" ", field.annotations));
              }

              sb.append("\n");
            }
          }

          sb.append("\n");

        } catch (Exception e) {
          // Skip problematic events, continue with others
          if (Boolean.getBoolean("jfr.shell.debug")) {
            System.err.println("Failed to load metadata for " + eventType + ": " + e.getMessage());
          }
        }
      }

      return sb.toString();

    } catch (Exception e) {
      // If metadata extraction fails, return minimal info
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.err.println("Failed to build event fields reference: " + e.getMessage());
      }
      return "AVAILABLE EVENT FIELDS:\n"
          + "(Metadata extraction unavailable - using runtime discovery)\n\n";
    }
  }

  /**
   * Builds a list of FieldInfo from metadata map.
   *
   * @param fieldsByName map of field name to field metadata
   * @param eventType the event type name
   * @return list of field information
   */
  private List<FieldInfo> buildFieldList(
      Map<String, Map<String, Object>> fieldsByName, String eventType) {

    List<FieldInfo> result = new ArrayList<>();

    for (Map.Entry<String, Map<String, Object>> entry : fieldsByName.entrySet()) {
      String fieldName = entry.getKey();
      Map<String, Object> fieldMeta = entry.getValue();

      String type = String.valueOf(fieldMeta.get("type"));
      int dimension = (Integer) fieldMeta.getOrDefault("dimension", -1);
      String arraySuffix = dimension > 0 ? "[]".repeat(dimension) : "";

      @SuppressWarnings("unchecked")
      List<String> annotations =
          (List<String>) fieldMeta.getOrDefault("annotations", Collections.emptyList());

      int importance =
          calculateFieldImportanceFromMetadata(fieldName, type, annotations, eventType);

      result.add(new FieldInfo(fieldName, type, arraySuffix, importance, annotations));
    }

    return result;
  }

  /**
   * Calculates field importance based on metadata.
   *
   * @param fieldName the field name
   * @param fieldType the field type
   * @param annotations list of annotation strings
   * @param eventType the event type name
   * @return importance score (0-100+)
   */
  private int calculateFieldImportanceFromMetadata(
      String fieldName, String fieldType, List<String> annotations, String eventType) {

    int score = 0;
    String lowerName = fieldName.toLowerCase();
    String lowerType = fieldType.toLowerCase();

    // Temporal fields (critical)
    if (lowerName.equals("starttime")
        || lowerName.equals("endtime")
        || lowerName.equals("duration")) {
      score += 100;
    }

    // Thread references
    if (lowerName.contains("thread") && !lowerType.equals("long")) {
      score += 90;
    }

    // Stack traces
    if (lowerName.equals("stacktrace")) {
      score += 85;
    }

    // Size/bytes fields
    if (lowerName.contains("bytes")
        || lowerName.contains("size")
        || lowerName.contains("allocated")
        || lowerName.contains("weight")) {
      score += 80;
    }

    // Common thread field names
    if (lowerName.equals("eventthread") || lowerName.equals("sampledthread")) {
      score += 75;
    }

    // Class references
    if (lowerName.contains("class") && !lowerType.equals("long")) {
      score += 70;
    }

    // Method references
    if (lowerName.contains("method")) {
      score += 65;
    }

    // Names and identifiers
    if (lowerName.equals("name") || lowerName.equals("id")) {
      score += 60;
    }

    // Path fields (File I/O)
    if (lowerName.equals("path")) {
      score += 55;
    }

    // Annotated fields (Label, Description indicate importance)
    for (String ann : annotations) {
      if (ann.contains("Label") || ann.contains("Description")) {
        score += 20;
      }
      if (ann.contains("Timespan") || ann.contains("DataAmount")) {
        score += 15;
      }
    }

    // Penalize internal/diagnostic fields
    if (lowerName.startsWith("_") || lowerName.contains("internal")) {
      score -= 50;
    }

    return score;
  }

  /** Helper class to hold field information with importance score. */
  private static class FieldInfo {
    final String name;
    final String type;
    final String arraySuffix;
    final int importance;
    final List<String> annotations;

    FieldInfo(
        String name, String type, String arraySuffix, int importance, List<String> annotations) {
      this.name = name;
      this.type = type;
      this.arraySuffix = arraySuffix;
      this.importance = importance;
      this.annotations = annotations;
    }
  }
}
