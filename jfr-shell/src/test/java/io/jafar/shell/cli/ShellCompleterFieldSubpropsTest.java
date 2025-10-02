package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCompleterFieldSubpropsTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void suggestsFieldSubproperties() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        ShellCompleter completer = new ShellCompleter(sessions);
        List<Candidate> cands = new ArrayList<>();

        // simulate typing "show metadata/jdk.ExecutionSample/fields/stackTrace/"
        ShellCompleterTest.SimpleParsedLine pl = new ShellCompleterTest.SimpleParsedLine(
                "show metadata/jdk.ExecutionSample/fields/stackTrace/");
        completer.complete(null, pl, cands);
        boolean hasType = cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample/fields/stackTrace/type"));
        boolean hasName = cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample/fields/stackTrace/name"));
        assertTrue(hasType || hasName, "Expected subproperty suggestions for field 'stackTrace'");
    }
}
