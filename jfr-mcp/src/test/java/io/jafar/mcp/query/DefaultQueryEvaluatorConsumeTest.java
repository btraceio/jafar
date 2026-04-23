package io.jafar.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.mcp.SimpleJfrFileBuilder;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DefaultQueryEvaluatorConsumeTest {

  private static JFRSession realSession;

  @BeforeAll
  static void openSession() throws Exception {
    Path jfrFile = SimpleJfrFileBuilder.createExecutionSampleFile(3);
    SessionRegistry registry = new SessionRegistry();
    SessionRegistry.SessionInfo info = registry.open(jfrFile, null);
    realSession = info.session();
  }

  @Test
  void queryEvaluatorDefaultConsumeIteratesEvaluateResultsInOrder() throws Exception {
    Map<String, Object> event1 = Map.of("id", 1);
    Map<String, Object> event2 = Map.of("id", 2);

    QueryEvaluator stub =
        new QueryEvaluator() {
          @Override
          public List<Map<String, Object>> evaluate(JFRSession s, JfrPath.Query q)
              throws Exception {
            return List.of(event1, event2);
          }
        };

    JfrPath.Query q = JfrPathParser.parse("events/jdk.ExecutionSample");

    List<Map<String, Object>> collected = new ArrayList<>();
    stub.consume(realSession, q, collected::add);

    assertEquals(2, collected.size());
    assertEquals(event1, collected.get(0));
    assertEquals(event2, collected.get(1));
  }
}
