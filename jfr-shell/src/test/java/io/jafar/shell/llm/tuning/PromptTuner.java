package io.jafar.shell.llm.tuning;

import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.llm.ContextBuilder;
import io.jafar.shell.llm.ConversationHistory;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;
import io.jafar.shell.llm.LLMProvider;
import io.jafar.shell.llm.QueryTranslator;
import io.jafar.shell.llm.TranslationResult;

/**
 * Main orchestrator for LLM prompt tuning. Runs test cases against a prompt variant and validates
 * the generated queries.
 */
public class PromptTuner {
  private final TestSuite testSuite;
  private final PromptVariant variant;
  private final LLMProvider provider;
  private final SessionRef testSession;

  /**
   * Creates a prompt tuner.
   *
   * @param testSuite test suite containing test cases
   * @param variant prompt variant to test
   * @param provider LLM provider
   * @param testSession JFR session for context
   */
  public PromptTuner(
      TestSuite testSuite, PromptVariant variant, LLMProvider provider, SessionRef testSession) {
    this.testSuite = testSuite;
    this.variant = variant;
    this.provider = provider;
    this.testSession = testSession;
  }

  /**
   * Runs all test cases and collects results.
   *
   * @return tuning results with metrics
   */
  public TuningResults runTests() {
    long startTime = System.currentTimeMillis();
    TuningResults results = new TuningResults(variant.getId());

    for (TestCase testCase : testSuite.getTestCases()) {
      TestResult result = runTestCase(testCase);
      results.addResult(result);
    }

    long duration = System.currentTimeMillis() - startTime;
    return new TuningResults(variant.getId(), results.getResults(), duration);
  }

  /**
   * Runs a single test case.
   *
   * @param testCase the test case to run
   * @return test result
   */
  private TestResult runTestCase(TestCase testCase) {
    try {
      // Build context with base session
      LLMConfig config;
      try {
        config = LLMConfig.load();
      } catch (Exception e) {
        config = LLMConfig.defaults();
      }

      ContextBuilder baseBuilder = new ContextBuilder(testSession, config);

      // Build modified prompt using variant
      String customPrompt = variant.buildSystemPrompt(baseBuilder);

      // Create wrapper context builder that returns the custom prompt
      ContextBuilder variantBuilder =
          new ContextBuilder(testSession, config) {
            @Override
            public String buildSystemPrompt() {
              return customPrompt;
            }
          };

      // Create QueryTranslator with modified prompt
      QueryTranslator translator =
          new QueryTranslator(provider, variantBuilder, new ConversationHistory(0), testSession);

      // Translate natural language query
      TranslationResult translation = translator.translate(testCase.naturalLanguage());

      // Validate generated query
      boolean syntaxValid = validateSyntax(translation.jfrPathQuery());
      boolean semanticMatch = semanticEquals(translation.jfrPathQuery(), testCase.expectedQuery());

      return new TestResult(
          testCase,
          translation.jfrPathQuery(),
          syntaxValid,
          semanticMatch,
          translation.confidence(),
          null);

    } catch (LLMException e) {
      return new TestResult(testCase, null, false, false, 0.0, e);
    } catch (Exception e) {
      // Wrap unexpected exceptions
      LLMException wrapped =
          new LLMException(
              LLMException.ErrorType.PARSE_ERROR, "Unexpected error: " + e.getMessage(), e);
      return new TestResult(testCase, null, false, false, 0.0, wrapped);
    }
  }

  /**
   * Validates query syntax by attempting to parse it.
   *
   * @param query the query to validate
   * @return true if valid syntax
   */
  private boolean validateSyntax(String query) {
    if (query == null) {
      return false;
    }
    try {
      JfrPathParser.parse(query);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks if two queries are semantically equivalent. Currently uses normalized string comparison,
   * but could be enhanced to compare parse trees.
   *
   * @param actual generated query
   * @param expected expected query
   * @return true if semantically equivalent
   */
  private boolean semanticEquals(String actual, String expected) {
    if (actual == null || expected == null) {
      return actual == expected;
    }
    // Normalize: remove extra whitespace, standardize case
    String normalizedActual = normalize(actual);
    String normalizedExpected = normalize(expected);
    return normalizedActual.equals(normalizedExpected);
  }

  /**
   * Normalizes a query for comparison.
   *
   * @param query query to normalize
   * @return normalized query
   */
  private String normalize(String query) {
    // Remove extra whitespace, standardize spacing around operators
    return query
        .replaceAll("\\s+", " ")
        .replaceAll("\\s*\\|\\s*", " | ")
        .replaceAll("\\s*\\[\\s*", "[")
        .replaceAll("\\s*]\\s*", "]")
        .replaceAll("\\s*\\(\\s*", "(")
        .replaceAll("\\s*\\)\\s*", ")")
        .replaceAll("\\s*,\\s*", ", ")
        .trim();
  }
}
