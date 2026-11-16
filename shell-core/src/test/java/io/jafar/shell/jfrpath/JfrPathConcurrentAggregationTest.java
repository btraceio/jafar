package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.FlameNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Exact results from every operator that reads the event stream, on a recording with many chunks.
 *
 * <p>The parser hands events to a handler from several chunk-parsing threads at once, so each
 * consumer passed to {@code EventSource.streamEvents} runs concurrently with itself. These
 * consumers used to keep their state in plain arrays, {@code ArrayList}s and {@code HashMap}s:
 * {@code events/T | count()} lost increments and returned a different, too small number on each
 * run. The recordings checked in have too few chunks to hit that, so this test generates one whose
 * exact contents it knows: {@link #CHUNKS} recordings made with the running JDK and concatenated (a
 * JFR chunk is self-contained, so a concatenation of JFR files is a JFR file).
 *
 * <p>Every test is repeated: one lucky run proves nothing about a race.
 */
class JfrPathConcurrentAggregationTest {
  private static final int REPETITIONS = 5;

  private static final int CHUNKS = 16;
  private static final int TICKS_PER_CHUNK = 40_000;
  private static final int SAMPLE_EVERY = 10;
  private static final int SPAN_EVERY = 10_000;
  private static final int BUCKETS = 16;

  private static final long TICKS = (long) CHUNKS * TICKS_PER_CHUNK;
  private static final long SAMPLES = TICKS / SAMPLE_EVERY;
  private static final long SPANS = TICKS / SPAN_EVERY;

  private static final String TICK = "race.Tick";
  private static final String SAMPLE = "race.Sample";
  private static final String SPAN = "race.Span";

  @Name(TICK)
  @StackTrace(false)
  static final class Tick extends Event {
    long seq;
    int bucket;
    long one;
  }

  @Name(SAMPLE)
  @StackTrace(true)
  static final class Sample extends Event {
    long seq;
    int bucket;
    long negative;
    String label;
    double ratio;
  }

  @Name(SPAN)
  @StackTrace(false)
  static final class Span extends Event {
    long seq;
  }

  @TempDir static Path dir;

  private static Path recording;
  private static JFRSession session;
  private static JfrPathEvaluator eval;

  @BeforeAll
  static void generateRecording() throws Exception {
    recording = dir.resolve("many-chunks.jfr");
    // A dedicated thread, so every stack trace is the same few frames whatever runs the tests.
    Throwable[] failure = new Throwable[1];
    Thread generator =
        new Thread(
            () -> {
              try {
                generate(recording);
              } catch (Throwable t) {
                failure[0] = t;
              }
            },
            "race-generator");
    generator.start();
    generator.join();
    if (failure[0] != null) {
      throw new IllegalStateException("could not generate the recording", failure[0]);
    }

    session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(recording);
    eval = new JfrPathEvaluator();
    sampleSeqInFileOrder = readSampleSeqInFileOrder();
    assertEquals(SAMPLES, sampleSeqInFileOrder.size());
  }

  private static void generate(Path out) throws IOException {
    Path segment = dir.resolve("segment.jfr");
    long tick = 0;
    long sample = 0;
    long span = 0;
    try (OutputStream os = Files.newOutputStream(out)) {
      for (int chunk = 0; chunk < CHUNKS; chunk++) {
        try (Recording r = new Recording()) {
          r.enable(Tick.class);
          r.enable(Sample.class);
          r.enable(Span.class);
          r.start();
          for (int i = 0; i < TICKS_PER_CHUNK; i++) {
            Tick t = new Tick();
            t.seq = tick;
            t.bucket = (int) (tick % BUCKETS);
            t.one = 1;
            t.commit();
            if (tick % SAMPLE_EVERY == 0) {
              Sample s = new Sample();
              s.seq = sample;
              s.bucket = (int) (sample % BUCKETS);
              s.negative = -sample;
              s.label = "ab";
              s.ratio = sample / 3.0;
              s.commit();
              sample++;
            }
            if (tick % SPAN_EVERY == 0) {
              Span s = new Span();
              s.seq = span++;
              s.commit();
            }
            tick++;
          }
          r.stop();
          r.dump(segment);
        }
        Files.copy(segment, os);
        Files.delete(segment);
      }
    }
  }

