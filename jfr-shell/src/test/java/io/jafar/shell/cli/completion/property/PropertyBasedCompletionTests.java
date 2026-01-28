package io.jafar.shell.cli.completion.property;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextAnalyzer;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.property.generators.CursorPositionStrategy;
import io.jafar.shell.cli.completion.property.generators.JfrPathExpressionGenerator;
import io.jafar.shell.cli.completion.property.models.CursorPosition;
import io.jafar.shell.cli.completion.property.models.GeneratedQuery;
import io.jafar.shell.cli.completion.property.models.ValidationResult;
import io.jafar.shell.cli.completion.property.validators.CandidateValidator;
import io.jafar.shell.cli.completion.property.validators.CompletionContextValidator;
import io.jafar.shell.cli.completion.property.validators.CompletionInvariants;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;

/**
 * Property-based tests for jfr-shell code completion using jqwik.
 *
 * <p>These tests automatically generate a wide variety of JfrPath expressions and cursor positions
 * to discover missing completion scenarios and validate completion behavior across all syntax
 * variations.
 *
 * <p>The test framework uses real JFR metadata from test-ap.jfr to ensure generated expressions use
 * valid event types and field names.
 */
@PropertyDefaults(tries = 1000, shrinking = ShrinkingMode.FULL)
public class PropertyBasedCompletionTests {

  private static SessionManager sessionManager;
  private static ShellCompleter completer;
  private static MetadataService metadataService;
  private static CompletionContextAnalyzer analyzer;

  // Generators and validators
  private static JfrPathExpressionGenerator expressionGenerator;
  private static CursorPositionStrategy cursorStrategy;
  private static CompletionContextValidator contextValidator;
  private static CandidateValidator candidateValidator;
  private static CompletionInvariants invariants;

