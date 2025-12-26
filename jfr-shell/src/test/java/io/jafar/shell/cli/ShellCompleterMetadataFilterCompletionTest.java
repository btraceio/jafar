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

class ShellCompleterMetadataFilterCompletionTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void suggestsFieldsInsideMetadataFilterAfterType() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing: show metadata/jdk.ExecutionSample[
    var pl = new ShellCompleterTest.SimpleParsedLine("show metadata/jdk.ExecutionSample[");
    completer.complete(null, pl, cands);

    boolean hasStackTrace =
        cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample[stackTrace"));
    boolean hasSampledThread =
        cands.stream()
            .anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample[sampledThread"));
    assertTrue(
        hasStackTrace || hasSampledThread,
        "Expected metadata field suggestions in filter after type");
  }
}