  private static List<Map<String, Object>> run(String query) throws Exception {
    return eval.evaluate(session, JfrPathParser.parse(query));
  }

  private static Map<String, Object> single(String query) throws Exception {
    List<Map<String, Object>> rows = run(query);
    assertEquals(1, rows.size(), query);
    return rows.get(0);
  }

  private static long num(Object value) {
    return ((Number) value).longValue();
  }

  @RepeatedTest(REPETITIONS)
  void recordingHasManyChunks() throws Exception {
    // The premise of every other test here: without several chunks nothing runs concurrently.
    assertEquals(CHUNKS, run("chunks").size());
  }

  @RepeatedTest(REPETITIONS)
  void countSingleType() throws Exception {
    assertEquals(TICKS, num(single("events/" + TICK + " | count()").get("count")));
  }

  @RepeatedTest(REPETITIONS)
  void countWithPredicate() throws Exception {
    assertEquals(
        TICKS / BUCKETS, num(single("events/" + TICK + "[bucket=3] | count()").get("count")));
  }

  @RepeatedTest(REPETITIONS)
  void countMultiType() throws Exception {
    assertEquals(
        TICKS + SAMPLES,
        num(single("events/(" + TICK + "|" + SAMPLE + ") | count()").get("count")));
  }

  @RepeatedTest(REPETITIONS)
  void countAllEventTypes() throws Exception {
    Map<String, Long> counts = eval.countAllEventTypes(session);
    assertEquals(TICKS, counts.get(TICK));
    assertEquals(SAMPLES, counts.get(SAMPLE));
    assertEquals(SPANS, counts.get(SPAN));
  }

  @RepeatedTest(REPETITIONS)
  void sumSingleType() throws Exception {
    Map<String, Object> row = single("events/" + TICK + " | sum(seq)");
    assertEquals(TICKS, num(row.get("count")));
    // Every partial sum is an integer below 2^53, so the double sum is exact in any order.
    assertEquals((double) (TICKS * (TICKS - 1) / 2), row.get("sum"));
  }

  @RepeatedTest(REPETITIONS)
  void sumMultiType() throws Exception {
    Map<String, Object> row = single("events/(" + TICK + "|" + SAMPLE + ") | sum(bucket)");
    assertEquals(TICKS + SAMPLES, num(row.get("count")));
    long perCycle = (long) BUCKETS * (BUCKETS - 1) / 2;
    assertEquals((double) ((TICKS + SAMPLES) / BUCKETS * perCycle), row.get("sum"));
  }

  @RepeatedTest(REPETITIONS)
  void stats() throws Exception {
    Map<String, Object> row = single("events/" + TICK + " | stats(bucket)");
    assertEquals(TICKS, num(row.get("count")));
    assertEquals(0.0, row.get("min"));
    assertEquals((double) (BUCKETS - 1), row.get("max"));
    // The running mean rounds at every step, so its last bits depend on the order of arrival.
    assertEquals((BUCKETS - 1) / 2.0, (Double) row.get("avg"), 1e-6);
  }

  @RepeatedTest(REPETITIONS)
  void quantiles() throws Exception {
    Map<String, Object> row = single("events/" + TICK + "/seq | quantiles(0.5)");
    assertEquals(TICKS, num(row.get("count")));
    assertEquals((TICKS - 1) / 2.0, row.get("p50"));
  }

  @RepeatedTest(REPETITIONS)
  void timeRange() throws Exception {
    assertEquals(TICKS, num(single("events/" + TICK + " | timerange()").get("count")));
  }

