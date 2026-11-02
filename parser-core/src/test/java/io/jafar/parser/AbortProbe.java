package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks the {@link Control#abort()} contract without depending on how many chunks a recording has
 * or how many threads parse them.
 *
 * <p>The handler calls {@code abort()} exactly once, when it sees the {@code threshold}-th event.
 * Chunks are parsed in parallel, so an exact total cannot be asserted: another chunk's thread may
 * already be inside a handler when the abort lands. What can be asserted, and what this probe
 * checks, is:
 *
 * <ol>
 *   <li>the thread that called {@code abort()} receives no further event, in any chunk;
 *   <li>every other thread receives at most one event after the abort - the one it was already
 *       dispatching - and then nothing more.
 * </ol>
 *
 * Under a per-chunk (per-thread) abort, rule 2 fails as soon as another chunk has two events left
 * to deliver, and rule 1 fails whenever the pool runs a later chunk on the aborting thread.
 */
public final class AbortProbe {
  private final int threshold;
  private final AtomicInteger seen = new AtomicInteger();
  private final AtomicBoolean abortCalled = new AtomicBoolean();
  private final Set<Thread> abortingThreads = ConcurrentHashMap.newKeySet();
  private final Map<String, AtomicInteger> lateByThread = new ConcurrentHashMap<>();
  private final List<String> violations = Collections.synchronizedList(new ArrayList<>());

  public AbortProbe(int threshold) {
    this.threshold = threshold;
  }

  /** To be called from the event handler, once per delivered event. */
  public void onEvent(Control ctl) {
    Thread t = Thread.currentThread();
    if (abortingThreads.contains(t)) {
      violations.add(
          "event "
              + (seen.get() + 1)
              + " delivered on "
              + t.getName()
              + " after it called abort()");
    }
    if (abortCalled.get()) {
      lateByThread.computeIfAbsent(t.getName(), k -> new AtomicInteger()).incrementAndGet();
    }
    if (seen.incrementAndGet() == threshold) {
      ctl.abort();
      abortingThreads.add(t);
      abortCalled.set(true);
    }
  }

  public int seen() {
    return seen.get();
  }

  /**
   * Asserts the contract.
   *
   * @param fixture name of the recording, for the failure message
   * @param totalMatching how many events the handler would see without an abort; used to check the
   *     parse was actually cut short
   */
  public void assertAborted(String fixture, int totalMatching) {
    assertTrue(
        totalMatching > threshold,
        fixture + ": fixture must hold more than " + threshold + " events, has " + totalMatching);
    assertTrue(
        seen.get() >= threshold, fixture + ": abort fired before threshold, seen=" + seen.get());
    assertTrue(violations.isEmpty(), fixture + ": " + violations);
    lateByThread.forEach(
        (thread, n) ->
            assertTrue(
                n.get() <= 1,
                fixture
                    + ": "
                    + thread
                    + " received "
                    + n.get()
                    + " events after abort(); at most the one in flight is allowed"
                    + " (seen="
                    + seen.get()
                    + ", threshold="
                    + threshold
                    + ", total="
                    + totalMatching
                    + ")"));
    assertTrue(
        seen.get() < totalMatching,
        fixture + ": abort did not stop the parse (seen=" + seen.get() + ")");
  }

  /**
   * The recordings the abort tests run against: {@code test-ap.jfr} as it is, and the same
   * recording concatenated three times. A JFR chunk is self-contained, so the concatenation is a
   * valid three-chunk recording whatever the original holds; the tests therefore exercise more than
   * one chunk even where {@code test-ap.jfr} is a single chunk.
   */
  public static List<Path> fixtures(Path tempDir) {
    Path single = resource("test-ap.jfr");
    List<Path> out = new ArrayList<>();
    out.add(single);
    out.add(concat(tempDir.resolve("test-ap-x3.jfr"), single, single, single));
    return out;
  }

  /** Path of a test resource on the classpath. */
  public static Path resource(String name) {
    URL url = AbortProbe.class.getClassLoader().getResource(name);
    if (url == null) {
      throw new IllegalStateException(name + " not on the test classpath; run ./get_resources.sh");
    }
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes the given recordings back to back into {@code target}. */
  public static Path concat(Path target, Path... parts) {
    try (OutputStream os = Files.newOutputStream(target)) {
      for (Path p : parts) {
        Files.copy(p, os);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return target;
  }
}
