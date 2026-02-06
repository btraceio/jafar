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

class ShellCompleterFieldSubpropsTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void suggestsFieldSubproperties() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> cands = new ArrayList<>();

    // simulate typing "show metadata/jdk.ExecutionSample/"
    ShellCompleterTest.SimpleParsedLine pl =
        new ShellCompleterTest.SimpleParsedLine("show metadata/jdk.ExecutionSample/");
    completer.complete(null, pl, cands);
    boolean hasFieldsSegment =
        cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample/fields"));
    assertTrue(hasFieldsSegment, "Expected 'fields' segment suggestion under metadata type");
  }
}
