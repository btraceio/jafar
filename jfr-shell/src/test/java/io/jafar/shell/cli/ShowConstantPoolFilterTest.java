package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShowConstantPoolFilterTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void filtersCpEntriesByStringEquality() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    sessions.open(jfr, null);

    var evaluator = new JfrPathEvaluator();
    // Load unfiltered symbol entries
    List<Map<String, Object>> all =
        evaluator.evaluate(
            sessions.getCurrent().get().session, JfrPathParser.parse("cp/jdk.types.Symbol"));
    assertFalse(all.isEmpty(), "Expected symbol entries");

    // Find one concrete string value
    String match = null;
    for (Map<String, Object> r : all) {
      Object v = r.get("string");
      if (v instanceof String s && !s.isEmpty()) {
        match = s;
        break;
      }
    }
    assertNotNull(match, "Expected a non-empty 'string' in symbol pool");

    // Now filter by that exact string
    String expr =
        "cp/jdk.types.Symbol[string=\"" + match.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    List<Map<String, Object>> filtered =
        evaluator.evaluate(sessions.getCurrent().get().session, JfrPathParser.parse(expr));
    assertFalse(filtered.isEmpty(), "Expected at least one filtered row");
    assertTrue(filtered.size() <= all.size(), "Filtered size should be <= full size");
    for (Map<String, Object> r : filtered) {
      assertEquals(match, r.get("string"), "Filtered row must match predicate");
    }
  }
}