  @RepeatedTest(REPETITIONS)
  void groupByCount() throws Exception {
    List<Map<String, Object>> rows = run("events/" + TICK + " | groupBy(bucket, agg=count)");
    assertEquals(BUCKETS, rows.size());
    for (Map<String, Object> row : rows) {
      assertEquals(TICKS / BUCKETS, num(row.get("count")), "bucket " + row.get("key"));
    }
  }

  @RepeatedTest(REPETITIONS)
  void groupBySumMultiType() throws Exception {
    List<Map<String, Object>> rows =
        run("events/(" + TICK + "|" + SAMPLE + ") | groupBy(bucket, agg=sum, value=bucket)");
    assertEquals(BUCKETS, rows.size());
    for (Map<String, Object> row : rows) {
      long bucket = num(row.get("key"));
      assertEquals(
          (double) ((TICKS + SAMPLES) / BUCKETS * bucket), row.get("sum"), "bucket " + bucket);
    }
  }

  @RepeatedTest(REPETITIONS)
  void rows() throws Exception {
    assertEquals(SAMPLES, run("events/" + SAMPLE).size());
    assertEquals(SAMPLES + SPANS, run("events/(" + SAMPLE + "|" + SPAN + ")").size());
  }

  @RepeatedTest(REPETITIONS)
  void rowsWithLimit() throws Exception {
    JfrPath.Query query = JfrPathParser.parse("events/" + SAMPLE);
    assertEquals(SAMPLES, eval.evaluateWithLimit(session, query, Integer.MAX_VALUE).size());
    assertEquals(100, eval.evaluateWithLimit(session, query, 100).size());
  }

  @RepeatedTest(REPETITIONS)
  void projectedValues() throws Exception {
    JfrPath.Query query = JfrPathParser.parse("events/" + SAMPLE + "/seq");
    assertEquals(SAMPLES, eval.evaluateValues(session, query).size());
    assertEquals(SAMPLES, eval.evaluateValuesWithLimit(session, query, Integer.MAX_VALUE).size());
    assertEquals(100, eval.evaluateValuesWithLimit(session, query, 100).size());
  }

  @RepeatedTest(REPETITIONS)
  void top() throws Exception {
    List<Map<String, Object>> rows = run("events/" + SAMPLE + " | top(3, by=seq)");
    assertEquals(3, rows.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(SAMPLES - 1 - i, num(rows.get(i).get("seq")));
    }
  }

