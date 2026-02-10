package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathEvaluatorMetadataTest {

  @Test
  void metadataRowAndProjection() throws Exception {
    // Prepare a session mock pointing to a real test JFR file
    JFRSession session = Mockito.mock(JFRSession.class);
    Path jfr = Path.of("..", "parser", "src", "test", "resources", "test-jfr.jfr");
    when(session.getRecordingPath()).thenReturn(jfr);
    when(session.getFilePath()).thenReturn(jfr);

    var eval = new JfrPathEvaluator();

    // Evaluate metadata row for a common type and assert structure
    var q = JfrPathParser.parse("metadata/java.lang.Thread");
    var rows = eval.evaluate(session, q);
    assertTrue(rows.size() <= 1);
    if (!rows.isEmpty()) {
      Map<String, Object> row = rows.get(0);
      assertEquals("java.lang.Thread", row.get("name"));
      assertTrue(row.containsKey("superType"));
      assertTrue(row.containsKey("fields"));
    }

    // Projection should return a scalar if present
    var qp = JfrPathParser.parse("metadata/java.lang.Thread/superType");
    try {
      List<Object> vals = eval.evaluateValues(session, qp);
      if (!vals.isEmpty()) {
        assertTrue(vals.get(0) instanceof String);
      }
    } catch (UnsupportedOperationException e) {
      fail("Evaluator should support metadata projection");
    }
  }
}
