package io.jafar.shell.jfrpath;

import io.jafar.shell.JFRSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class JfrPathEvaluatorTest {

    @Test
    void filtersByTypeAndRegexPredicate() throws Exception {
        // Fake session and event source
        JFRSession session = Mockito.mock(JFRSession.class);
        when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

        var src = new JfrPathEvaluator.EventSource() {
            @Override
            public void streamEvents(Path recording, java.util.function.Consumer<JfrPathEvaluator.Event> consumer) {
                consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of(
                        "thread", Map.of("name", "main"),
                        "bytes", 42
                )));
                consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of(
                        "thread", Map.of("name", "worker-1"),
                        "bytes", 7
                )));
                consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of(
                        "path", "/etc/hosts"
                )));
            }
        };

        var eval = new JfrPathEvaluator(src);
        var q = JfrPathParser.parse("events/jdk.ExecutionSample[thread/name~\"main\"]");
        List<Map<String, Object>> out = eval.evaluate(session, q);
        assertEquals(1, out.size());
        assertEquals("main", ((Map<?,?>)out.get(0).get("thread")).get("name"));
    }

    @Test
    void numericComparison() throws Exception {
        JFRSession session = Mockito.mock(JFRSession.class);
        when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

        var src = (JfrPathEvaluator.EventSource) (recording, consumer) -> {
            consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 1500)));
            consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 500)));
        };

        var eval = new JfrPathEvaluator(src);
        var q = JfrPathParser.parse("events/jdk.FileRead[bytes>=1000]");
        List<Map<String, Object>> out = eval.evaluate(session, q);
        assertEquals(1, out.size());
        assertEquals(1500, out.get(0).get("bytes"));
    }
}