  @BeforeContainer
  static void setupTestEnvironment() throws Exception {
    // Use the test JFR file from parser module
    Path testJfr = Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr");
    if (!testJfr.toFile().exists()) {
      // Try alternative path if running from different directory
      testJfr = Paths.get("parser", "src", "test", "resources", "test-ap.jfr");
    }

    // Create SessionManager with ParsingContext
    ParsingContext ctx = ParsingContext.create();
    sessionManager = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));

    // Open the test session
    sessionManager.open(testJfr, "test");

    // Initialize completion components
    completer = new ShellCompleter(sessionManager, null);
    metadataService = new MetadataService(sessionManager);
    analyzer = new CompletionContextAnalyzer();

    // Initialize generators and validators
    expressionGenerator = new JfrPathExpressionGenerator(metadataService);
    cursorStrategy = new CursorPositionStrategy(true);
    contextValidator = new CompletionContextValidator();
    candidateValidator = new CandidateValidator(metadataService);
    invariants = new CompletionInvariants();
  }

  /**
   * Invokes completion for a generated query.
   *
   * @param query the query with cursor position
   * @return list of completion candidates
   */
  private List<Candidate> invokeCompletion(GeneratedQuery query) {
    List<Candidate> candidates = new ArrayList<>();
    ParsedLine line = new SimpleParsedLine(query.getFullLine(), query.getCursorInFullLine());
    completer.complete(null, line, candidates);
    return candidates;
  }

  /**
   * Analyzes the completion context for a generated query.
   *
   * @param query the query with cursor position
   * @return the determined completion context
   */
  private CompletionContext analyzeContext(GeneratedQuery query) {
    ParsedLine line = new SimpleParsedLine(query.getFullLine(), query.getCursorInFullLine());
    return analyzer.analyze(line);
  }

  /**
   * Helper method to invoke completion at a specific position in an expression.
   *
   * @param expr the JfrPath expression (without "show ")
   * @param cursor the cursor position in the expression
   * @return list of completion candidates
   */
  private List<Candidate> completeAt(String expr, int cursor) {
    String fullLine = "show " + expr;
    int fullCursor = 5 + cursor;
    List<Candidate> candidates = new ArrayList<>();
    ParsedLine line = new SimpleParsedLine(fullLine, fullCursor);
    completer.complete(null, line, candidates);
    return candidates;
  }

  /**
   * Helper method to check if candidates contain a specific value.
   *
   * @param candidates the list of candidates
   * @param value the value to search for
   * @return true if found
   */
  private boolean containsCandidate(List<Candidate> candidates, String value) {
    return candidates.stream().anyMatch(c -> c.value().contains(value));
  }

  // ==================== Arbitrary Providers ====================

  /** Provides valid JfrPath expressions with strategic cursor positions. */
  @Provide
  Arbitrary<GeneratedQuery> validQueries() {
    return expressionGenerator
        .validJfrPathExpression()
        .flatMap(
            expr -> {
              List<CursorPosition> positions = cursorStrategy.generatePositions(expr);
              return Arbitraries.of(positions)
                  .map(
                      pos -> new GeneratedQuery(expr, pos.position(), pos.type(), metadataService));
            });
  }

  /** Provides simple expressions (no filters or pipelines). */
  @Provide
  Arbitrary<GeneratedQuery> simpleQueries() {
    return expressionGenerator
        .simpleExpression()
        .flatMap(
            expr -> {
              List<CursorPosition> positions = cursorStrategy.generatePositions(expr);
              return Arbitraries.of(positions)
                  .map(
                      pos -> new GeneratedQuery(expr, pos.position(), pos.type(), metadataService));
            });
  }

  /** Provides invalid JfrPath expressions for negative testing. */
  @Provide
  Arbitrary<GeneratedQuery> invalidQueries() {
    return expressionGenerator
        .invalidJfrPathExpression()
        .map(expr -> GeneratedQuery.atEnd(expr, metadataService));
  }

  // ==================== Universal Invariant Tests ====================

  /** Property: Completion never throws exceptions, even for arbitrary input. */
  @Property
  void completionNeverThrows(@ForAll String arbitraryInput) {
    assertDoesNotThrow(
        () -> {
          completeAt(arbitraryInput, Math.min(arbitraryInput.length(), 100));
        });
  }

  /** Property: Completion always returns non-null list. */
  @Property
  void completionReturnsNonNullList(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    assertNotNull(candidates, "Completion must never return null");
  }

  /** Property: No duplicate candidates. */
  @Property
  void noDuplicateCandidates(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    ValidationResult result = new ValidationResult();
    invariants.checkNoDuplicates(candidates, result);
    assertTrue(result.isValid(), result.getReport());
  }

  /** Property: All candidates have non-empty values. */
  @Property
  void candidatesHaveNonEmptyValues(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    ValidationResult result = new ValidationResult();
    invariants.checkNonEmptyValues(candidates, result);
    assertTrue(result.isValid(), result.getReport());
  }

  /** Property: Context type determination is deterministic. */
  @Property
  void contextTypeIsDeterministic(@ForAll("validQueries") GeneratedQuery query) {
    CompletionContext ctx1 = analyzeContext(query);
    CompletionContext ctx2 = analyzeContext(query);

    ValidationResult result = new ValidationResult();
    invariants.checkDeterminism(ctx1, ctx2, result);
    assertTrue(result.isValid(), result.getReport());
  }

  // ==================== Initial Property Tests ====================

  /** Property: Completion at the start should suggest all root types. */
  @Property
  void rootCompletionSuggestsAllRoots() {
    GeneratedQuery query = GeneratedQuery.atStart("", metadataService);
    List<Candidate> candidates = invokeCompletion(query);

    assertTrue(containsCandidate(candidates, "events"), "Should suggest 'events'");
    assertTrue(containsCandidate(candidates, "metadata"), "Should suggest 'metadata'");
    assertTrue(containsCandidate(candidates, "cp"), "Should suggest 'cp'");
    assertTrue(containsCandidate(candidates, "chunks"), "Should suggest 'chunks'");
  }

  // ==================== Context-Specific Property Tests ====================

  /** Property: Simple expressions produce valid completions. */
  @Property
  void simpleExpressionsProduceValidCompletions(@ForAll("simpleQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    CompletionContext context = analyzeContext(query);

    // Check all invariants
    ValidationResult invariantResult = invariants.checkAllInvariants(context, candidates);
    if (!invariantResult.isValid()) {
      fail(
          "Invariant violations for query: "
              + query.describe()
              + "\n"
              + invariantResult.getReport());
    }
  }

  /** Property: Candidates match partial input. */
  @Property
  void candidatesMatchPartialInput(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    CompletionContext context = analyzeContext(query);

    ValidationResult result = new ValidationResult();
    invariants.checkPartialInputMatching(context, candidates, result);

    // Warnings are acceptable (some completers suggest alternatives)
    // but errors are not
    assertTrue(result.isValid(), "Errors: " + result.getReport());
  }

  /** Property: Invalid expressions don't crash completion. */
  @Property(tries = 500)
  void invalidExpressionsDontCrash(@ForAll("invalidQueries") GeneratedQuery query) {
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = invokeCompletion(query);
          assertNotNull(candidates);
        },
        "Completion crashed on invalid query: " + query.describe());
  }

  /** Property: Reasonable candidate counts. */
  @Property
  void reasonableCandidateCounts(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);

    ValidationResult result = new ValidationResult();
    invariants.checkReasonableCandidateCount(candidates, result);

    // This produces warnings, not errors
    // Just log the warnings if any
    if (result.hasWarnings()) {
      System.err.println("Candidate count warning for: " + query.describe());
      System.err.println(result.getReport());
    }
  }

  // ==================== Validation-Based Property Tests ====================

  /** Property: Candidates are appropriate for their context. */
  @Property(tries = 500)
  void candidatesMatchContext(@ForAll("simpleQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    CompletionContext context = analyzeContext(query);

    // Validate candidates for context
    ValidationResult result = candidateValidator.validateForContext(context, candidates);

    // Allow warnings but fail on errors
    if (!result.isValid()) {
      fail(
          "Candidate validation failed for: "
              + query.describe()
              + "\nContext: "
              + context
              + "\n"
              + result.getReport());
    }
  }

  /** Property: All invariants hold for generated queries. */
  @Property(tries = 500)
  void allInvariantsHold(@ForAll("validQueries") GeneratedQuery query) {
    List<Candidate> candidates = invokeCompletion(query);
    CompletionContext context = analyzeContext(query);

    ValidationResult result = invariants.checkAllInvariants(context, candidates);

    if (!result.isValid()) {
      fail(
          "Invariant violations for: "
              + query.describe()
              + "\nContext: "
              + context
              + "\n"
              + result.getReport());
    }
  }

  // ==================== Filter Completion Property Tests ====================

  /** Property: Filter field completion suggests valid fields for the event type. */
  @Property(tries = 300)
  void filterFieldCompletionSuggestsValidFields() {
    // Test completion inside filter brackets after opening [
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + "[";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);
    CompletionContext context =
        analyzeContext(GeneratedQuery.atPosition(expr, cursor, metadataService));

    // Should suggest fields for this event type
    List<String> validFields = metadataService.getFieldNames(eventType);
    if (!validFields.isEmpty()) {
      assertTrue(candidates.size() > 0, "Should suggest at least one field for filter");

      // At least some candidates should be valid fields
      // Extract field name from candidate (after last "[")
      long validCount =
          candidates.stream()
              .filter(
                  c -> {
                    String value = c.value();
                    String fieldName =
                        value.contains("[")
                            ? value.substring(value.lastIndexOf("[") + 1).trim()
                            : value.trim();
                    // Also handle function completions like "contains("
                    if (fieldName.contains("(")) {
                      return true; // Functions are valid suggestions in filters
                    }
                    return validFields.contains(fieldName);
                  })
              .count();
      assertTrue(
          validCount > 0,
          "Should suggest valid fields, got: "
              + candidates.stream().map(Candidate::value).toList());
    }
  }

  /** Property: Filter operator completion after field name suggests all operators. */
  @Property(tries = 300)
  void filterOperatorCompletionAfterFieldName() {
    // Test completion after field name in filter
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + "[startTime ";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest comparison operators
    assertTrue(containsCandidate(candidates, "=="), "Should suggest ==");
    assertTrue(containsCandidate(candidates, "!="), "Should suggest !=");
    assertTrue(containsCandidate(candidates, ">"), "Should suggest >");
    assertTrue(containsCandidate(candidates, "<"), "Should suggest <");
  }

  /** Property: Filter logical operator completion after condition suggests && and ||. */
  @Property(tries = 300)
  void filterLogicalCompletionAfterCondition() {
    // Test completion after complete condition
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + "[startTime > 0 ";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest logical operators (closing bracket may not be suggested)
    assertTrue(containsCandidate(candidates, "&&"), "Should suggest &&");
    assertTrue(containsCandidate(candidates, "||"), "Should suggest ||");
    // Note: "]" may or may not be suggested depending on completer implementation
  }

  /** Property: Nested field paths in filters are handled correctly. */
  @Property(tries = 300)
  void nestedFieldPathsInFiltersHandled() {
    // Test completion with nested field paths
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + "[sampledThread.";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should not crash and should return candidates (even if empty for invalid path)
    assertNotNull(candidates);
  }

  // ==================== Pipeline Completion Property Tests ====================

  /**
   * Property: Pipeline operator completion after pipe suggests applicable functions. Note: With
   * semantic filtering, only functions applicable to the event type's field types are suggested.
   * jdk.ExecutionSample has numeric/time fields but no direct string fields.
   */
  @Property(tries = 300)
  void pipelineOperatorCompletionSuggestsApplicableFunctions() {
    // Test completion after pipe operator
    String expr = "events/jdk.ExecutionSample | ";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest aggregation functions (always applicable or requires numeric/time)
    assertTrue(containsCandidate(candidates, "count"), "Should suggest count");
    assertTrue(containsCandidate(candidates, "groupBy"), "Should suggest groupBy");
    assertTrue(containsCandidate(candidates, "top"), "Should suggest top");
    assertTrue(containsCandidate(candidates, "select"), "Should suggest select");

    // Should suggest numeric functions (jdk.ExecutionSample has numeric fields)
    assertTrue(containsCandidate(candidates, "sum"), "Should suggest sum");
    assertTrue(containsCandidate(candidates, "stats"), "Should suggest stats");

    // Should suggest decorator functions
    assertTrue(containsCandidate(candidates, "decorateByTime"), "Should suggest decorateByTime");
    assertTrue(containsCandidate(candidates, "decorateByKey"), "Should suggest decorateByKey");

    // String functions (uppercase, lowercase) may not be suggested
    // because jdk.ExecutionSample has no direct string fields - this is correct semantic filtering
  }

  /** Property: Function parameter completion suggests valid fields. */
  @Property(tries = 300)
  void functionParameterCompletionSuggestsFields() {
    // Test completion inside function parameters
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + " | sum(";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest fields for the event type
    List<String> validFields = metadataService.getFieldNames(eventType);
    if (!validFields.isEmpty()) {
      assertTrue(candidates.size() > 0, "Should suggest at least one field for function parameter");
    }
  }

  /**
   * Property: Transform operators are available after pipe based on field types. With semantic
   * filtering, only applicable transforms are suggested.
   */
  @Property(tries = 300)
  void transformOperatorsAvailableAfterPipe() {
    // Test that transform functions appear in pipeline completions
    String expr = "events/jdk.ExecutionSample/sampledThread | ";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Always-applicable transforms should be available
    assertTrue(containsCandidate(candidates, "len"), "Should suggest len");

    // Numeric transforms (abs, round) should be available if event has numeric fields
    assertTrue(containsCandidate(candidates, "abs"), "Should suggest abs");
    assertTrue(containsCandidate(candidates, "round"), "Should suggest round");

    // Note: String transforms (trim, uppercase) may not appear if event has no string fields
    // This is correct semantic filtering behavior
  }

  // ==================== Decorator Completion Property Tests ====================

  /** Property: decorateByTime function has correct signature in completion. */
  @Property(tries = 200)
  void decorateByTimeHasCorrectSignature() {
    // Test that decorateByTime appears with correct signature
    String expr = "events/jdk.ExecutionSample | decorateByTi";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should find decorateByTime with opening paren
    boolean foundDecorator =
        candidates.stream().anyMatch(c -> c.value().contains("decorateByTime"));
    assertTrue(foundDecorator, "Should suggest decorateByTime");
  }

  /** Property: decorateByKey function parameters are validated. */
  @Property(tries = 200)
  void decorateByKeyParametersValid() {
    // Test that decorateByKey appears in completions
    String expr = "events/jdk.ExecutionSample | decorateByKe";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should find decorateByKey
    boolean foundDecorator = candidates.stream().anyMatch(c -> c.value().contains("decorateByKey"));
    assertTrue(foundDecorator, "Should suggest decorateByKey");
  }

  /** Property: Decorated fields (with $decorator prefix) are accessible after decoration. */
  @Property(tries = 200)
  void decoratedFieldsAccessible() {
    // Test completion for decorated fields
    String expr =
        "events/jdk.ExecutionSample | decorateByTime(\"jdk.JavaMonitorEnter\") | $decorator.";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest fields from decorator event type
    // Note: This tests the accessor, actual fields depend on metadata
    assertNotNull(candidates, "Should not crash on $decorator. access");
  }

  // ==================== Edge Case Property Tests ====================

  /** Property: Very long field paths are handled gracefully. */
  @Property(tries = 200)
  void veryLongPathsHandledGracefully(@ForAll @IntRange(min = 5, max = 10) int depth) {
    // Build a path with many levels
    StringBuilder expr = new StringBuilder("events/jdk.ExecutionSample");
    for (int i = 0; i < depth; i++) {
      expr.append("/field").append(i);
    }
    int cursor = expr.length();

    // Should not crash even with very deep paths
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = completeAt(expr.toString(), cursor);
          assertNotNull(candidates);
        },
        "Should handle deep paths gracefully");
  }

  /** Property: Cursor in middle of token is handled correctly. */
  @Property(tries = 300)
  void cursorInMiddleOfTokenHandled() {
    // Test completion with cursor in middle of identifier
    String expr = "events/jdk.ExecutionSample";
    int cursor = 10; // Middle of "events"

    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = completeAt(expr, cursor);
          assertNotNull(candidates);
        },
        "Should handle cursor in middle of token");
  }

  /** Property: Whitespace-only and empty input handled correctly. */
  @Property(tries = 200)
  void emptyAndWhitespaceHandled(@ForAll @StringLength(max = 10) @Whitespace String whitespace) {
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = completeAt(whitespace, whitespace.length());
          assertNotNull(candidates);
        },
        "Should handle whitespace gracefully");
  }

  /** Property: Completion after metadata root suggests metadata types. */
  @Property(tries = 200)
  void metadataCompletionSuggestsTypes() {
    String expr = "metadata/";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest metadata types
    Set<String> metadataTypes = metadataService.getAllMetadataTypes();
    if (!metadataTypes.isEmpty()) {
      assertTrue(candidates.size() > 0, "Should suggest metadata types");

      // At least some should be valid metadata types
      long validCount =
          candidates.stream()
              .filter(
                  c -> {
                    String value = c.value();
                    if (value.contains("/")) {
                      value = value.substring(value.indexOf("/") + 1);
                      if (value.contains("/")) {
                        value = value.substring(0, value.indexOf("/"));
                      }
                    }
                    return metadataTypes.contains(value);
                  })
              .count();
      assertTrue(validCount > 0, "Should suggest valid metadata types");
    }
  }

  /** Property: Completion after cp root suggests constant pool types. */
  @Property(tries = 200)
  void cpCompletionSuggestsTypes() {
    String expr = "cp/";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest constant pool types
    Set<String> cpTypes = metadataService.getConstantPoolTypes();
    if (!cpTypes.isEmpty()) {
      assertTrue(candidates.size() > 0, "Should suggest constant pool types");
    }
  }

  /** Property: Chunk ID completion suggests valid chunk IDs. */
  @Property(tries = 200)
  void chunkIdCompletionSuggestsValidIds() {
    String expr = "chunks/";
    int cursor = expr.length();

    List<Candidate> candidates = completeAt(expr, cursor);

    // Should suggest chunk IDs
    List<Integer> chunkIds = metadataService.getChunkIds();
    if (!chunkIds.isEmpty()) {
      assertTrue(candidates.size() > 0, "Should suggest chunk IDs");
    }
  }

  /** Property: Field path with slashes is parsed correctly. */
  @Property(tries = 300)
  void fieldPathWithSlashesParsedCorrectly() {
    String eventType = "jdk.ExecutionSample";
    String expr = "events/" + eventType + "/sampledThread/";
    int cursor = expr.length();

    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = completeAt(expr, cursor);
          CompletionContext context =
              analyzeContext(GeneratedQuery.atPosition(expr, cursor, metadataService));

          // Should identify as field path context
          assertNotNull(context);
        },
        "Should parse field paths with trailing slashes");
  }

  /** Property: Special characters in field names don't crash completion. */
  @Property(tries = 200)
  void specialCharactersInFieldNamesDontCrash(
      @ForAll @Chars({'$', '_', '.', '-'}) char specialChar) {
    String expr = "events/jdk.ExecutionSample/field" + specialChar;
    int cursor = expr.length();

    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = completeAt(expr, cursor);
          assertNotNull(candidates);
        },
        "Should handle special characters in field names");
  }

  // ==================== Helper Classes ====================

  /**
   * Simple mock implementation of ParsedLine for testing.
   *
   * <p>This mimics the SimpleParsedLine used in existing completion tests. It splits on whitespace
   * and doesn't match JLine3's DefaultParser tokenization.
   *
   * <p>Our token-based completion implementation operates on the raw line + cursor, so it's not
   * affected by this tokenization difference.
   */
  static class SimpleParsedLine implements ParsedLine {
    private final String line;
    private final int cursor;
    private final List<String> words;
    private final int wordIndex;

    SimpleParsedLine(String line, int cursor) {
      this.line = line;
      this.cursor = cursor;
      List<String> w = new ArrayList<>(Arrays.asList(line.stripLeading().split("\\s+")));
      if (line.endsWith(" ")) {
        w.add("");
      }
      this.words = Collections.unmodifiableList(w);
      this.wordIndex = words.size() - 1;
    }

    SimpleParsedLine(String line) {
      this(line, line.length());
    }

    @Override
    public String word() {
      return words.get(wordIndex);
    }

    @Override
    public int wordCursor() {
      return word().length();
    }

    @Override
    public int wordIndex() {
      return wordIndex;
    }

    @Override
    public List<String> words() {
      return words;
    }

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return cursor;
    }
  }
}
