package io.jafar.shell.cli.completion.stateful;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.CompletionContextAnalyzer;
import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.stateful.SessionSequenceGenerator.CompletionScenario;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;

/**
 * Stateful property-based tests for code completion.
 *
 * <p>These tests verify completion behavior across session state changes, including opening/closing
 * sessions, switching sessions, and variable operations.
 */
@PropertyDefaults(tries = 100, shrinking = ShrinkingMode.FULL)
public class StatefulCompletionTests {

  private static Path testJfrPath;
  private static ParsingContext parsingContext;
  private static SessionSequenceGenerator staticGenerator;

  // Per-test state (reset before each try)
  private SessionManager sessionManager;
  private VariableStore variableStore;
  private ShellCompleter completer;
  private MetadataService metadataService;
  private CompletionContextAnalyzer analyzer;

  @BeforeContainer
  static void setupTestEnvironment() throws Exception {
    // Find the test JFR file
    testJfrPath = Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr");
    if (!testJfrPath.toFile().exists()) {
      testJfrPath = Paths.get("parser", "src", "test", "resources", "test-ap.jfr");
    }

    // Create shared ParsingContext
    parsingContext = ParsingContext.create();

    // Create static generator for Arbitraries
    staticGenerator = new SessionSequenceGenerator(testJfrPath);
  }

