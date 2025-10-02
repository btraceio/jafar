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

class ShellCompleterMetadataFilterOperatorsTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void suggestsOperatorsInMetadataFilter() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        ShellCompleter completer = new ShellCompleter(sessions);
        List<Candidate> cands = new ArrayList<>();

        var pl = new ShellCompleterTest.SimpleParsedLine("show metadata/jdk.ExecutionSample[stackTrace");
        completer.complete(null, pl, cands);

        boolean hasEq = cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample[stackTrace="));
        boolean hasRegex = cands.stream().anyMatch(c -> c.value().equals("metadata/jdk.ExecutionSample[stackTrace~"));
        assertTrue(hasEq || hasRegex, "Expected operator suggestions for metadata filter field");
    }
}
