package io.jafar.shell.jfrpath;

import io.jafar.shell.JFRSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class JfrPathEvaluatorProjectionTest {

    @Test
    void projectsAttributeValues() throws Exception {
        JFRSession session = Mockito.mock(JFRSession.class);
        when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

        var src = (JfrPathEvaluator.EventSource) (rec, consumer) -> {
            consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of("sampledThread", Map.of("javaName", "main"))));
            consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of("sampledThread", Map.of("javaName", "worker-1"))));
        };

        var eval = new JfrPathEvaluator(src);
        var q = JfrPathParser.parse("events/jdk.ExecutionSample/sampledThread/javaName");
        List<Object> out = eval.evaluateValues(session, q);
        assertEquals(2, out.size());
        assertEquals("main", out.get(0));
        assertEquals("worker-1", out.get(1));
    }
}

