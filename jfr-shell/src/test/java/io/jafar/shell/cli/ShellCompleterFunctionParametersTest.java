package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for function parameter completion: select(), sum(), groupBy(), top() */
class ShellCompleterFunctionParametersTest {
  private ShellCompleter completer;

  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @BeforeEach
  void setUp() throws Exception {
    // Use real JFR file so metadata loading works
    Path jfr = resource("test-ap.jfr"); // Use test-ap.jfr which has JDK events
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);
    completer = new ShellCompleter(sessions);
  }

  @Test
  void completesSelectFieldsImmediatelyAfterOpenParen() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | select(");

    completer.complete(null, pl, cands);

    // Debug: print what we got
    System.out.println("Candidates for 'select(': " + cands.stream().map(c -> c.value()).toList());

    // Should suggest field names, not show command options
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside select()");
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--format")),
        "Should not suggest --format inside select()");

    // Should suggest some field names (we don't know exact field names without reading real
    // metadata)
    assertFalse(cands.isEmpty(), "Should suggest field names inside select()");
  }

  @Test
  void completesSelectFieldsAfterComma() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | select(startTime, ");

    completer.complete(null, pl, cands);

    // Should suggest more field names, not show command options
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside select()");
    assertFalse(cands.isEmpty(), "Should suggest field names after comma in select()");
  }

  @Test
  void completesSelectFieldsWithPartialInput() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | select(st");

    completer.complete(null, pl, cands);

    // Should suggest field names starting with "st"
    assertFalse(cands.isEmpty(), "Should suggest field names matching partial input");
  }

  @Test
  void completesSumFieldsImmediatelyAfterOpenParen() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | sum(");

    completer.complete(null, pl, cands);

    System.out.println("Candidates for 'sum(':");
    System.out.println("Words: " + pl.words());
    System.out.println("Current word: '" + pl.word() + "'");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "'");
    }

    // Should suggest field names, not show command options
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside sum()");
    assertFalse(cands.isEmpty(), "Should suggest field names inside sum()");
  }

  @Test
  void completesGroupByFieldsImmediatelyAfterOpenParen() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | groupBy(");

    completer.complete(null, pl, cands);

    System.out.println("Candidates for 'groupBy(': " + cands.stream().map(c -> c.value()).toList());

    // Should NOT suggest template like "groupBy(key)"
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("groupBy(key)")),
        "Should not suggest template 'groupBy(key)' when inside groupBy()");

    // Should suggest field names, not show command options
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside groupBy()");
    assertFalse(cands.isEmpty(), "Should suggest field names inside groupBy()");
  }

  @Test
  void completesTopSecondParameter() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | top(10, ");

    completer.complete(null, pl, cands);

    // Should suggest field names for second parameter
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside top()");
    assertFalse(cands.isEmpty(), "Should suggest field names for top() second parameter");
  }

  @Test
  void doesNotCompleteTopFirstParameter() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | top(");

    completer.complete(null, pl, cands);

    // First parameter is a number, we shouldn't suggest field names
    // The behavior here is to not interfere - could be empty or could suggest numbers
    // Main point: should not suggest --limit, --format, etc.
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside top()");
  }

  @Test
  void completesFilterPredicateFieldsImmediatelyAfterBracket() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[");

    completer.complete(null, pl, cands);

    // Should suggest field names, not show command options
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside filter predicate");
    assertFalse(cands.isEmpty(), "Should suggest field names inside filter predicate");
  }

  @Test
  void completesFilterPredicateOperators() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[startTime ");

    completer.complete(null, pl, cands);

    // Should suggest comparison operators
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("==")), "Should suggest == operator");
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("!=")), "Should suggest != operator");
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("<")), "Should suggest < operator");
    assertTrue(cands.stream().anyMatch(c -> c.value().equals(">")), "Should suggest > operator");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("contains")),
        "Should suggest contains operator");
  }

  @Test
  void completesFilterPredicateLogicalOperators() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[startTime > 0 ");

    completer.complete(null, pl, cands);

    // Should suggest logical operators after complete condition
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("&&")), "Should suggest && operator");
    assertTrue(cands.stream().anyMatch(c -> c.value().equals("||")), "Should suggest || operator");
  }

  @Test
  void completesFilterPredicateFieldsAfterLogicalOperator() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample[startTime > 0 && ");

    completer.complete(null, pl, cands);

    // Should suggest field names again for second condition
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit in filter predicate");
    assertFalse(cands.isEmpty(), "Should suggest field names after logical operator");
  }

  @Test
  void suggestsGroupByTemplateWhenTypingAfterPipe() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | group");

    completer.complete(null, pl, cands);

    System.out.println("Candidates for '| group': " + cands.stream().map(c -> c.value()).toList());

    // When typing "group" after pipe, SHOULD suggest template
    assertTrue(
        cands.stream().anyMatch(c -> c.value().contains("groupBy")),
        "Should suggest groupBy template when typing after pipe");
  }

  @Test
  void doesNotCompleteOutsideFunction() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | select(startTime) | ");

    completer.complete(null, pl, cands);

    // After closing paren, should suggest pipeline functions, not field names from select
    assertTrue(
        cands.stream().anyMatch(c -> c.value().contains("count()")),
        "Should suggest pipeline functions after select() is closed");
  }

  @Test
  void doesNotAddExtraSpaceAfterOpeningParenthesis() throws Exception {
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | groupBy(");

    completer.complete(null, pl, cands);

    System.out.println("Checking candidates for extra space after '(':");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "' complete=" + c.complete());
    }

    // Candidates should not cause "groupBy( fieldName)" with extra space
    // When complete=true, JLine adds a space after the candidate
    // When complete=false, JLine does NOT add a space
    // We want complete=false for field names inside functions
    assertFalse(cands.isEmpty(), "Should have field name candidates");
    for (Candidate c : cands) {
      assertFalse(
          c.complete(),
          "Candidate '" + c.value() + "' should have complete=false to prevent extra space");
    }
  }

  @Test
  void completesGroupByAfterTypingPartialGroupBy() throws Exception {
    // Test the scenario: type "| group" then tab
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | group");

    completer.complete(null, pl, cands);

    System.out.println("Candidates for '| group':");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "' complete=" + c.complete());
    }

    // Should suggest "groupBy(" template
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("groupBy(")),
        "Should suggest 'groupBy(' template");

    // Check if groupBy( template has complete flag set correctly
    var groupByCandidate = cands.stream().filter(c -> c.value().equals("groupBy(")).findFirst();
    assertTrue(groupByCandidate.isPresent(), "groupBy( candidate should exist");

    // The template should NOT add space (complete=false), as we want immediate field completion
    assertFalse(
        groupByCandidate.get().complete(),
        "groupBy( template should have complete=false to prevent extra space after '('");
  }

  @Test
  void suggestsPipelineFunctionsAfterPipeWithSpace() throws Exception {
    // Test: "show events/jdk.ExecutionSample | " (space after pipe)
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | ");

    completer.complete(null, pl, cands);

    System.out.println("Candidates after '| ':");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "'");
    }

    // Should suggest pipeline functions
    assertFalse(cands.isEmpty(), "Should suggest pipeline functions after '| '");
    assertTrue(cands.stream().anyMatch(c -> c.value().contains("count")), "Should suggest count()");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().contains("groupBy")), "Should suggest groupBy(");
  }

  @Test
  void suggestsPipelineFunctionsAfterPipeWithPartialInput() throws Exception {
    // Test: "show events/jdk.ExecutionSample | grou" (partial word after pipe and space)
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample | grou");

    completer.complete(null, pl, cands);

    System.out.println("Candidates after '| grou':");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "'");
    }

    // Should suggest groupBy
    assertFalse(cands.isEmpty(), "Should suggest completions for 'grou'");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().contains("groupBy")),
        "Should suggest groupBy( when typing 'grou'");
  }

  @Test
  void suggestsPipelineFunctionsWhenPipeIsPartOfPreviousWord() throws Exception {
    // Test: "show events/jdk.ExecutionSample|" (NO space after pipe)
    // JLine tokenizes on whitespace, so | is part of the previous word
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample|");

    completer.complete(null, pl, cands);

    System.out.println("Candidates after 'events/jdk.ExecutionSample|':");
    System.out.println("Words: " + pl.words());
    System.out.println("Current word: '" + pl.word() + "'");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "'");
    }

    // Should suggest pipeline functions with space prefix (not "| " prefix)
    assertFalse(cands.isEmpty(), "Should suggest pipeline functions after |");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().trim().startsWith("count")),
        "Should suggest count()");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().trim().startsWith("groupBy")),
        "Should suggest groupBy(");
    // Should NOT include the | prefix since it's already in the word
    assertFalse(
        cands.stream().anyMatch(c -> c.value().startsWith("|")),
        "Should not include | prefix when | is already part of current word");
  }

  @Test
  void completesSumFieldsWhenSumIsPartOfPreviousToken() throws Exception {
    // Test: "show events/jdk.ExecutionSample|sum(" (NO spaces, sum( attached to |)
    List<Candidate> cands = new ArrayList<>();
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample|sum(");

    completer.complete(null, pl, cands);

    System.out.println("Candidates for 'events/jdk.ExecutionSample|sum(':");
    System.out.println("Words: " + pl.words());
    System.out.println("Current word: '" + pl.word() + "'");
    for (Candidate c : cands) {
      System.out.println("  value='" + c.value() + "'");
    }

    // Should suggest field names even when sum( is part of a larger token
    assertFalse(cands.isEmpty(), "Should suggest field names inside sum()");
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("--limit")),
        "Should not suggest --limit inside sum()");
  }
}