  @BeforeTry
  void setupPerTry() throws Exception {
    // Create fresh state for each try
    sessionManager = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), parsingContext);
    variableStore = new VariableStore();
    completer = new ShellCompleter(sessionManager, null);
    metadataService = new MetadataService(sessionManager);
    analyzer = new CompletionContextAnalyzer();
  }

  @AfterTry
  void cleanupPerTry() {
    // Close any open sessions
    if (sessionManager != null) {
      try {
        sessionManager.closeAll();
      } catch (Exception e) {
        // Ignore cleanup errors
      }
    }
  }

  // ==================== Helper Methods ====================

  private void executeActions(List<SessionAction> actions) throws Exception {
    for (SessionAction action : actions) {
      try {
        action.execute(sessionManager, variableStore);
      } catch (Exception e) {
        // Some actions may fail (e.g., close non-existent session) - that's OK
      }
    }
  }

  private List<Candidate> invokeCompletion(String expression, int cursor) {
    List<Candidate> candidates = new ArrayList<>();
    String fullLine = "show " + expression;
    int fullCursor = 5 + cursor;
    ParsedLine line = new SimpleParsedLine(fullLine, fullCursor);
    completer.complete(null, line, candidates);
    return candidates;
  }

  private CompletionContext analyzeContext(String expression, int cursor) {
    String fullLine = "show " + expression;
    int fullCursor = 5 + cursor;
    ParsedLine line = new SimpleParsedLine(fullLine, fullCursor);
    return analyzer.analyze(line);
  }

  // ==================== Arbitrary Providers ====================

  @Provide
  Arbitrary<CompletionScenario> completionScenarios() {
    return staticGenerator.completionScenario();
  }

  @Provide
  Arbitrary<CompletionScenario> sessionSwitchScenarios() {
    return staticGenerator.sessionSwitchScenario();
  }

  @Provide
  Arbitrary<List<SessionAction>> simpleWorkflows() {
    return staticGenerator.simpleWorkflow();
  }

  @Provide
  Arbitrary<List<SessionAction>> multiSessionWorkflows() {
    return staticGenerator.multiSessionWorkflow();
  }

  // ==================== Robustness Tests ====================

  /** Property: Completion never crashes after any workflow. */
  @Property(tries = 200)
  void completionNeverCrashesAfterWorkflow(
      @ForAll("completionScenarios") CompletionScenario scenario) {
    assertDoesNotThrow(
        () -> {
          executeActions(scenario.setupActions());
          List<Candidate> candidates =
              invokeCompletion(scenario.expression(), scenario.cursorPosition());
          assertNotNull(candidates, "Completion returned null after workflow");
        },
        "Completion crashed after workflow: " + scenario.describe());
  }

  /** Property: Context analysis is deterministic after state changes. */
  @Property(tries = 100)
  void contextAnalysisIsDeterministicAfterStateChanges(
      @ForAll("completionScenarios") CompletionScenario scenario) throws Exception {

    executeActions(scenario.setupActions());

    CompletionContext ctx1 = analyzeContext(scenario.expression(), scenario.cursorPosition());
    CompletionContext ctx2 = analyzeContext(scenario.expression(), scenario.cursorPosition());

    assertNotNull(ctx1);
    assertNotNull(ctx2);
    assertEquals(
        ctx1.type(),
        ctx2.type(),
        "Non-deterministic context after workflow: " + scenario.describe());
  }

  // ==================== Session Lifecycle Tests ====================

  /** Property: Completion works after opening a session. */
  @Property(tries = 100)
  void completionWorksAfterOpeningSession() throws Exception {
    sessionManager.open(testJfrPath, "test");

    List<Candidate> candidates = invokeCompletion("events/jdk.ExecutionSample/", 27);

    assertNotNull(candidates);
    // Should have some field completions
    assertTrue(candidates.size() > 0, "Expected field completions after opening session");
  }

  /** Property: Completion is safe after closing session. */
  @Property(tries = 50)
  void completionIsSafeAfterClosingSession() throws Exception {
    sessionManager.open(testJfrPath, "test");
    sessionManager.close("test");

    // Should not crash even with no active session
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = invokeCompletion("events/jdk.ExecutionSample/", 27);
          assertNotNull(candidates);
        });
  }

  /** Property: Completion is consistent across session switches. */
  @Property(tries = 50)
  void completionConsistentAcrossSessionSwitches() throws Exception {
    // Open two sessions with the same file
    sessionManager.open(testJfrPath, "session1");
    sessionManager.open(testJfrPath, "session2");

    // Get completions in session1
    sessionManager.use("session1");
    List<Candidate> candidates1 = invokeCompletion("events/jdk.ExecutionSample/", 27);

    // Get completions in session2
    sessionManager.use("session2");
    List<Candidate> candidates2 = invokeCompletion("events/jdk.ExecutionSample/", 27);

    // Should have the same candidates (same file)
    assertEquals(
        candidates1.size(),
        candidates2.size(),
        "Different completion counts across sessions with same file");
  }

  /** Property: Completion works after switching back and forth. */
  @Property(tries = 50)
  void completionWorksAfterSwitchingBackAndForth(
      @ForAll("sessionSwitchScenarios") CompletionScenario scenario) throws Exception {

    executeActions(scenario.setupActions());

    // Multiple switches shouldn't break completion
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates =
              invokeCompletion(scenario.expression(), scenario.cursorPosition());
          assertNotNull(candidates);
        },
        "Completion failed after session switches: " + scenario.describe());
  }

  // ==================== Variable Completion Tests ====================

  /** Property: Variable completion works after setting variables. */
  @Property(tries = 50)
  void variableCompletionWorksAfterSettingVariables() throws Exception {
    sessionManager.open(testJfrPath, "test");

    // Set some variables using the correct API
    variableStore.set("myCount", new VariableStore.ScalarValue(42));
    variableStore.set("myName", new VariableStore.ScalarValue("test"));
    variableStore.set("myValue", new VariableStore.ScalarValue(100));

    // Completion should not crash (variable completion is complex)
    assertDoesNotThrow(
        () -> {
          // This tests the variable completion path
          List<Candidate> candidates = new ArrayList<>();
          ParsedLine line = new SimpleParsedLine("echo ${my", 9);
          completer.complete(null, line, candidates);
          assertNotNull(candidates);
        });
  }

  /** Property: Variable completion is safe after unsetting variables. */
  @Property(tries = 50)
  void variableCompletionSafeAfterUnsettingVariables() throws Exception {
    sessionManager.open(testJfrPath, "test");

    // Set then unset a variable
    variableStore.set("tempVar", new VariableStore.ScalarValue("value"));
    variableStore.remove("tempVar");

    // Should not crash
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = new ArrayList<>();
          ParsedLine line = new SimpleParsedLine("echo ${temp", 11);
          completer.complete(null, line, candidates);
          assertNotNull(candidates);
        });
  }

  // ==================== Metadata Cache Tests ====================

  /** Property: Completion works after metadata cache invalidation. */
  @Property(tries = 50)
  void completionWorksAfterCacheInvalidation() throws Exception {
    sessionManager.open(testJfrPath, "test");

    // Get initial completions
    List<Candidate> candidatesBefore = invokeCompletion("events/jdk.ExecutionSample/", 27);

    // Invalidate cache
    metadataService.invalidateCache();

    // Get completions again
    List<Candidate> candidatesAfter = invokeCompletion("events/jdk.ExecutionSample/", 27);

    assertNotNull(candidatesAfter);
    // Cache should be rebuilt with same results
    assertEquals(
        candidatesBefore.size(),
        candidatesAfter.size(),
        "Completion counts differ after cache invalidation");
  }

  /** Property: Metadata refreshes correctly after reopening file. */
  @Property(tries = 30)
  void metadataRefreshesAfterReopeningFile() throws Exception {
    sessionManager.open(testJfrPath, "test");

    // Get initial completions
    List<Candidate> candidatesBefore = invokeCompletion("events/", 7);
    int countBefore = candidatesBefore.size();

    // Close and reopen
    sessionManager.close("test");
    sessionManager.open(testJfrPath, "test2");

    // Get completions again
    List<Candidate> candidatesAfter = invokeCompletion("events/", 7);

    assertNotNull(candidatesAfter);
    // Should have same event types available
    assertEquals(countBefore, candidatesAfter.size(), "Event type counts differ after reopen");
  }

  // ==================== Stress Tests ====================

  /** Property: Completion survives rapid session operations. */
  @Property(tries = 20)
  void completionSurvivesRapidSessionOperations() throws Exception {
    // Rapidly open, use, and close sessions
    for (int i = 0; i < 5; i++) {
      sessionManager.open(testJfrPath, "rapid" + i);
    }

    for (int i = 0; i < 5; i++) {
      sessionManager.use("rapid" + i);
      assertDoesNotThrow(
          () -> {
            List<Candidate> candidates = invokeCompletion("events/", 7);
            assertNotNull(candidates);
          });
    }

    for (int i = 0; i < 5; i++) {
      sessionManager.close("rapid" + i);
    }
  }

  /** Property: Completion works under interleaved operations. */
  @Property(tries = 30)
  void completionWorksUnderInterleavedOperations(
      @ForAll("simpleWorkflows") List<SessionAction> workflow) throws Exception {

    for (SessionAction action : workflow) {
      try {
        action.execute(sessionManager, variableStore);

        // Test completion after each action
        if (!sessionManager.list().isEmpty()) {
          assertDoesNotThrow(
              () -> {
                List<Candidate> candidates = invokeCompletion("events/", 7);
                assertNotNull(candidates);
              });
        }
      } catch (Exception e) {
        // Some actions may fail, that's OK
      }
    }
  }

  // ==================== Edge Cases ====================

  /** Property: Completion with no active session doesn't crash. */
  @Property(tries = 20)
  void completionWithNoActiveSessionDoesntCrash() {
    // No session opened
    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = invokeCompletion("events/", 7);
          assertNotNull(candidates);
        });
  }

  /** Property: Completion with closed session doesn't crash. */
  @Property(tries = 20)
  void completionWithClosedSessionDoesntCrash() throws Exception {
    sessionManager.open(testJfrPath, "test");
    sessionManager.close("test");

    assertDoesNotThrow(
        () -> {
          List<Candidate> candidates = invokeCompletion("events/jdk.ExecutionSample/", 27);
          assertNotNull(candidates);
        });
  }

  // ==================== SimpleParsedLine ====================

  static class SimpleParsedLine implements ParsedLine {
    private final String line;
    private final int cursor;
    private final List<String> words;
    private final int wordIndex;

    SimpleParsedLine(String line, int cursor) {
      this.line = line;
      this.cursor = Math.min(cursor, line.length());
      List<String> w = new ArrayList<>(Arrays.asList(line.stripLeading().split("\\s+")));
      if (line.endsWith(" ")) {
        w.add("");
      }
      this.words = Collections.unmodifiableList(w);
      this.wordIndex = Math.max(0, words.size() - 1);
    }

    @Override
    public String word() {
      return words.isEmpty() ? "" : words.get(wordIndex);
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