  @RepeatedTest(REPETITIONS)
  void rowTransforms() throws Exception {
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | len(label)").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | uppercase(label)").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | abs(negative)").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | formatDuration(seq)").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | asDateTime(startTime)").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | contains(label, \"a\")").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | replace(label, \"a\", \"b\")").size());
    assertEquals(SAMPLES, run("events/" + SAMPLE + " | select(seq)").size());
  }

  @RepeatedTest(REPETITIONS)
  void flameGraph() throws Exception {
    FlameNode root = (FlameNode) single("events/" + SAMPLE + " | flamegraph()").get("__flamegraph");
    assertEquals(SAMPLES, root.value);
  }

  @RepeatedTest(REPETITIONS)
  void stackProfile() throws Exception {
    JfrPath.Query query =
        JfrPathParser.parse("events/" + SAMPLE + " | stackprofile(direction=top-down)");
    assertEquals(SAMPLES, new Profiles(eval).scanStackProfileStats(session, query)[0]);

    // Top-down, every sample starts in the generator thread's Thread.run: the unindented rows
    // are the roots, and between them they must account for every sample.
    long roots = 0;
    for (Map<String, Object> row : eval.evaluate(session, query)) {
      if (!String.valueOf(row.get("method")).startsWith(" ")) {
        roots += num(((Map<?, ?>) row.get("profile")).get("total"));
      }
    }
    assertEquals(SAMPLES, roots);
  }

  @RepeatedTest(REPETITIONS)
  void decorateByKey() throws Exception {
    JfrPath.Query query =
        JfrPathParser.parse(
            "events/"
                + SAMPLE
                + " | decorateByKey("
                + SAMPLE
                + ", key=seq, decoratorKey=seq, fields=bucket)");
    JfrPath.DecorateByKeyOp op = (JfrPath.DecorateByKeyOp) query.pipeline.get(0);
    Map<Object, List<Map<String, Object>>> index =
        new Decorators(eval).collectKeyedDecorators(recording, op);
    assertEquals(SAMPLES, index.size());
    assertEquals(SAMPLES, index.values().stream().mapToLong(List::size).sum());

    assertEquals(SAMPLES, eval.evaluate(session, query).size());
  }

  @RepeatedTest(REPETITIONS)
  void decorateByTime() throws Exception {
    JfrPath.Query bySample =
        JfrPathParser.parse("events/" + SPAN + " | decorateByTime(" + SAMPLE + ", fields=seq)");
    JfrPath.DecorateByTimeOp op = (JfrPath.DecorateByTimeOp) bySample.pipeline.get(0);
    assertEquals(SAMPLES, new Decorators(eval).collectTimeRangeDecorators(recording, op).size());

    assertEquals(
        SAMPLES, run("events/" + SAMPLE + " | decorateByTime(" + SPAN + ", fields=seq)").size());
  }

  // --- The same result on every run, and rows in file order ---------------------------------
  //
  // Chunks are parsed in parallel, so which chunk delivers first changes from run to run. Rows
  // must come out in the order of the recording all the same, and anything whose value depends on
  // the order of its terms (a double sum, a running mean) must be put together in that order.

  /**
   * The {@code seq} of every {@value #SAMPLE} event in the order the events are in the file, read
   * by a parser that takes the chunks one after the other. It is not 0, 1, 2, ...: within a chunk
   * JFR does not write events in the order they were committed.
   */
  private static List<Long> sampleSeqInFileOrder;

  private static List<Long> readSampleSeqInFileOrder() throws Exception {
    List<Long> seq = java.util.Collections.synchronizedList(new ArrayList<>());
    try (io.jafar.parser.api.UntypedJafarParser parser =
        io.jafar.parser.api.ParsingContext.create()
            .withParallelism(1)
            .newUntypedParser(recording)) {
      parser.handle(
          (type, event, ctl) -> {
            if (SAMPLE.equals(type.getName())) {
              seq.add(num(event.get("seq")));
            }
          });
      parser.run();
    }
    return new ArrayList<>(seq);
  }

  /** 0, 1, 2, ... n-1. */
  private static List<Long> upTo(long n) {
    List<Long> expected = new ArrayList<>();
    for (long i = 0; i < n; i++) {
      expected.add(i);
    }
    return expected;
  }

  private static List<Long> seqOf(List<Map<String, Object>> rows, String column) {
    List<Long> seq = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      seq.add(num(row.get(column)));
    }
    return seq;
  }

  @RepeatedTest(REPETITIONS)
  void rowsAreInFileOrder() throws Exception {
    assertEquals(sampleSeqInFileOrder, seqOf(run("events/" + SAMPLE), "seq"));
    assertEquals(sampleSeqInFileOrder, seqOf(run("events/" + SAMPLE + " | select(seq)"), "seq"));
    // Two types: each one's rows keep their own order, whatever the interleaving in the file.
    List<Map<String, Object>> both = run("events/(" + SAMPLE + "|" + SPAN + ")");
    List<Map<String, Object>> samples = new ArrayList<>();
    for (Map<String, Object> row : both) {
      if (row.containsKey("label")) {
        samples.add(row);
      }
    }
    assertEquals(sampleSeqInFileOrder, seqOf(samples, "seq"));
  }

  @RepeatedTest(REPETITIONS)
  void projectedValuesAreInFileOrder() throws Exception {
    JfrPath.Query query = JfrPathParser.parse("events/" + SAMPLE + "/seq");
    List<Long> values = new ArrayList<>();
    for (Object v : eval.evaluateValues(session, query)) {
      values.add(num(v));
    }
    assertEquals(sampleSeqInFileOrder, values);
  }

  @RepeatedTest(REPETITIONS)
  void transformedRowsAreInFileOrder() throws Exception {
    // abs(negative) is seq again.
    assertEquals(
        sampleSeqInFileOrder, seqOf(run("events/" + SAMPLE + " | abs(negative)"), "value"));
  }

  @RepeatedTest(REPETITIONS)
  void limitedRowsAreTheFirstOfTheRecording() throws Exception {
    JfrPath.Query rows = JfrPathParser.parse("events/" + SAMPLE);
    assertEquals(
        sampleSeqInFileOrder.subList(0, 100),
        seqOf(eval.evaluateWithLimit(session, rows, 100), "seq"));

    JfrPath.Query values = JfrPathParser.parse("events/" + SAMPLE + "/seq");
    List<Long> first = new ArrayList<>();
    for (Object v : eval.evaluateValuesWithLimit(session, values, 100)) {
      first.add(num(v));
    }
    assertEquals(sampleSeqInFileOrder.subList(0, 100), first);
  }

  @RepeatedTest(REPETITIONS)
  void decoratedRowsAreInFileOrder() throws Exception {
    assertEquals(
        sampleSeqInFileOrder,
        seqOf(run("events/" + SAMPLE + " | decorateByTime(" + SPAN + ", fields=seq)"), "seq"));
  }

  @RepeatedTest(REPETITIONS)
  void groupsAreInOrderOfFirstAppearance() throws Exception {
    // A group appears where its first event is in the file; bucket is seq modulo BUCKETS.
    List<Long> firstAppearance = new ArrayList<>();
    for (Long seq : sampleSeqInFileOrder) {
      long bucket = seq % BUCKETS;
      if (!firstAppearance.contains(bucket)) {
        firstAppearance.add(bucket);
      }
    }
    assertEquals(upTo(BUCKETS), new ArrayList<>(new java.util.TreeSet<>(firstAppearance)));
    assertEquals(
        firstAppearance, seqOf(run("events/" + SAMPLE + " | groupBy(bucket, agg=count)"), "key"));
  }

  /** The first results of the order-dependent aggregations; every later run must equal them. */
  private static volatile List<Object> firstOrderDependentResults;

  @RepeatedTest(REPETITIONS)
  void orderDependentAggregationsAreTheSameOnEveryRun() throws Exception {
    List<Object> results = new ArrayList<>();
    // ratio is seq / 3.0: its partial sums are not exact, so their order shows in the last bits.
    Map<String, Object> stats = single("events/" + SAMPLE + " | stats(ratio)");
    results.add(stats.get("avg"));
    results.add(stats.get("stddev"));
    results.add(single("events/" + SAMPLE + " | sum(ratio)").get("sum"));
    for (Map<String, Object> row :
        run("events/" + SAMPLE + " | groupBy(bucket, agg=sum, value=ratio)")) {
      results.add(row.get("sum"));
    }
    for (Map<String, Object> row :
        run("events/" + SAMPLE + " | groupBy(bucket, agg=avg, value=ratio)")) {
      results.add(row.get("avg"));
    }

    // Not vacuous: the values are there and are not all one number.
    assertEquals(3 + 2 * BUCKETS, results.size());
    assertNotEquals(results.get(0), results.get(1));
    assertEquals((SAMPLES - 1) / 3.0 / 2, (Double) stats.get("avg"), 1e-6);

    synchronized (JfrPathConcurrentAggregationTest.class) {
      if (firstOrderDependentResults == null) {
        firstOrderDependentResults = results;
      }
    }
    assertEquals(firstOrderDependentResults, results);
  }
}
