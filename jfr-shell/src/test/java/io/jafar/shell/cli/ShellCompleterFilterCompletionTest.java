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

class ShellCompleterFilterCompletionTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void suggestsEventAttributesWhenFilterOpenedAfterType() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing: show events/jdk.ExecutionSample[
    var pl = new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[");
    completer.complete(null, pl, cands);

    boolean hasStackTrace =
        cands.stream().anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[stackTrace"));
    boolean hasSampledThread =
        cands.stream().anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[sampledThread"));
    assertTrue(
        hasStackTrace || hasSampledThread,
        "Expected field suggestions inside event filter after type");
  }

  @Test
  void suggestsEventAttributesWhenFilterOpenedAfterNestedPath() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing: show events/jdk.ExecutionSample/stackTrace[
    var pl = new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample/stackTrace[");
    completer.complete(null, pl, cands);

    boolean hasFrames =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample/stackTrace[frames"));
    boolean hasTruncated =
        cands.stream()
            .anyMatch(c -> c.value().equals("events/jdk.ExecutionSample/stackTrace[truncated"));
    assertTrue(
        hasFrames || hasTruncated, "Expected field suggestions inside event filter at nested path");
  }

  @Test
  void suggestsCpAttributesWhenFilterOpenedAfterType() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing: show cp/jdk.types.Symbol[
    var pl = new ShellCompleterTest.SimpleParsedLine("show cp/jdk.types.Symbol[");
    completer.complete(null, pl, cands);

    boolean hasString =
        cands.stream().anyMatch(c -> c.value().equals("cp/jdk.types.Symbol[string"));
    assertTrue(hasString, "Expected CP field suggestions inside filter after type");
  }
}
