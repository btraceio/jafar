package io.jafar.shell.jfrpath;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JfrPathInterleavedFiltersTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void gcHeapSummaryFilterBeforeProjection() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (p, c) -> new JFRSession(p, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var q = JfrPathParser.parse("events/jdk.GCHeapSummary[when/when=\"After GC\"]/heapSpace");
        var values = evaluator.evaluateValues(sessions.getCurrent().get().session, q);
        assertFalse(values.isEmpty(), "Expected some heapSpace values after GC");
    }

    @Test
    void gcHeapSummaryFilterRelativeToProjection() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (p, c) -> new JFRSession(p, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var q = JfrPathParser.parse("events/jdk.GCHeapSummary/heapSpace[committedSize>1000000]/reservedSize");
        var values = evaluator.evaluateValues(sessions.getCurrent().get().session, q);
        assertFalse(values.isEmpty(), "Expected reservedSize values filtered by committedSize");
        for (Object v : values) {
            assertTrue(v instanceof Number, "reservedSize should be numeric");
        }
    }
}

