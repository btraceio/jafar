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

class ShowConstantPoolSymbolValuesTest {
  private static Path resource(String name) {
    return Paths.get("..", "parser", "src", "test", "resources", name).normalize().toAbsolutePath();
  }

  @Test
  void symbolEntriesHaveNonEmptyString() throws Exception {
    Path jfr = resource("test-ap.jfr");
    ParsingContext ctx = ParsingContext.create();
    SessionManager sessions = new SessionManager((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    sessions.open(jfr, null);

    var evaluator = new JfrPathEvaluator();
    var query = JfrPathParser.parse("cp/jdk.types.Symbol");
    List<Map<String, Object>> rows = evaluator.evaluate((JFRSession) sessions.getCurrent().get().session, query);

    assertFalse(rows.isEmpty(), "Expected some cp/jdk.types.Symbol rows");

    boolean hasNonEmpty = false;
    for (Map<String, Object> r : rows) {
      Object v = r.get("string");
      if (v instanceof String s && !s.isEmpty()) {
        hasNonEmpty = true;
        break;
      }
    }
    if (!hasNonEmpty) {
      StringBuilder sb = new StringBuilder("No non-empty 'string' values found. Sample rows:\n");
      for (int i = 0; i < Math.min(5, rows.size()); i++) sb.append(rows.get(i)).append('\n');
      fail(sb.toString());
    }
  }
}
