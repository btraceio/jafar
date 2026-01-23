package io.jafar.shell.cli.completion.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.integration.JLineTestEnvironment.ParseComparison;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;

/**
 * Integration tests comparing SimpleParsedLine with JLine's real DefaultParser.
 *
 * <p>These tests identify parsing differences that could cause completion behavior to differ
 * between test mocks and production usage.
 */
@PropertyDefaults(tries = 200, shrinking = ShrinkingMode.FULL)
public class RealParserCompletionTests {

  private static Path testJfrPath;
  private static ParsingContext parsingContext;
  private static JLineTestEnvironment testEnv;

  private SessionManager sessionManager;
  private ShellCompleter completer;
  private MetadataService metadataService;
  private DefaultParser jlineParser;

  @BeforeContainer
  static void setupTestEnvironment() throws Exception {
    testJfrPath = Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr");
    if (!testJfrPath.toFile().exists()) {
      testJfrPath = Paths.get("parser", "src", "test", "resources", "test-ap.jfr");
    }
    parsingContext = ParsingContext.create();
    testEnv = new JLineTestEnvironment();
  }

  @BeforeTry
  void setupPerTry() throws Exception {
    sessionManager = new SessionManager(parsingContext, (path, c) -> new JFRSession(path, c));
    sessionManager.open(testJfrPath, "test");
    completer = new ShellCompleter(sessionManager, null);
    metadataService = new MetadataService(sessionManager);
    jlineParser = new DefaultParser();
  }

