package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * An event source calls its consumer from several chunk threads at once. Every query below has one
 * exact answer, so an operator whose consumer updates shared state without guarding it fails here:
 * {@code count()} returned 316,700 to 317,233 for 320,000 events of a 32-chunk recording when it
 * incremented a plain {@code long[]}.
 *
 * <p>The source here is deliberately harsher than a recording, and needs none: {@value #THREADS}
 * threads released together, each delivering {@value #PER_THREAD} events with nothing to decode in
 * between. {@code JfrPathConcurrentAggregationTest} covers the same ground on a generated
 * multi-chunk recording.
 */
class ParallelEventSourceTest {
  private static final int THREADS = 8;
  private static final int PER_THREAD = 50_000;
  private static final long TOTAL = (long) THREADS * PER_THREAD;
  private static final int GROUPS = 7;

  private JFRSession session;
  private JfrPathEvaluator evaluator;

  @BeforeEach
  void setUp() throws Exception {
    session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("t.A", "t.B"));
    evaluator = new JfrPathEvaluator(ParallelEventSourceTest::deliverFromManyThreads);
  }

  /** Each thread delivers PER_THREAD events of t.A and as many of t.B, interleaved. */
  private static void deliverFromManyThreads(
      Path recording, java.util.function.Consumer<JfrPathEvaluator.Event> consumer)
      throws Exception {
    CyclicBarrier start = new CyclicBarrier(THREADS);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      int thread = t;
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < PER_THREAD; i++) {
                    long id = (long) thread * PER_THREAD + i;
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", id);
                    a.put("group", "g" + (id % GROUPS));
                    a.put("value", 1L);
                    consumer.accept(new JfrPathEvaluator.Event("t.A", a));
                    Map<String, Object> b = new HashMap<>();
                    b.put("id", id);
                    b.put("value", 2L);
                    consumer.accept(new JfrPathEvaluator.Event("t.B", b));
                  }
                } catch (Throwable e) {
                  failure.compareAndSet(null, e);
                }
              },
              "fake-chunk-" + t);
      threads.add(worker);
      worker.start();
    }
    for (Thread worker : threads) {
      worker.join();
    }
    if (failure.get() != null) {
      throw new IllegalStateException("A delivering thread failed", failure.get());
    }
  }

  private List<Map<String, Object>> run(String query) throws Exception {
    return evaluator.evaluate(session, JfrPathParser.parse(query));
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  @Test
  void countIsExact() throws Exception {
    assertEquals(TOTAL, number(run("events/t.A | count()").get(0).get("count")));
  }

  @Test
  void countOverTwoTypesIsExact() throws Exception {
    assertEquals(2 * TOTAL, number(run("events/(t.A|t.B) | count()").get(0).get("count")));
  }

  @Test
  void countWithPredicateIsExact() throws Exception {
    assertEquals(
        TOTAL / 2, number(run("events/t.A[id<" + (TOTAL / 2) + "] | count()").get(0).get("count")));
  }

  @Test
  void sumIsExact() throws Exception {
    Map<String, Object> row = run("events/t.B | sum(value)").get(0);
    assertEquals(2 * TOTAL, number(row.get("sum")), row.toString());
    assertEquals(TOTAL, number(row.get("count")), row.toString());
  }

  @Test
  void statsCountIsExact() throws Exception {
    Map<String, Object> row = run("events/t.A | stats(value)").get(0);
    assertEquals(TOTAL, number(row.get("count")), row.toString());
  }

  @Test
  void groupByCountsEveryEventOnce() throws Exception {
    List<Map<String, Object>> rows = run("events/t.A | groupBy(group, agg=count)");
    assertEquals(GROUPS, rows.size(), rows.toString());
    long total = 0;
    for (Map<String, Object> row : rows) {
      long expected = TOTAL / GROUPS + (groupIndex(row) < TOTAL % GROUPS ? 1 : 0);
      assertEquals(expected, number(row.get("count")), row.toString());
      total += number(row.get("count"));
    }
    assertEquals(TOTAL, total);
  }

  private static int groupIndex(Map<String, Object> row) {
    return Integer.parseInt(String.valueOf(row.get("key")).substring(1));
  }

  @Test
  void plainRowsAreAllCollected() throws Exception {
    List<Map<String, Object>> rows = run("events/t.A");
    assertEquals(TOTAL, rows.size());
    assertEquals(TOTAL, rows.stream().map(r -> r.get("id")).distinct().count());
  }

  @Test
  void projectedValuesAreAllCollected() throws Exception {
    List<Object> values = evaluator.evaluateValues(session, JfrPathParser.parse("events/t.A/id"));
    assertEquals(TOTAL, values.size());
    assertEquals(TOTAL, values.stream().distinct().count());
  }
}
