package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathEvaluatorChunksTest {

  @Test
  void chunksRowsAndProjection() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    Path jfr = Path.of("..", "parser", "src", "test", "resources", "test-jfr.jfr");
    when(session.getRecordingPath()).thenReturn(jfr);

    var eval = new JfrPathEvaluator();
    var q = JfrPathParser.parse("chunks");
    List<Map<String, Object>> rows = eval.evaluate(session, q);
    assertFalse(rows.isEmpty());
    Map<String, Object> first = rows.get(0);
    assertTrue(first.containsKey("size"));
    assertTrue(first.containsKey("startNanos"));

    var qp = JfrPathParser.parse("chunks/size");
    List<Object> sizes = eval.evaluateValues(session, qp);
    assertEquals(rows.size(), sizes.size());
  }
}