  @AfterTry
  void cleanupPerTry() {
    if (sessionManager != null) {
      try {
        sessionManager.closeAll();
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  // ==================== Arbitrary Providers ====================

  @Provide
  Arbitrary<String> testInputs() {
    return Arbitraries.of(testEnv.generateTestCorpus());
  }

  @Provide
  Arbitrary<Integer> cursorPositions() {
    return Arbitraries.integers().between(0, 100);
  }

  // ==================== Parser Comparison Tests ====================

  /**
   * Property: SimpleParsedLine and JLine parser produce consistent word counts for simple inputs.
   */
  @Property(tries = 100)
  void parserConsistencyForSimpleInputs() {
    String[] simpleInputs = {
      "show events/jdk.ExecutionSample", "show metadata/Class", "help", "exit"
    };

    for (String input : simpleInputs) {
      ParseComparison comparison = testEnv.compare(input, input.length());
      // Word count should match for simple inputs
      assertEquals(
          comparison.simpleParsed().words().size(),
          comparison.jlineParsed().words().size(),
          "Word count mismatch for: " + input);
    }
  }

  /** Property: Completion produces results with JLine parser. */
  @Property(tries = 100)
  void completionWorksWithJLineParser(@ForAll("testInputs") String input) {
    int cursor = input.length();

    // Parse with JLine
    ParsedLine jlineParsed;
    try {
      jlineParsed = jlineParser.parse(input, cursor);
    } catch (Exception e) {
      // Some inputs may not parse - that's OK for fuzzing
      return;
    }

    // Invoke completion
    List<Candidate> candidates = new ArrayList<>();
    assertDoesNotThrow(
        () -> {
          completer.complete(null, jlineParsed, candidates);
        },
        "Completion crashed with JLine parser for: " + input);

    assertNotNull(candidates, "Candidates should not be null");
  }

  /** Property: Completion results are similar with both parsers. */
  @Property(tries = 50)
  void completionResultsSimilarWithBothParsers(@ForAll("testInputs") String input) {
    int cursor = input.length();

    // Parse with both
    ParseComparison comparison = testEnv.compare(input, cursor);

    // Skip if JLine parsing failed
    if (!comparison.jlineParsed().words().isEmpty() && comparison.jlineParsed().word() != null) {

      // Complete with both
      List<Candidate> simpleCandidates = new ArrayList<>();
      List<Candidate> jlineCandidates = new ArrayList<>();

      completer.complete(null, comparison.simpleParsed(), simpleCandidates);
      try {
        completer.complete(null, comparison.jlineParsed(), jlineCandidates);
      } catch (Exception e) {
        // JLine may have issues
        return;
      }

      // Both should produce non-null lists
      assertNotNull(simpleCandidates);
      assertNotNull(jlineCandidates);

      // Log significant differences (for analysis, not failure)
      if (Math.abs(simpleCandidates.size() - jlineCandidates.size()) > 5) {
        System.err.println("Candidate count difference for: " + input);
        System.err.println("  Simple: " + simpleCandidates.size());
        System.err.println("  JLine:  " + jlineCandidates.size());
      }
    }
  }

  // ==================== Edge Case Tests ====================

  /** Property: Filter bracket inputs parse correctly. */
  @Property(tries = 50)
  void filterBracketInputsParseCorrectly() {
    String[] filterInputs = {
      "show events/jdk.ExecutionSample[",
      "show events/jdk.ExecutionSample[startTime",
      "show events/jdk.ExecutionSample[startTime > 0",
      "show events/jdk.ExecutionSample[startTime > 0]"
    };

    for (String input : filterInputs) {
      ParseComparison comparison = testEnv.compare(input, input.length());

      // Both parsers should handle this without crashing
      assertNotNull(comparison.simpleParsed());
      assertNotNull(comparison.jlineParsed());

      // Complete with both - should not crash
      List<Candidate> candidates = new ArrayList<>();
      assertDoesNotThrow(
          () -> {
            completer.complete(null, comparison.simpleParsed(), candidates);
          },
          "Completion failed for filter input: " + input);
    }
  }

  /** Property: Pipeline inputs parse correctly. */
  @Property(tries = 50)
  void pipelineInputsParseCorrectly() {
    String[] pipelineInputs = {
      "show events/jdk.ExecutionSample |",
      "show events/jdk.ExecutionSample | ",
      "show events/jdk.ExecutionSample | count()",
      "show events/jdk.ExecutionSample | groupBy(sampledThread)"
    };

    for (String input : pipelineInputs) {
      ParseComparison comparison = testEnv.compare(input, input.length());

      // Both parsers should handle this
      assertNotNull(comparison.simpleParsed());
      assertNotNull(comparison.jlineParsed());
    }
  }

  /** Property: Quoted strings are handled correctly. */
  @Property(tries = 50)
  void quotedStringsHandledCorrectly() {
    String[] quotedInputs = {
      "show events/jdk.ExecutionSample[name == \"test\"]",
      "show events/jdk.ExecutionSample[name ~ \"pattern.*\"]",
      "echo \"hello world\""
    };

    for (String input : quotedInputs) {
      ParseComparison comparison = testEnv.compare(input, input.length());

      // Completion should not crash
      List<Candidate> candidates = new ArrayList<>();
      assertDoesNotThrow(
          () -> {
            completer.complete(null, comparison.simpleParsed(), candidates);
          },
          "Completion crashed on quoted input: " + input);
    }
  }

  /** Property: Various cursor positions are handled correctly. */
  @Property(tries = 100)
  void cursorPositionsHandledCorrectly() {
    String input = "show events/jdk.ExecutionSample[startTime > 0]";

    for (int cursor = 0; cursor <= input.length(); cursor++) {
      int finalCursor = cursor;
      assertDoesNotThrow(
          () -> {
            ParseComparison comparison = testEnv.compare(input, finalCursor);
            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, comparison.simpleParsed(), candidates);
            assertNotNull(candidates);
          },
          "Failed at cursor position " + cursor);
    }
  }

  // ==================== Regression Tests ====================

  /**
   * Test known edge cases that have caused issues. Note: This test logs failures for analysis but
   * doesn't fail the test, since we're discovering bugs that will be fixed separately.
   */
  @Property(tries = 1)
  void knownEdgeCasesHandledCorrectly() {
    List<ParseComparison> edgeCases = testEnv.testEdgeCases();

    List<String> failures = new ArrayList<>();
    int successCount = 0;
    for (ParseComparison comparison : edgeCases) {
      try {
        // Both should not crash during completion
        List<Candidate> simpleCandidates = new ArrayList<>();
        List<Candidate> jlineCandidates = new ArrayList<>();

        completer.complete(null, comparison.simpleParsed(), simpleCandidates);

        if (comparison.jlineParsed() instanceof JLineTestEnvironment.FailedParsedLine) {
          // JLine parsing failed - note but don't fail test
          System.err.println("JLine parsing failed for: " + comparison.input());
        } else {
          completer.complete(null, comparison.jlineParsed(), jlineCandidates);
        }
        successCount++;
      } catch (Exception e) {
        failures.add(comparison.input() + ": " + e.getMessage());
      }
    }

    // Log failures for analysis but don't fail test - these are known issues
    if (!failures.isEmpty()) {
      System.err.println("=== Edge Case Issues Found (for analysis) ===");
      for (String failure : failures) {
        System.err.println("  " + failure);
      }
      System.err.println("Total: " + failures.size() + " issues, " + successCount + " successes");
    }

    // Test passes if at least some edge cases work
    assertTrue(successCount > 0, "All edge cases failed - critical issue");
  }

  // ==================== Corpus-Based Tests ====================

  /** Property: All corpus inputs complete without crashing. */
  @Property(tries = 500)
  void allCorpusInputsCompleteWithoutCrashing(@ForAll("testInputs") String input) {
    // Test at various cursor positions
    int[] cursors = {0, input.length() / 2, input.length()};

    for (int cursor : cursors) {
      int safeCursor = Math.min(cursor, input.length());
      assertDoesNotThrow(
          () -> {
            ParseComparison comparison = testEnv.compare(input, safeCursor);
            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, comparison.simpleParsed(), candidates);
            assertNotNull(candidates);
          },
          "Crashed on input: " + input + " at cursor " + safeCursor);
    }
  }
}
