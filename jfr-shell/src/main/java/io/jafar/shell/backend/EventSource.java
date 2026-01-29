package io.jafar.shell.backend;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Abstraction for streaming events from a JFR recording. */
public interface EventSource {

  /** An event with its type name and field values. */
  record Event(String typeName, Map<String, Object> value) {}

  /**
   * Stream all events from the recording to the consumer.
   *
   * @param recording path to the JFR recording file
   * @param consumer receives each event as it is parsed
   * @throws Exception if parsing fails
   */
  void streamEvents(Path recording, Consumer<Event> consumer) throws Exception;
}
