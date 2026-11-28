package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A tail filter with a row-set operator aggregates (#185): the result covers every matching event
 * since the tail started, printed every {@code --every} while events come in and once at the end,
 * whatever ends the tail.
 */
class TailAggregationTest {

  @jdk.jfr.Name("test.AggProbe")
  static class AggProbe extends jdk.jfr.Event {
    int seq;
    String group;
  }

  /** 200 events: seq 0..199, group "g" + seq % 3 (67, 67 and 66 of them). */
  static List<RecordedEvent> events;

  @BeforeAll
  static void record() throws Exception {
    Path file = Files.createTempFile("tail-aggregation", ".jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable(AggProbe.class);
      r.setToDisk(true);
      r.setDestination(file);
      r.start();
      for (int i = 0; i < 200; i++) {
        AggProbe e = new AggProbe();
        e.seq = i;
        e.group = "g" + (i % 3);
        e.commit();
      }
      r.stop();
    }
    events =
        RecordingFile.readAllEvents(file).stream()
            .filter(e -> e.getEventType().getName().equals("test.AggProbe"))
            .toList();
    Files.delete(file);
    assertEquals(200, events.size());
  }

  /** Hands out the events one per poll, waiting {@code delayMs} first; closed when done. */
  static final class Replay implements LiveStreamView {
    private final Deque<RecordedEvent> queue;
    private final long delayMs;

    Replay(List<RecordedEvent> events, long delayMs) {
      this.queue = new ArrayDeque<>(events);
      this.delayMs = delayMs;
    }

    @Override
    public RecordedEvent pollEvent(long timeoutMillis) throws InterruptedException {
      if (delayMs > 0) {
        Thread.sleep(delayMs);
      }
      return queue.pollFirst();
    }

    @Override
    public String getStreamState() {
      return queue.isEmpty() ? "closed" : "running";
    }

    @Override
    public long getDroppedCount() {
      return 0;
    }

    @Override
    public String getType() {
      return "jfr-live";
    }

    @Override
    public Path getFilePath() {
      return Path.of("replay");
    }

    @Override
    public boolean isClosed() {
      return queue.isEmpty();
    }

    @Override
    public Set<String> getAvailableTypes() {
      return Set.of("test.AggProbe");
    }

    @Override
    public Map<String, Object> getStatistics() {
      return Map.of();
    }

    @Override
    public void close() {}
  }

  static final class Lines implements TailRunner.Sink {
    final List<String> events = new ArrayList<>();
    final List<String> statuses = new ArrayList<>();
    String end;

    @Override
    public void event(String line) {
      events.add(line);
    }

    @Override
    public void status(String line) {
      statuses.add(line);
    }

    @Override
    public void end(String line) {
      end = line;
    }
  }

  private static Lines run(String filter, long limit, Duration every, long maxRows, long delayMs) {
    Lines out = new Lines();
    TailRunner.run(
        new Replay(events, delayMs),
        TailRunner.parseFilter(filter),
        TailRunner.Format.JSON,
        limit,
        every,
        maxRows,
        out);
    return out;
  }

  private static List<JsonObject> aggregates(Lines out) {
    List<JsonObject> list = new ArrayList<>();
    for (String line : out.events) {
      JsonObject o = JsonParser.parseString(line).getAsJsonObject();
      assertEquals("aggregate", o.get("type").getAsString(), line);
      list.add(o);
    }
    return list;
  }

  private static JsonObject last(Lines out) {
    List<JsonObject> all = aggregates(out);
    JsonObject last = all.get(all.size() - 1);
    assertTrue(last.get("final").getAsBoolean(), last.toString());
    return last;
  }

  @Test
  void countCoversEveryEvent() {
    Lines out = run("events/test.AggProbe | count()", -1, Duration.ofHours(1), 0, 0);
    JsonObject last = last(out);
    assertEquals(200, last.get("events").getAsInt());
    assertEquals(
        200, last.getAsJsonArray("result").get(0).getAsJsonObject().get("count").getAsInt());
    assertTrue(
        out.end.startsWith("tail finished (target stream closed): 200 events aggregated"), out.end);
  }

  @Test
  void groupByGivesEachGroupsCount() {
    JsonArray rows =
        last(run("events/test.AggProbe | groupBy(group)", -1, Duration.ofHours(1), 0, 0))
            .getAsJsonArray("result");
    Map<String, Integer> counts = new java.util.TreeMap<>();
    rows.forEach(
        r ->
            counts.put(
                r.getAsJsonObject().get("key").getAsString(),
                r.getAsJsonObject().get("count").getAsInt()));
    assertEquals(Map.of("g0", 67, "g1", 67, "g2", 66), counts);
  }

  @Test
  void perEventOperatorsRunFirst() {
    JsonObject last =
        last(
            run(
                "events/test.AggProbe[seq < 100] | filter(group == \"g0\") | count()",
                -1,
                Duration.ofHours(1),
                0,
                0));
    // seq 0, 3, ..., 99: 34 events
    assertEquals(34, last.get("events").getAsInt());
    assertEquals(
        34, last.getAsJsonArray("result").get(0).getAsJsonObject().get("count").getAsInt());
  }

  @Test
  void limitCountsTheEventsTakenIn() {
    Lines out = run("events/test.AggProbe | count()", 30, Duration.ofHours(1), 0, 0);
    assertEquals(30, last(out).get("events").getAsInt());
    assertTrue(out.end.startsWith("tail finished (limit reached): 30 events aggregated"), out.end);
  }

  @Test
  void theRowCapEndsTheTailWithAResultOverWhatItKept() {
    Lines out = run("events/test.AggProbe | count()", -1, Duration.ofHours(1), 20, 0);
    assertEquals(20, last(out).get("events").getAsInt());
    assertTrue(out.end.contains("row cap reached: 20 rows kept (--max-rows)"), out.end);
  }

  @Test
  void theResultIsPrintedWhileEventsComeIn() {
    Lines out = run("events/test.AggProbe | count()", -1, Duration.ofMillis(20), 0, 2);
    List<JsonObject> all = aggregates(out);
    assertTrue(all.size() >= 3, "expected several results, got " + all.size());
    int previous = 0;
    for (int i = 0; i < all.size(); i++) {
      JsonObject o = all.get(i);
      assertEquals(i == all.size() - 1, o.get("final").getAsBoolean(), o.toString());
      int n = o.get("events").getAsInt();
      // A result is printed only after new events came in: strictly more each time, but the last
      assertTrue(i == all.size() - 1 ? n >= previous : n > previous, all.toString());
      assertEquals(n, o.getAsJsonArray("result").get(0).getAsJsonObject().get("count").getAsInt());
      previous = n;
    }
    assertEquals(200, previous);
  }

  @Test
  void aFilterThatDoesNotAggregateStillStreamsEvents() {
    Lines out = new Lines();
    TailRunner.run(
        new Replay(events, 0),
        TailRunner.parseFilter("events/test.AggProbe | filter(seq < 3)"),
        TailRunner.Format.JSON,
        -1,
        out);
    assertEquals(3, out.events.size());
    assertFalse(out.events.get(0).contains("\"aggregate\""), out.events.get(0));
    assertTrue(out.end.contains("3 events shown"), out.end);
  }

  @Test
  void tableFormatHasAHeaderAndTheTable() {
    Lines out = new Lines();
    TailRunner.run(
        new Replay(events, 0),
        TailRunner.parseFilter("events/test.AggProbe | count()"),
        TailRunner.Format.TABLE,
        -1,
        Duration.ofHours(1),
        0,
        out);
    assertTrue(out.events.get(0).startsWith("final result at "), out.events.toString());
    assertTrue(
        out.events.get(0).endsWith(" over 200 events (0 dropped by the live session):"),
        out.events.get(0));
    assertTrue(out.events.stream().anyMatch(l -> l.contains("200")), out.events.toString());
  }

  @Test
  void aProjectionAggregatesItsValues() {
    // events/T/seq: the rows are the seq values, as a file query makes them
    JsonObject last =
        last(run("events/test.AggProbe/seq | stats()", -1, Duration.ofHours(1), 0, 0));
    JsonObject stats = last.getAsJsonArray("result").get(0).getAsJsonObject();
    assertEquals(200, stats.get("count").getAsInt(), stats.toString());
    assertEquals(0, stats.get("min").getAsInt(), stats.toString());
    assertEquals(199, stats.get("max").getAsInt(), stats.toString());
    assertEquals(200, last.get("events").getAsInt());
    assertEquals(200, last.get("rows").getAsInt());
  }

  @Test
  void aFailingAggregationIsReportedOnce() {
    Lines out = run("events/test.AggProbe | sortby(nosuchfield)", -1, Duration.ofMillis(10), 0, 1);
    long errors = out.statuses.stream().filter(l -> l.startsWith("error: ")).count();
    assertEquals(1, errors, out.statuses.toString());
    assertTrue(out.end.startsWith("tail finished (aggregation failed"), out.end);
  }

  @Test
  void theLimitWinsOverTheRowCapWhenBothAreReached() {
    Lines out = run("events/test.AggProbe | count()", 20, Duration.ofHours(1), 20, 0);
    assertTrue(out.end.startsWith("tail finished (limit reached)"), out.end);
  }

  @Test
  void aNearlyFullHeapEndsTheTailWithAResult() {
    java.util.function.BooleanSupplier saved = TailRunner.heapNearlyFull;
    int savedEvery = TailRunner.heapCheckEvery;
    TailRunner.heapNearlyFull = () -> true;
    TailRunner.heapCheckEvery = 50;
    try {
      Lines out = run("events/test.AggProbe | count()", -1, Duration.ofHours(1), 0, 0);
      assertEquals(50, last(out).get("events").getAsInt());
      assertTrue(out.end.contains("heap nearly full: 50 rows kept"), out.end);
    } finally {
      TailRunner.heapNearlyFull = saved;
      TailRunner.heapCheckEvery = savedEvery;
    }
  }

  @Test
  void theTuiShowsTheLatestResultWhole() {
    List<String> printed = new ArrayList<>();
    io.jafar.shell.cli.CommandDispatcher.IO io =
        new io.jafar.shell.cli.CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            printed.add(s);
          }

          @Override
          public void printf(String fmt, Object... args) {
            printed.add(String.format(java.util.Locale.ROOT, fmt, args));
          }

          @Override
          public void error(String s) {}
        };
    // A ring of 4 lines, as a --limit 4 tail gets: the result's header and table are longer
    TailRunner.run(
        new Replay(events, 1),
        TailRunner.parseFilter("events/test.AggProbe | groupBy(group)"),
        TailRunner.Format.TABLE,
        4,
        Duration.ofMillis(1),
        0,
        new TailRunner.TuiSink(io, 4));
    assertTrue(printed.get(0).startsWith("final result at "), printed.toString());
    assertTrue(printed.stream().anyMatch(l -> l.contains("key")), printed.toString());
    assertEquals(
        1, printed.stream().filter(l -> l.contains("result at")).count(), printed.toString());
    assertTrue(
        printed.get(printed.size() - 1).startsWith("tail finished (limit reached)"),
        printed.toString());
  }
}
