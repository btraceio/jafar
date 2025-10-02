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

class ShellCompleterFilterOperatorsTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void suggestsOperatorsForEventFieldInFilter() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        ShellCompleter completer = new ShellCompleter(sessions);
        List<Candidate> cands = new ArrayList<>();

        // simulate typing up to a field path but no operator yet
        var pl = new ShellCompleterTest.SimpleParsedLine("show events/jdk.ExecutionSample[stackTrace/truncated");
        completer.complete(null, pl, cands);

        boolean hasEq = cands.stream().anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[stackTrace/truncated="));
        boolean hasRegex = cands.stream().anyMatch(c -> c.value().equals("events/jdk.ExecutionSample[stackTrace/truncated~"));
        assertTrue(hasEq || hasRegex, "Expected operator suggestions for field inside filter");
    }

    @Test
    void suggestsOperatorsForCpFieldInFilter() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        ShellCompleter completer = new ShellCompleter(sessions);
        List<Candidate> cands = new ArrayList<>();

        var pl = new ShellCompleterTest.SimpleParsedLine("show cp/jdk.types.Symbol[string");
        completer.complete(null, pl, cands);

        boolean hasEq = cands.stream().anyMatch(c -> c.value().equals("cp/jdk.types.Symbol[string="));
        boolean hasRegex = cands.stream().anyMatch(c -> c.value().equals("cp/jdk.types.Symbol[string~"));
        assertTrue(hasEq || hasRegex, "Expected operator suggestions for cp filter field");
    }
}

