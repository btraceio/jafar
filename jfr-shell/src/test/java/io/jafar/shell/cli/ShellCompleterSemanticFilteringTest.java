package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.*;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for semantic filtering of pipeline function completions. Verifies that only applicable
 * functions are suggested based on available field types in the current event type.
 */
class ShellCompleterSemanticFilteringTest {

  private SessionManager sessionManager;
  private ShellCompleter completer;

  @BeforeEach
  void setUp() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    sessionManager = new SessionManager(createMockSessionFactory(), ctx);
    sessionManager.open(Path.of("/tmp/test.jfr"), null);
    completer = new ShellCompleter(sessionManager, null);
  }

  // ==================== Mock Setup ====================

  private SessionManager.SessionFactory createMockSessionFactory() {
    return (path, context) -> {
      JFRSession session = Mockito.mock(JFRSession.class);
      when(session.getRecordingPath()).thenReturn(path);
      when(session.getAvailableEventTypes())
          .thenReturn(
              Set.of(
                  "test.NumericOnlyEvent",
                  "test.StringOnlyEvent",
                  "test.AllTypesEvent",
                  "test.EmptyEvent"));
      return session;
    };
  }

  // ==================== Graceful Fallback Tests ====================

  @Test
  void showsAllFunctionsWhenNoMetadataAvailable() throws Exception {
    // When metadata is not available (mock session doesn't provide field info),
    // all functions should be shown as a graceful fallback
    List<Candidate> cands = complete("show events/test.EmptyEvent | ");

    // Should include always-applicable functions
    assertTrue(hasCandidate(cands, "count"), "Should suggest count");
    assertTrue(hasCandidate(cands, "groupBy"), "Should suggest groupBy");

    // Should also include type-specific functions (fallback behavior)
    assertTrue(hasCandidate(cands, "sum"), "Should suggest sum (fallback)");
    assertTrue(hasCandidate(cands, "uppercase"), "Should suggest uppercase (fallback)");
    assertTrue(hasCandidate(cands, "decorateByTime"), "Should suggest decorateByTime (fallback)");
  }

  // ==================== Always-Applicable Functions Tests ====================

  @Test
  void alwaysApplicableFunctionsAreSuggested() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | ");

    // These functions work with any field type
    assertTrue(hasCandidate(cands, "count"), "count() should always be suggested");
    assertTrue(hasCandidate(cands, "groupBy"), "groupBy() should always be suggested");
    assertTrue(hasCandidate(cands, "top"), "top() should always be suggested");
    assertTrue(hasCandidate(cands, "select"), "select() should always be suggested");
    assertTrue(hasCandidate(cands, "toMap"), "toMap() should always be suggested");
    assertTrue(hasCandidate(cands, "len"), "len() should always be suggested");
    assertTrue(hasCandidate(cands, "decorateByKey"), "decorateByKey() should always be suggested");
  }

  // ==================== Prefix Filtering Tests ====================

  @Test
  void filtersByPrefixMatch() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | co");

    // Should only suggest functions starting with "co"
    assertTrue(hasCandidate(cands, "count"), "Should suggest count");
    assertFalse(hasCandidate(cands, "sum"), "Should NOT suggest sum (doesn't start with 'co')");
    assertFalse(
        hasCandidate(cands, "groupBy"), "Should NOT suggest groupBy (doesn't start with 'co')");
  }

  @Test
  void filtersByPrefixCaseInsensitive() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | SU");

    // Should match case-insensitively
    assertTrue(hasCandidate(cands, "sum"), "Should suggest sum (case-insensitive match)");
    assertFalse(hasCandidate(cands, "count"), "Should NOT suggest count");
  }

  // ==================== Template Format Tests ====================

  @Test
  void completionValueIsSimplifiedForParameterCompletion() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | quant");

    // Completion value should be "quantiles(" to allow field completion
    var quantilesCand = cands.stream().filter(c -> c.value().equals("quantiles(")).findFirst();
    assertTrue(quantilesCand.isPresent(), "Should have quantiles( completion");

    // Display text should show template with example parameters
    assertTrue(
        quantilesCand.get().displ().contains("0.5"),
        "Display should show template with example values");
  }

  @Test
  void groupByDisplayShowsTemplate() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | group");

    // Completion value should be "groupBy(" for field completion
    var groupByCand = cands.stream().filter(c -> c.value().equals("groupBy(")).findFirst();
    assertTrue(groupByCand.isPresent(), "Should have groupBy( completion");

    // Display text should show template with example parameters
    assertTrue(
        groupByCand.get().displ().contains("agg="),
        "Display should show template with agg parameter example");
  }

  // ==================== Description Tests ====================

  @Test
  void completionsHaveDescriptions() throws Exception {
    List<Candidate> cands = complete("show events/test.EmptyEvent | ");

    // All completions should have descriptions
    var countCand = cands.stream().filter(c -> c.value().contains("count")).findFirst();
    assertTrue(countCand.isPresent(), "Should have count completion");
    assertNotNull(countCand.get().descr(), "count should have description");
    assertFalse(countCand.get().descr().isEmpty(), "count description should not be empty");
  }

  // ==================== Helper Methods ====================

  private List<Candidate> complete(String input) {
    List<Candidate> candidates = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine(input);
    completer.complete(null, pl, candidates);
    return candidates;
  }

  private boolean hasCandidate(List<Candidate> candidates, String functionName) {
    return candidates.stream().anyMatch(c -> c.value().contains(functionName));
  }
}
