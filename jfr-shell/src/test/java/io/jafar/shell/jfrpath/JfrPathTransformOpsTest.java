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

class JfrPathTransformOpsTest {
    private static Path resource(String name) {
        return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
    }

    @Test
    void uppercaseOnCpSymbolString() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        // grab a baseline string
        var qStr = JfrPathParser.parse("cp/jdk.types.Symbol/string");
        var vals = evaluator.evaluateValues(sessions.getCurrent().get().session, qStr);
        String sample = null;
        for (Object v : vals) { if (v instanceof String s && !s.isEmpty()) { sample = s; break; } }
        assertNotNull(sample, "Need a symbol string");

        // transform via uppercase()
        var q = JfrPathParser.parse("cp/jdk.types.Symbol/string | uppercase()");
        List<Map<String, Object>> rows = evaluator.evaluate(sessions.getCurrent().get().session, q);
        assertFalse(rows.isEmpty());
        // find a row that matches our sample
        String up = sample.toUpperCase(java.util.Locale.ROOT);
        boolean found = false;
        for (Map<String, Object> r : rows) {
            Object v = r.get("value");
            if (up.equals(v)) { found = true; break; }
        }
        assertTrue(found, "Expected to find uppercased sample");
    }

    @Test
    void roundOnCpuLoadMachineTotal() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var q = JfrPathParser.parse("events/jdk.CPULoad/machineTotal | round()");
        List<Map<String, Object>> rows = evaluator.evaluate(sessions.getCurrent().get().session, q);
        assertFalse(rows.isEmpty(), "Expected some rows");
        for (Map<String, Object> r : rows) {
            Object v = r.get("value");
            assertTrue(v instanceof Number, "Expected numeric value");
            long n = ((Number) v).longValue();
            assertTrue(n == 0L || n == 1L, "Rounded CPU load should be 0 or 1, got " + n);
        }
    }

    @Test
    void floorAndCeilOnCpuLoad() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var q1 = JfrPathParser.parse("events/jdk.CPULoad/machineTotal | floor()");
        var q2 = JfrPathParser.parse("events/jdk.CPULoad/machineTotal | ceil()");
        var r1 = evaluator.evaluate(sessions.getCurrent().get().session, q1);
        var r2 = evaluator.evaluate(sessions.getCurrent().get().session, q2);
        assertFalse(r1.isEmpty());
        assertFalse(r2.isEmpty());
        for (Map<String, Object> m : r1) assertTrue(m.get("value") instanceof Number);
        for (Map<String, Object> m : r2) assertTrue(m.get("value") instanceof Number);
    }

    @Test
    void containsAndReplaceOnCpSymbol() throws Exception {
        Path jfr = resource("test-ap.jfr");
        ParsingContext ctx = ParsingContext.create();
        SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
        sessions.open(jfr, null);

        var evaluator = new JfrPathEvaluator();
        var qContains = JfrPathParser.parse("cp/jdk.types.Symbol/string | contains(\"java/\")");
        var rows = evaluator.evaluate(sessions.getCurrent().get().session, qContains);
        assertFalse(rows.isEmpty());
        Boolean sawTrue = false;
        for (Map<String,Object> m : rows) {
            Object v = m.get("value");
            if (v instanceof Boolean b && b) { sawTrue = true; break; }
        }
        var qReplace = JfrPathParser.parse("cp/jdk.types.Symbol/string | replace(\"/\",\".\")");
        var rows2 = evaluator.evaluate(sessions.getCurrent().get().session, qReplace);
        assertFalse(rows2.isEmpty());
        boolean hasDot = false;
        for (Map<String,Object> m : rows2) {
            Object v = m.get("value");
            if (v instanceof String s && s.contains(".")) { hasDot = true; break; }
        }
        assertTrue(hasDot || sawTrue);
    }
}
