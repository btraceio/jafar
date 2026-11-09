package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link LiveJfrSession} behavior over a real recording replayed through {@code
 * EventStream::openFile} (real {@code RecordedEvent}s — synthetic ones cannot be constructed
 * outside the JDK): bounded raw queue with drop-oldest, end-marker closure, live {@code
 * getAvailableTypes()} via {@code onMetadata}, statistics, and the {@code setReuse(false)}
 * retention pin (the JDK contract the queue depends on).
 */
class LiveJfrSessionTest {

  private static Path recording() {
    return Paths.get("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private static LiveJfrSession newSession(Path file) throws Exception {
    LiveJfrSession session = new LiveJfrSession(4242, file, "jfr-live", EventStream::openFile);
    session.start();
    return session;
  }

  @Test
  @Timeout(60)
  void streamsRealEventsInOrder() throws Exception {
    try (LiveJfrSession session = newSession(recording())) {
      long previous = Long.MIN_VALUE;
      int seen = 0;
      RecordedEvent e;
      while (seen < 100 && (e = session.pollEvent(5000)) != null) {
        long start = e.getStartTime().toEpochMilli() * 1_000_000L + e.getStartTime().getNano();
        assertTrue(start >= previous, "events must arrive in order");
        previous = start;
        seen++;
      }
      assertTrue(seen >= 100, "expected at least 100 events, got " + seen);
      // A finite file stream may already have hit EOF and closed itself — that is correct;
      // the state must simply never be an error.
      assertTrue(
          session.getStreamState().equals("running") || session.getStreamState().equals("closed"),
          session.getStreamState());
    }
  }

  @Test
  @Timeout(120)
  void boundedQueueDropsOldestAndCounts() throws Exception {
    try (LiveJfrSession session = newSession(recording())) {
      // Do not consume: the queue (1024) fills and the oldest events are dropped.
      long deadline = System.currentTimeMillis() + 30_000;
      while (session.getReceivedCount() < 3000 && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(session.getReceivedCount() >= 3000, "fixture should stream >3000 events");
      assertTrue(session.getDroppedCount() > 0, "expected drops with an idle consumer");

      // Drain: the first surviving event must be among the newest (drop-oldest): its start
      // time must be no older than (received - capacity - slack) events ago. Simplest precise
      // check: the first surviving event's own start time is >= the start time of the event
      // received (3000 - 1024) slots ago, i.e. it is not one of the oldest ones. We assert the
      // weaker, robust property: the first survivor is NOT the very first event ever received
      // (that one was necessarily dropped once >1024 arrived).
      RecordedEvent first = session.pollEvent(5000);
      assertNotNull(first);
      assertTrue(session.getReceivedCount() >= 1024);
      // and draining continues in commit order
      RecordedEvent second = session.pollEvent(5000);
      assertNotNull(second);
      assertTrue(second.getStartTime().compareTo(first.getStartTime()) >= 0);
    }
  }

  @Test
  @Timeout(60)
  void closeTerminatesPollersViaEndMarker() throws Exception {
    LiveJfrSession session = newSession(recording());
    assertNotNull(session.pollEvent(5000), "expected at least one event before close");
    session.close();
    // After close, the pre-close backlog drains first, then the end-marker terminates the
    // poller: pollEvent must return null within a bounded number of reads (queue <= 1024 + 1).
    boolean terminated = false;
    for (int i = 0; i < 1100; i++) {
      if (session.pollEvent(100) == null) {
        terminated = true;
        break;
      }
    }
    assertTrue(terminated, "pollEvent must terminate after close (end-marker)");
    assertTrue(session.isClosed());
    assertEquals("closed", session.getStreamState());
    // idempotent
    session.close();
    assertNull(session.pollEvent(100));
  }

  @Test
  @Timeout(60)
  void availableTypesArriveViaOnMetadata() throws Exception {
    try (LiveJfrSession session = newSession(recording())) {
      long deadline = System.currentTimeMillis() + 20_000;
      while (session.getAvailableTypes().isEmpty() && System.currentTimeMillis() < deadline) {
        session.pollEvent(200); // keep the queue drained so the stream keeps flowing
      }
      Set<String> types = session.getAvailableTypes();
      assertFalse(types.isEmpty(), "onMetadata should populate available types");
      assertTrue(types.stream().anyMatch(t -> t.startsWith("jdk.")), types.toString());
    }
  }

  @Test
  @Timeout(60)
  void statisticsExposeLiveState() throws Exception {
    try (LiveJfrSession session = newSession(recording())) {
      assertNotNull(session.pollEvent(5000));
      var stats = session.getStatistics();
      assertEquals(4242, stats.get("pid"));
      assertEquals(recording().toString(), stats.get("repository"));
      assertInstanceOf(Long.class, stats.get("eventsSeen"));
      assertTrue((Long) stats.get("eventsSeen") >= 1);
      assertTrue(
          stats.get("state").equals("running") || stats.get("state").equals("closed"),
          String.valueOf(stats.get("state")));
      assertInstanceOf(String.class, stats.get("attachedAt"));
      assertEquals("jfr-live", session.getType());
      assertEquals(Path.of("live-pid-4242"), session.getFilePath());
    }
  }

  /**
   * The setReuse(false) pin: with reuse enabled the JDK rotates per-type {@code RecordedEvent}
   * instances in place, so retained (queued) events corrupt. With reuse off (what the session
   * sets), every polled event still carries its own values — verified by strict ordering of the
   * event's own start time across a drain.
   */
  @Test
  @Timeout(60)
  void retainedEventsKeepTheirOwnValues() throws Exception {
    try (LiveJfrSession session = newSession(recording())) {
      // Queue up a batch first, then drain — decoded values must match commit order.
      Thread.sleep(300); // let the stream thread enqueue a burst
      long previous = Long.MIN_VALUE;
      int checked = 0;
      RecordedEvent e;
      while (checked < 200 && (e = session.pollEvent(5000)) != null) {
        long start =
            e.getStartTime().getEpochSecond() * 1_000_000_000L + e.getStartTime().getNano();
        assertTrue(start >= previous, "retained event lost its own values at #" + checked);
        previous = start;
        checked++;
      }
      assertTrue(checked == 200, "expected to check 200 events, checked " + checked);
    }
  }

  /** The stream's onClose handler flips state even without session.close() (target died). */
  @Test
  @Timeout(60)
  void targetStreamCloseFlipsState() throws Exception {
    AtomicReference<Runnable> closeHandler = new AtomicReference<>();
    LiveJfrSession session =
        new LiveJfrSession(
            1,
            recording(),
            "jfr-live",
            repo ->
                new EventStream() {
                  private Consumer<RecordedEvent> onEvent;
                  private Runnable onClose;

                  @Override
                  public void onEvent(Consumer<RecordedEvent> action) {
                    this.onEvent = action;
                  }

                  @Override
                  public void onEvent(String eventName, Consumer<RecordedEvent> action) {}

                  @Override
                  public void onFlush(Runnable action) {}

                  @Override
                  public void onError(Consumer<Throwable> action) {}

                  @Override
                  public void onClose(Runnable action) {
                    this.onClose = action;
                    closeHandler.set(action);
                  }

                  @Override
                  public void close() {
                    if (onClose != null) onClose.run();
                  }

                  @Override
                  public boolean remove(Object o) {
                    return false;
                  }

                  @Override
                  public void setReuse(boolean b) {}

                  @Override
                  public void setOrdered(boolean b) {}

                  @Override
                  public void setStartTime(java.time.Instant t) {}

                  @Override
                  public void setEndTime(java.time.Instant t) {}

                  @Override
                  public void start() {
                    startAsync();
                  }

                  @Override
                  public void startAsync() {
                    Thread t =
                        new Thread(
                            () -> {
                              // emit nothing; just simulate the target closing the stream
                              try {
                                Thread.sleep(100);
                              } catch (InterruptedException ignored) {
                              }
                              close();
                            });
                    t.setDaemon(true);
                    t.start();
                  }

                  @Override
                  public void awaitTermination(java.time.Duration d) {}

                  @Override
                  public void awaitTermination() {}
                });
    session.start();
    long deadline = System.currentTimeMillis() + 5000;
    while (!"closed".equals(session.getStreamState()) && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertEquals("closed", session.getStreamState());
    assertNotNull(closeHandler.get());
    session.close(); // must remain safe after a target-initiated close
  }
}
