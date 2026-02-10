package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRStackTrace;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Test that the parser gracefully handles JFR files with missing constant pools. When a constant
 * pool is missing for a referenced type, the accessor should return null instead of throwing NPE.
 */
public class MissingConstantPoolTest {

  @JfrType("datadog.ExecutionSample")
  public interface DatadogExecutionSample {
    @JfrField("spanId")
    long spanId();

    @JfrField("localRootSpanId")
    long localRootSpanId();

    @JfrField("stackTrace")
    JFRStackTrace stackTrace();
  }

  @TempDir Path tempDir;

  @Test
  void accessorReturnsNullWhenConstantPoolMissing() throws Exception {
    Path jfrFile = tempDir.resolve("missing-cp.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.ExecutionSample event type with minimal fields
      // Note: no stackTrace field registered, so constant pool will be missing
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write execution sample event without stack trace
      recording.writeEvent(
          executionSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 12345L);
                valueBuilder.putField("localRootSpanId", 67890L);
              }));
    }

    AtomicInteger eventCount = new AtomicInteger(0);
    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          DatadogExecutionSample.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            // This should return null gracefully, not throw NPE
            assertNull(event.stackTrace(), "stackTrace() should return null when CP is missing");
          });
      parser.run();
    }

    assert eventCount.get() == 1 : "Expected 1 event, got " + eventCount.get();
  }

  @Test
  void parserHandlesEmptyJfrFile() throws Exception {
    Path jfrFile = tempDir.resolve("empty.jfr");

    // Create empty JFR file with minimal setup
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Just create an empty recording - no events
    }

    AtomicInteger eventCount = new AtomicInteger(0);
    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          DatadogExecutionSample.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
          });
      parser.run();
    }

    assert eventCount.get() == 0 : "Expected 0 events, got " + eventCount.get();
  }
}
