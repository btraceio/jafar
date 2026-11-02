package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A read racing {@code invalidate()} / {@code setCachedResult()} must see either the old rows, the
 * new rows, or "not evaluated" — never "evaluated" with no rows. With {@code evaluated} and {@code
 * cachedResult} as two separate volatiles, {@code get()} could read {@code evaluated == true}, lose
 * the CPU to {@code invalidate()} (which nulls {@code cachedResult}), and return {@code null};
 * {@code copy()} could do the same.
 */
class LazyQueryValueRaceTest {
  private static final List<Map<String, Object>> ROWS = List.of(Map.of("count", 42L));

  @Test
  void racingReadsNeverSeeEvaluatedWithoutRows() throws Exception {
    // A session the evaluator cannot read: if a reader catches the value invalidated it tries to
    // evaluate, fails, and moves on. Only a "cached" read returning null is a failure.
    JFRSession session = mock(JFRSession.class);
    var ref = new SessionManager.SessionRef<>(1, null, session);
    LazyQueryValue value = new LazyQueryValue(JfrPathParser.parse("events/x"), ref, "events/x");
    value.setCachedResult(ROWS);

    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger nullReads = new AtomicInteger();
    AtomicInteger cachedReads = new AtomicInteger();
    int readers = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    CountDownLatch done = new CountDownLatch(readers + 1);

    Thread writer =
        new Thread(
            () -> {
              try {
                while (!stop.get()) {
                  value.invalidate();
                  value.setCachedResult(ROWS);
                }
              } finally {
                done.countDown();
              }
            });
    writer.start();
    for (int i = 0; i < readers; i++) {
      new Thread(
              () -> {
                try {
                  while (!stop.get()) {
                    try {
                      Object rows = value.get();
                      if (rows == null) {
                        nullReads.incrementAndGet();
                      } else {
                        assertSame(ROWS, rows);
                        cachedReads.incrementAndGet();
                      }
                      LazyQueryValue copy = value.copy();
                      if (copy.isCached() && copy.get() == null) {
                        nullReads.incrementAndGet();
                      }
                    } catch (Exception notEvaluable) {
                      // caught it invalidated and tried to evaluate the mock session: fine
                    }
                  }
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    // Stop at the first torn read, or after two seconds of trying
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && nullReads.get() == 0) {
      Thread.sleep(10);
    }
    stop.set(true);
    done.await(10, TimeUnit.SECONDS);

    assertEquals(0, nullReads.get(), "reads that saw 'evaluated' with null rows");
    if (cachedReads.get() == 0) {
      throw new AssertionError("no read ever saw the cached rows; the test exercised nothing");
    }
  }
}
