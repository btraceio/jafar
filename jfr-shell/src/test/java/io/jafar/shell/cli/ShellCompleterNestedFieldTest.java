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
import org.junit.jupiter.api.Test;

/**
 * Tests for nested field completion in select() and other function parameters. Verifies that typing
 * "select(stackTrace/<TAB>" completes to fields within the stackTrace type.
 */
class ShellCompleterNestedFieldTest {

  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void completesNestedFieldsInSelectWithSlash() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // Test completion after "select(stackTrace/"
    // jdk.ExecutionSample has a stackTrace field of type jdk.types.StackTrace
    // jdk.types.StackTrace has fields like "frames" and "truncated"
    var pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | select(stackTrace/");
    completer.complete(null, pl, cands);

    // Should suggest fields from jdk.types.StackTrace
    // Candidates include the JLine word prefix "select(" so JLine can properly replace the word
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace/frames")),
        "Should complete select(stackTrace/frames");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace/truncated")),
        "Should complete select(stackTrace/truncated");
  }

  @Test
  void completesNestedFieldsInSelectWithDot() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // Test completion with dot notation "select(stackTrace."
    var pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | select(stackTrace.");
    completer.complete(null, pl, cands);

    // Should suggest fields from jdk.types.StackTrace with dot notation
    // Candidates include the JLine word prefix "select(" so JLine can properly replace the word
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace.frames")),
        "Should complete select(stackTrace.frames");
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace.truncated")),
        "Should complete select(stackTrace.truncated");
  }

  @Test
  void completesPartialNestedField() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // Test partial completion "select(stackTrace/f" should complete to "frames"
    var pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | select(stackTrace/f");
    completer.complete(null, pl, cands);

    // Should only suggest fields starting with 'f'
    // Candidates include the JLine word prefix "select(" so JLine can properly replace the word
    assertTrue(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace/frames")),
        "Should complete select(stackTrace/frames");
    assertFalse(
        cands.stream().anyMatch(c -> c.value().equals("select(stackTrace/truncated")),
        "Should not complete select(stackTrace/truncated (doesn't start with 'f')");
  }

  @Test
  void completesNestedFieldsInGroupBy() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // Test that nested completion also works in groupBy()
    var pl =
        new ShellCompleterTest.SimpleParsedLine(
            "show events/jdk.ExecutionSample | groupBy(sampledThread/");
    completer.complete(null, pl, cands);

    // sampledThread is of type jdk.types.Thread which has fields like osName, javaName, etc
    // We just verify that we get some completions (exact fields may vary)
    assertFalse(cands.isEmpty(), "Should get nested field completions in groupBy()");
  }
}
