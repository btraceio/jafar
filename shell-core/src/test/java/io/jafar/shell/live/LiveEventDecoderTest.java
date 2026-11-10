package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.backend.EventSource;
import java.nio.file.Path;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LiveEventDecoder} conversion tests against a purpose-built recording (thread-carrying
 * events only — shared fixtures contain event types that trip a pre-existing JDK consumer quirk,
 * see the follow-up issue). The {@code decodeFields} half is the extraction of the jdk file
 * backend's converter — its output shape is a file-backend contract, so it is pinned here
 * field-for-field. The {@code decode} half adds the live time decorations.
 */
class LiveEventDecoderTest {

  /** Test event carrying a thread field and its own startTime/duration declarations. */
  static class ThreadedEvent extends jdk.jfr.Event {
    String label;
    java.lang.Thread thread;
  }

  @TempDir Path tmp;

  private Path recordingWithThreads() throws Exception {
    Path file = tmp.resolve("threaded.jfr");
    try (Recording r = new Recording()) {
      r.setToDisk(true);
      r.setDestination(file);
      r.start();
      for (int i = 0; i < 3; i++) {
        ThreadedEvent e = new ThreadedEvent();
        e.label = "e" + i;
        e.thread = Thread.currentThread();
        e.commit();
      }
      Thread.sleep(50);
      r.stop();
    }
    return file;
  }

  private static RecordedEvent firstEventOfType(Path file, String simpleName) throws Exception {
    try (RecordingFile rf = new RecordingFile(file)) {
      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        if (e.getEventType().getName().endsWith(simpleName)) {
          return e;
        }
      }
    }
    throw new AssertionError("no " + simpleName + " in the fixture");
  }

  @Test
  void decodeFieldsMatchesRawFieldIteration() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    assertEquals(event.getFields().size(), fields.size());
    for (var f : event.getFields()) {
      assertTrue(fields.containsKey(f.getName()), f.getName());
    }
    assertEquals(event.getValue("label"), fields.get("label"));
  }

  @Test
  void threadFlattenCarriesIdentity() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    RecordedThread thread = (RecordedThread) event.getValue("thread");
    @SuppressWarnings("unchecked")
    Map<String, Object> threadMap = (Map<String, Object>) fields.get("thread");
    // RecordedThread.getFields() itself carries the thread identity — the flatten keeps it
    assertEquals(thread.getJavaName(), threadMap.get("javaName"));
    assertEquals(thread.getOSThreadId(), threadMap.get("osThreadId"));
    assertEquals(thread.getJavaThreadId(), threadMap.get("javaThreadId"));
  }

  @Test
  void decodeAddsTimeDecorations() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> decoded = LiveEventDecoder.decode(event);

    long startNanos =
        event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano();
    assertEquals(startNanos, decoded.get("startTime"));
    assertEquals(event.getDuration().toNanos(), decoded.get("duration"));
  }

  @Test
  void decodeRewritesChunkRelativeBuiltinTimeFields() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    long rawStart = (Long) fields.get("startTime");
    long absolute =
        event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano();
    // The raw flatten carries the built-in chunk-relative ticks; the live decode rewrites them
    assertNotEquals(absolute, rawStart);
    Map<String, Object> decoded = LiveEventDecoder.decode(event);
    assertEquals(absolute, decoded.get("startTime"));
    assertEquals(event.getDuration().toNanos(), decoded.get("duration"));
  }

  @Test
  void decodeFeedsTheSameShapeTheEvaluatorConsumes() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    EventSource.Event ev =
        new EventSource.Event(event.getEventType().getName(), LiveEventDecoder.decode(event));
    assertEquals(event.getEventType().getName(), ev.typeName());
    assertTrue(ev.value().containsKey("startTime"));
  }
}
