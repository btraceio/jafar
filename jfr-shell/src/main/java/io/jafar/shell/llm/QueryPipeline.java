package io.jafar.shell.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.schemas.ResponseSchemas;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-step pipeline for reliable JfrPath query generation.
 *
 * <p>Pipeline steps: CLASSIFY → CLARIFY (conditional) → GENERATE → VALIDATE/REPAIR (conditional)
 *
 * <p>This pipeline decomposes query translation into focused steps with structured outputs, making
 * it more reliable for small (7-8B parameter) models.
 */
public class QueryPipeline {

  private final LLMProvider provider;
  private final QueryClassifier classifier;
  private final QueryClarifier clarifier;
  private final ContextBuilder contextBuilder;
  private final ExampleRetriever exampleRetriever;
  private final QueryValidator validator;
  private final QueryRepairer repairer;
  private final LLMConfig config;
  private final Gson gson = new Gson();

  // Thresholds for conditional steps
  private static final double CLARIFY_THRESHOLD = 0.5;
  private static final double REPAIR_THRESHOLD = 0.7;

  /**
   * Creates a query pipeline with the given LLM provider and session.
   *
   * @param provider LLM provider
   * @param session JFR session for context
   * @param config LLM configuration
   */
  public QueryPipeline(LLMProvider provider, SessionRef session, LLMConfig config) {
    this.provider = provider;
    this.config = config;
    this.classifier = new QueryClassifier(provider);
    this.clarifier = new QueryClarifier(provider);
    this.contextBuilder = new ContextBuilder(session, config);
    this.exampleRetriever = new ExampleRetriever(4); // max 4 examples

    // Get available event types from session metadata for validation
    var availableEventTypes = session.session.getAvailableEventTypes();

    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.out.println("=== PIPELINE INIT ===");
      System.out.println("Total event types in metadata: " + availableEventTypes.size());
      var profilingEvents =
          availableEventTypes.stream()
              .filter(
                  e ->
                      e.contains("ExecutionSample")
                          || e.contains("HeapLiveObject")
                          || e.contains("OldObjectSample")
                          || e.contains("ObjectSample"))
              .sorted()
              .toList();
      System.out.println("Profiling events available: " + profilingEvents);
      System.out.println("=====================");
    }
    this.validator = new QueryValidator(availableEventTypes);
    this.repairer = new QueryRepairer(provider);
  }

  /**
   * Main entry point: generate JfrPath query from natural language.
   *
   * @param userQuestion natural language query
   * @param context optional context (conversation history, clarification answer, etc.)
   * @return pipeline result with query OR needs_clarification
   * @throws LLMException if pipeline execution fails
   */
  public PipelineResult generateJfrPath(String userQuestion, PipelineContext context)
      throws LLMException {

    boolean debug = Boolean.getBoolean("jfr.shell.debug");
    PipelineTrace trace = debug ? new PipelineTrace() : null;

    try {
      // STEP 1: CLASSIFY
      ClassificationResult classification = stepClassify(userQuestion, trace);

      // STEP 2: CLARIFY (conditional)
      if (shouldClarify(classification)) {
        return stepClarify(userQuestion, classification, trace);
      }

      // STEP 3: GENERATE
      GenerationResult generation = stepGenerate(userQuestion, classification, context, trace);

      // STEP 4: ALWAYS REPAIR (to expand profiling event groups + fix validation issues)
      generation = stepRepair(generation, trace);

      return PipelineResult.success(
          generation.query(),
          generation.explanation(),
          generation.confidence(),
          generation.warning(),
          trace);

    } catch (LLMException e) {
      // Re-throw LLM exceptions
      throw e;
    } catch (Exception e) {
      throw new LLMException(
          LLMException.ErrorType.NETWORK_ERROR, "Pipeline execution failed: " + e.getMessage(), e);
    }
  }

  /**
   * STEP 1: Classify the user query into a category.
   *
   * @param query user query
   * @param trace optional trace
   * @return classification result
   * @throws LLMException if classification fails
   */
  private ClassificationResult stepClassify(String query, PipelineTrace trace) throws LLMException {

    long start = System.currentTimeMillis();
    ClassificationResult result = classifier.classify(query);

    if (trace != null) {
      trace.addStep(
          "CLASSIFY",
          "category=" + result.category() + ", confidence=" + result.confidence(),
          System.currentTimeMillis() - start);
    }

    return result;
  }

  /**
   * Check if clarification is needed based on classification confidence.
   *
   * @param classification classification result
   * @return true if clarification needed
   */
  private boolean shouldClarify(ClassificationResult classification) {
    return classification.confidence() < CLARIFY_THRESHOLD;
  }

  /**
   * STEP 2: Generate clarification question for ambiguous query.
   *
   * @param query user query
   * @param classification classification result
   * @param trace optional trace
   * @return pipeline result with clarification request
   * @throws LLMException if clarification generation fails
   */
  private PipelineResult stepClarify(
      String query, ClassificationResult classification, PipelineTrace trace) throws LLMException {

    long start = System.currentTimeMillis();
    QueryClarifier.ClarificationResult clarification =
        clarifier.clarify(query, classification.category());

    if (trace != null) {
      trace.addStep(
          "CLARIFY",
          "ambiguity=" + clarification.ambiguityScore(),
          System.currentTimeMillis() - start);
    }

    return PipelineResult.needsClarification(
        new ClarificationRequest(
            query,
            clarification.question(),
            clarification.choices(),
            clarification.ambiguityScore()),
        trace);
  }

  /**
   * STEP 3: Generate JfrPath query.
   *
   * @param query user query
   * @param classification classification result
   * @param context pipeline context
   * @param trace optional trace
   * @return generation result
   * @throws LLMException if generation fails
   */
  private GenerationResult stepGenerate(
      String query,
      ClassificationResult classification,
      PipelineContext context,
      PipelineTrace trace)
      throws LLMException {

    long start = System.currentTimeMillis();

    // Retrieve relevant examples
    List<ExampleRetriever.Example> examples =
        exampleRetriever.retrieve(classification.category(), query);

    // Build category-specific prompt with session context and guidance
    String prompt = buildGeneratorPrompt(classification.category(), examples);

    // Generate query
    LLMProvider.LLMRequest request =
        new LLMProvider.LLMRequest(
            prompt,
            List.of(new LLMProvider.Message(LLMProvider.Role.USER, query)),
            Map.of("temperature", config.temperature(), "max_tokens", config.maxTokens()));

    LLMProvider.LLMResponse response =
        provider.completeStructured(request, ResponseSchemas.GENERATION);

    JsonObject json = gson.fromJson(response.content(), JsonObject.class);

    GenerationResult result =
        new GenerationResult(
            json.get("query").getAsString(),
            json.get("explanation").getAsString(),
            json.get("confidence").getAsDouble(),
            json.has("warning") ? json.get("warning").getAsString() : null);

    if (trace != null) {
      trace.addStep(
          "GENERATE",
          "confidence=" + result.confidence() + ", examples=" + examples.size(),
          System.currentTimeMillis() - start,
          prompt.length());
    }

    return result;
  }

  /**
   * Build generator prompt for category with session context and guidance.
   *
   * @param category query category
   * @param examples selected examples (2-4)
   * @return generator prompt with context, guidance, template, and examples
   */
  private String buildGeneratorPrompt(
      QueryCategory category, List<ExampleRetriever.Example> examples) {
    StringBuilder prompt = new StringBuilder();

    // Session context (available event types, recording info)
    prompt.append("CURRENT SESSION:\n");
    prompt.append(contextBuilder.buildSessionContext());
    prompt.append("\n\n");

    // CRITICAL: Short event type selection rules (not the full guide - too long)
    prompt.append("EVENT TYPE SELECTION:\n");
    prompt.append(
        "- CPU profiling: Use the ExecutionSample event shown in CURRENT SESSION above\n");
    prompt.append(
        "- Heap profiling: Use the OldObjectSample or HeapLiveObject shown in CURRENT SESSION above\n");
    prompt.append("- DO NOT assume jdk.ExecutionSample exists - check CURRENT SESSION!\n");
    prompt.append("- Use ONLY event types listed as present in CURRENT SESSION\n\n");

    // Load category template from resources
    String templatePath =
        "/llm-prompts/pipeline/generator/" + category.name().toLowerCase() + ".txt";
    try (var is = getClass().getResourceAsStream(templatePath)) {
      if (is != null) {
        prompt.append(new String(is.readAllBytes()));
      } else {
        // Fallback if template not found
        prompt.append("Generate a JfrPath query for category: ").append(category);
      }
    } catch (Exception e) {
      // Use fallback
      prompt.append("Generate a JfrPath query for category: ").append(category);
    }

    prompt.append("\n\n");

    // Insert examples
    if (!examples.isEmpty()) {
      prompt.append("RELEVANT EXAMPLES:\n\n");
      for (ExampleRetriever.Example ex : examples) {
        prompt.append("Q: ").append(ex.question()).append("\n");
        prompt.append("A: ").append(ex.answer()).append("\n\n");
      }
    }

    return prompt.toString();
  }

  /**
   * Check if repair is needed based on generation confidence or validation.
   *
   * @param generation generation result
   * @return true if repair needed
   */
  private boolean shouldRepair(GenerationResult generation) {
    // Repair if: (1) low confidence, OR (2) validation fails
    if (generation.confidence() < REPAIR_THRESHOLD) {
      if (Boolean.getBoolean("jfr.shell.debug")) {
        System.out.println(
            "=== SHOULD REPAIR: Low confidence ("
                + generation.confidence()
                + " < "
                + REPAIR_THRESHOLD
                + ") ===");
      }
      return true;
    }

    QueryValidator.ValidationResult validation = validator.validate(generation.query());
    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.out.println("=== VALIDATION ===");
      System.out.println("Query: " + generation.query());
      System.out.println("Valid: " + validation.valid());
      System.out.println("Needs repair: " + validation.needsRepair());
      System.out.println("Issues: " + validation.issues().size());
      for (var issue : validation.issues()) {
        System.out.println("  - " + issue.type() + ": " + issue.description());
      }
      System.out.println("==================");
    }
    return validation.needsRepair();
  }

  /**
   * STEP 4: Always repair to expand event type groups and fix any validation issues.
   *
   * @param original original generation result
   * @param trace optional trace
   * @return repaired generation result
   * @throws LLMException if repair fails
   */
  private GenerationResult stepRepair(GenerationResult original, PipelineTrace trace)
      throws LLMException {

    long start = System.currentTimeMillis();

    QueryValidator.ValidationResult validation = validator.validate(original.query());

    // ALWAYS call repair - it will expand profiling event type groups even if validation passes
    QueryRepairer.RepairResult repair = repairer.repair(original.query(), validation);

    if (trace != null) {
      trace.addStep(
          "REPAIR",
          "changes=" + repair.changes().size() + ", confidence=" + repair.confidence(),
          System.currentTimeMillis() - start);
    }

    return new GenerationResult(
        repair.query(),
        original.explanation() + " (repaired)",
        Math.min(original.confidence(), repair.confidence()),
        repair.warning());
  }

  /** Internal record for generation results. */
  private record GenerationResult(
      String query, String explanation, double confidence, String warning) {}

  /**
   * Context for pipeline execution.
   *
   * @param clarificationAnswer answer to previous clarification question
   * @param conversationHistory previous conversation turns
   */
  public record PipelineContext(
      Optional<String> clarificationAnswer, Optional<List<String>> conversationHistory) {

    /** Creates an empty pipeline context. */
    public PipelineContext() {
      this(Optional.empty(), Optional.empty());
    }
  }
}
