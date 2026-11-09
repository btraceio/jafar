package io.jafar.shell.live;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;

/**
 * A shell session bound to a RUNNING target JVM's JFR event repository (live tail, issue #92).
 *
 * <p>Lifecycle contract (deliberate, do not "simplify"):
 *
 * <ul>
 *   <li>The stream is consumed on the JDK's stream dispatch thread into a bounded queue of RAW
 *       {@link RecordedEvent}s (conversion is deferred to the consumer — converted maps of
 *       stack-trace events are orders of magnitude larger than the raw event). The stream is opened
 *       with {@code setReuse(false)}: with reuse enabled the JDK rotates a per-type 2-slot {@code
 *       RecordedEvent} cache in place and the javadoc forbids keeping a reference past the {@code
 *       onEvent} callback — queued events would corrupt.
 *   <li>Slow-consumer policy is drop-oldest, keep-newest (tail semantics favor the newest events;
 *       the target cannot be back-pressured through an on-disk repository anyway). Drops are
 *       counted and reported via {@link #getStatistics()} / the tail status line.
 *   <li>{@link #close()} is idempotent and does NOT unregister from the session manager —
 *       unregistration is manager-owned ({@code closeById} removes the entry before calling close,
 *       so a reverse call would re-enter the manager monitor). Closing enqueues an end-marker so an
 *       in-flight {@code tail} observes closure promptly.
 * </ul>
 */
public final class LiveJfrSession implements io.jafar.shell.core.Session {

  /** End-of-stream sentinel; {@code null} cannot enter a {@link BlockingQueue}. */
  private static final Object END_MARKER = new Object();

  private final int pid;
  private final Path repository;
  private final String type;

  private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(1024);
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong received = new AtomicLong();
  private final AtomicReference<String> streamState = new AtomicReference<>("starting");
  private final long attachTimeMillis = System.currentTimeMillis();
  private volatile boolean closed;
  private volatile boolean ended;
  private final EventStream stream;
  private final Set<String> availableTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * @param pid target process id (reported, not polled)
   * @param repository the target's JFR event repository directory
   * @param type session type id, always {@code "jfr-live"} for real sessions (injectable for tests)
   * @param streamFactory opens the {@link EventStream} — production passes {@code
   *     EventStream::openRepository}; tests inject scripted fakes
   */
  public LiveJfrSession(int pid, Path repository, String type, EventStreamFactory streamFactory)
      throws IOException {
    this.pid = pid;
    this.repository = repository;
    this.type = type;
    this.stream = streamFactory.open(repository);
    // MUST stay false: reuse rotates per-type RecordedEvent instances in place, and this
    // session queues raw events for deferred conversion (see class javadoc).
    this.stream.setReuse(false);
    this.stream.setOrdered(true);
    this.stream.onMetadata(
        e -> {
          for (jdk.jfr.EventType t : e.getAddedEventTypes()) {
            availableTypes.add(t.getName());
          }
        });
    this.stream.onEvent(this::enqueue);
    this.stream.onClose(
        () -> {
          streamState.compareAndSet("starting", "closed");
          streamState.compareAndSet("running", "closed");
        });
    this.stream.onError(
        e -> {
          streamState.compareAndSet("starting", "error: " + e.getMessage());
          streamState.compareAndSet("running", "error: " + e.getMessage());
        });
  }

  /** Starts the stream ({@code startAsync}). Call once, after construction. */
  public void start() {
    stream.startAsync();
    // startAsync's dispatch may deliver ALL events and close (openFile reaches EOF) before
    // this line runs — the close/error transitions below are CAS-based so that overwriting
    // "closed"/"error" back to "running" here is impossible.
    streamState.compareAndSet("starting", "running");
  }

  /** Factory for the underlying {@link EventStream} (injection seam for tests). */
  public interface EventStreamFactory {
    EventStream open(Path repository) throws IOException;
  }

  private void enqueue(RecordedEvent event) {
    if (closed) {
      return; // in-flight deliveries after close are dropped — the end-marker already sits in
    }
    received.incrementAndGet();
    while (!queue.offer(event)) {
      // drop-oldest, keep-newest
      queue.poll();
      dropped.incrementAndGet();
    }
  }

  /**
   * Polls the next raw event, waiting up to {@code timeoutMillis}.
   *
   * @return the event, {@code null} when the stream ended (session closed or target stream closed),
   *     or {@code null} on timeout with the session still live — callers distinguish via {@link
   *     #isClosed()} / {@link #getStreamState()}
   * @throws InterruptedException when the polling thread is interrupted (the tail-cancel path)
   */
  public RecordedEvent pollEvent(long timeoutMillis) throws InterruptedException {
    if (ended) {
      return null;
    }
    Object item = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    if (item == null) {
      // poll again briefly so a close racing with the timeout still drains cleanly
      item = queue.poll(50, TimeUnit.MILLISECONDS);
    }
    if (item == END_MARKER) {
      ended = true; // everything after the marker is post-close noise; stop reading
      return null;
    }
    return item == null ? null : (RecordedEvent) item;
  }

  public int getPid() {
    return pid;
  }

  public Path getRepository() {
    return repository;
  }

  public long getDroppedCount() {
    return dropped.get();
  }

  public long getReceivedCount() {
    return received.get();
  }

  public String getStreamState() {
    return streamState.get();
  }

  // --- Session ---

  @Override
  public String getType() {
    return type;
  }

  /**
   * Synthetic path: the {@code Session} interface mandates one and session-list renderers show it.
   * It is never opened as a file — file-bound commands are guarded against live sessions before any
   * file I/O.
   */
  @Override
  public Path getFilePath() {
    return Path.of("live-pid-" + pid);
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public Set<String> getAvailableTypes() {
    return Set.copyOf(availableTypes);
  }

  @Override
  public Map<String, Object> getStatistics() {
    Map<String, Object> stats = new java.util.LinkedHashMap<>();
    stats.put("pid", pid);
    stats.put("repository", repository.toString());
    stats.put("eventsSeen", received.get());
    stats.put("dropped", dropped.get());
    stats.put("state", streamState.get());
    stats.put("attachedAt", java.time.Instant.ofEpochMilli(attachTimeMillis).toString());
    return stats;
  }

  /** Idempotent close; the end-marker wakes pollers. Does NOT unregister from the manager. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      stream.close();
    } catch (Throwable ignored) {
      // closing a stream whose target died can throw; the session is closed either way
    }
    streamState.set("closed");
    queue.offer(END_MARKER);
  }

  /** Uptime since attach, for status display. */
  public Duration uptime() {
    return Duration.ofMillis(System.currentTimeMillis() - attachTimeMillis);
  }

  /** Sorted type-name snapshot for display. */
  public List<String> typeList() {
    return availableTypes.stream().sorted().toList();
  }
}
