package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathEvaluatorListMatchTest {

  private static Map<String, Object> frame(String methodName) {
    return Map.of("method", Map.of("name", Map.of("string", methodName)));
  }

  @Test
  void matchesAnyAllNoneAcrossFrames() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "stackTrace",
                          Map.of(
                              "truncated",
                              true,
                              "frames",
                              new Object[] {frame("Main.doWork"), frame("Helper.step")}))));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "stackTrace",
                          Map.of(
                              "truncated",
                              false,
                              "frames",
                              new Object[] {frame("Other.run"), frame("Other.more")}))));
            };

    // Default ANY: truncated=true should match first event
    var evalAny = new JfrPathEvaluator(src, JfrPath.MatchMode.ANY);
    var q1 = JfrPathParser.parse("events/jdk.ExecutionSample[stackTrace/truncated=true]");
    assertEquals(1, evalAny.evaluate(session, q1).size());

    // ANY with regex on frames
    var q2 =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample[any:stackTrace/frames/method/name/string~'.*Main.*']");
    assertEquals(1, evalAny.evaluate(session, q2).size());

    // ALL: require all frames to match regex (none do), expect 0
    var evalAll = new JfrPathEvaluator(src, JfrPath.MatchMode.ALL);
    var q3 =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample[all:stackTrace/frames/method/name/string~'.*Main.*']");
    assertEquals(0, evalAll.evaluate(session, q3).size());

    // NONE: require no frames to match regex, second event qualifies
    var evalNone = new JfrPathEvaluator(src, JfrPath.MatchMode.NONE);
    var q4 =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample[none:stackTrace/frames/method/name/string~'.*Main.*']");
    assertEquals(1, evalNone.evaluate(session, q4).size());
  }
}
