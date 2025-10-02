package io.jafar.shell.jfrpath;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JfrPathLenOpTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void lenOnCpSymbolString() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var q = JfrPathParser.parse("cp/jdk.types.Symbol/string | len()");
        List<Map<String, Object>> rows = evaluator.evaluate(sessions.getCurrent().get().session, q);
        assertFalse(rows.isEmpty(), "Expected len() rows");
        // Validate first non-null
        Integer first = null;
        for (Map<String, Object> r : rows) {
            Object v = r.get("len");
            if (v instanceof Number n) { first = n.intValue(); break; }
        }
        assertNotNull(first, "Expected numeric length");
        // Cross-check by reading raw string
        var q2 = JfrPathParser.parse("cp/jdk.types.Symbol/string");
        var strings = evaluator.evaluateValues(sessions.getCurrent().get().session, q2);
        for (Object s : strings) {
            if (s instanceof String st && st.length() == first) { return; }
        }
        fail("Did not find a matching string of measured length");
    }
}

