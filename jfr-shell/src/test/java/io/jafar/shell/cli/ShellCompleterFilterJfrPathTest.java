package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;

class ShellCompleterFilterJfrPathTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void suggestsNestedPathsInsideEventFilter() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing up to a nested path inside the filter
    var pl = new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[stackTrace/");
    completer.complete(null, pl, cands);

    boolean hasFrames =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[stackTrace/frames"));
    boolean hasTruncated =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[stackTrace/truncated"));
    assertTrue(hasFrames || hasTruncated, "Expected nested field suggestions inside event filter");
  }

  @Test
  void suggestsListPrefixAndFieldsInsideEventFilter() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing a list-mode prefix then expecting fields after any:
    var pl = new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[any:");
    completer.complete(null, pl, cands);

    boolean hasAnyStackTrace =
        cands.stream().anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[any:stackTrace"));
    boolean hasAnySampledThread =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[any:sampledThread"));
    assertTrue(
        hasAnyStackTrace || hasAnySampledThread,
        "Expected list-aware field suggestions after any:");
  }

  @Test
  void suggestsNestedPathsAfterListPrefixInsideEventFilter() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing nested path after list prefix
    var pl =
        new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[any:stackTrace/");
    completer.complete(null, pl, cands);

    boolean hasFrames =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[any:stackTrace/frames"));
    assertTrue(hasFrames, "Expected nested field suggestions after any:stackTrace/");
  }
}
