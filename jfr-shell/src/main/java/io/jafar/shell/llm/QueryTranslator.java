package io.jafar.shell.llm;

import io.jafar.shell.jfrpath.JfrPathParser;

/**
 * Translates natural language queries into JfrPath queries using an LLM provider with a multi-step
 * pipeline architecture.
 *
 * <p>Pipeline architecture:
 *
 * <ol>
 *   <li>CLASSIFY: Categorizes queries into 12 types (count, topN, decorator, etc.)
 *   <li>CLARIFY (conditional): Generates clarification for ambiguous queries (confidence < 0.5)
 *   <li>GENERATE: Creates JfrPath query using category-specific prompts and examples
 *   <li>VALIDATE/REPAIR (conditional): Fixes syntax errors when confidence < 0.7 or validation
 *       fails
 * </ol>
 *
 * <p>Benefits:
 *
 * <ul>
 *   <li>85-95% prompt size reduction (12-33KB → <2KB per step)
 *   <li>Structured outputs with JSON schema enforcement
 *   <li>Smart example selection (2-4 most relevant per category)
 *   <li>Rule-based validation with targeted LLM repairs
 *   <li>Clarification requests for ambiguous input
 * </ul>
 */
public class QueryTranslator {

  private final LLMProvider provider;
  private final ContextBuilder contextBuilder;
  private final ConversationHistory history;
  private final LLMConfig config;
  private final QueryPipeline pipeline;

  /**
   * Creates a query translator with pipeline support.
   *
   * @param provider LLM provider
   * @param contextBuilder context builder for prompts
   * @param history conversation history for context
   * @param session JFR session for pipeline context
   */
  public QueryTranslator(
      LLMProvider provider,
      ContextBuilder contextBuilder,
      ConversationHistory history,
      io.jafar.shell.core.SessionManager.SessionRef session) {
    this.provider = provider;
    this.contextBuilder = contextBuilder;
    this.history = history;
    this.config = provider.getConfig();
    this.pipeline = new QueryPipeline(provider, session, config);
  }

  /**
   * Translates a natural language query into a JfrPath query using the multi-step pipeline.
   *
   * @param naturalLanguageQuery the user's question
   * @return translation result with query, explanation, and confidence
   * @throws LLMException if translation fails
   */
  public TranslationResult translate(String naturalLanguageQuery) throws LLMException {
    PipelineResult result =
        pipeline.generateJfrPath(naturalLanguageQuery, new QueryPipeline.PipelineContext());

    if (result.needsClarification()) {
      return TranslationResult.needsClarification(result.clarification().get());
    }

    if (result.hasQuery()) {
      String warning = result.warning().orElse(null);
      if (warning != null) {
        return TranslationResult.successWithWarning(
            result.query(), result.explanation(), result.confidence(), warning);
      } else {
        return TranslationResult.success(result.query(), result.explanation(), result.confidence());
      }
    }

    throw new LLMException(LLMException.ErrorType.INVALID_RESPONSE, "Pipeline produced no result");
  }

  /**
   * Validates a JfrPath query by attempting to parse it.
   *
   * @param query query string
   * @return true if valid
   */
  @SuppressWarnings("unused")
  private boolean validateQuery(String query) {
    try {
      JfrPathParser.parse(query);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
