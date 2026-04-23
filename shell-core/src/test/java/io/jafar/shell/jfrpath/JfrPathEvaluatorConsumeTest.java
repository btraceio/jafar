package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathEvaluatorConsumeTest {

  private static JFRSession mockSession() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    return session;
  }

  private static JfrPathEvaluator.EventSource twoEventSource() {
    return (recording, consumer) -> {
      consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
      consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 200)));
    };
  }

  @Test
  void singleTypeHappyPath() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator eval = new JfrPathEvaluator(twoEventSource());
    JfrPath.Query q = JfrPathParser.parse("events/jdk.FileRead");

    List<Map<String, Object>> collected = new ArrayList<>();
    eval.consume(session, q, collected::add);

    assertEquals(2, collected.size());
    assertEquals(100, collected.get(0).get("bytes"));
    assertEquals(200, collected.get(1).get("bytes"));
  }

  @Test
  void predicateFilteringDeliveredOnlyMatchingEvents() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator eval = new JfrPathEvaluator(twoEventSource());
    JfrPath.Query q = JfrPathParser.parse("events/jdk.FileRead[bytes>=150]");

    List<Map<String, Object>> collected = new ArrayList<>();
    eval.consume(session, q, collected::add);

    assertEquals(1, collected.size());
    assertEquals(200, collected.get(0).get("bytes"));
  }

  @Test
  void multiTypeQueryDeliversAllMatchingEventTypes() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator.EventSource src =
        (recording, consumer) -> {
          consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 1)));
          consumer.accept(new JfrPathEvaluator.Event("jdk.FileWrite", Map.of("bytes", 2)));
          consumer.accept(new JfrPathEvaluator.Event("jdk.SocketRead", Map.of("bytes", 3)));
        };
    JfrPathEvaluator eval = new JfrPathEvaluator(src);
    JfrPath.Query q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)");

    List<Map<String, Object>> collected = new ArrayList<>();
    eval.consume(session, q, collected::add);

    assertEquals(2, collected.size());
  }

  @Test
  void nonEventsRootThrowsUnsupportedOperationException() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator eval = new JfrPathEvaluator(twoEventSource());
    JfrPath.Query q = JfrPathParser.parse("metadata");

    assertThrows(UnsupportedOperationException.class, () -> eval.consume(session, q, e -> {}));
  }

  @Test
  void emptyEventTypesThrowsIllegalArgumentException() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator eval = new JfrPathEvaluator(twoEventSource());
    JfrPath.Query emptyTypes = new JfrPath.Query(JfrPath.Root.EVENTS, List.of(), List.of());

    assertThrows(IllegalArgumentException.class, () -> eval.consume(session, emptyTypes, e -> {}));
  }

  @Test
  void consumerThrowingRuntimeExceptionPropagates() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator eval = new JfrPathEvaluator(twoEventSource());
    JfrPath.Query q = JfrPathParser.parse("events/jdk.FileRead");

    Consumer<Map<String, Object>> throwing =
        e -> {
          throw new RuntimeException("test-error");
        };

    assertThrows(RuntimeException.class, () -> eval.consume(session, q, throwing));
  }

  @Test
  void emptyResultSetConsumerNeverCalled() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator.EventSource emptySource = (recording, consumer) -> {};
    JfrPathEvaluator eval = new JfrPathEvaluator(emptySource);
    JfrPath.Query q = JfrPathParser.parse("events/jdk.FileRead");

    List<Map<String, Object>> collected = new ArrayList<>();
    eval.consume(session, q, collected::add);

    assertEquals(0, collected.size());
  }

  @Test
  void delegationViaEventSource() throws Exception {
    JFRSession session = mockSession();
    JfrPathEvaluator.EventSource src =
        (recording, consumer) ->
            consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of("tid", 42)));
    JfrPathEvaluator eval = new JfrPathEvaluator(src);
    JfrPath.Query q = JfrPathParser.parse("events/jdk.ExecutionSample");

    List<Map<String, Object>> collected = new ArrayList<>();
    eval.consume(session, q, collected::add);

    assertEquals(1, collected.size());
    assertEquals(42, collected.get(0).get("tid"));
  }
}
