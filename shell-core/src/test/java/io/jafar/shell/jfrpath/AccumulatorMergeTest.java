package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * stats() and timerange() accumulate per chunk thread and merge after the parse. A merge of parts
 * must give what one accumulator fed every value would.
 */
class AccumulatorMergeTest {

  @Test
  void mergedStatsEqualOneAccumulator() {
    Random rnd = new Random(7);
    for (int split : new int[] {1, 2, 3, 7}) {
      Aggregations.StatsAgg one = new Aggregations.StatsAgg();
      List<Aggregations.StatsAgg> parts = new ArrayList<>();
      for (int p = 0; p < split; p++) parts.add(new Aggregations.StatsAgg());
      for (int i = 0; i < 10_000; i++) {
        double x = rnd.nextGaussian() * 1e6 + 5e9; // large offset: tests the stable update
        one.add(x);
        parts.get(rnd.nextInt(split)).add(x);
      }
      Map<String, Object> expected = one.toRow();
      Map<String, Object> merged = Aggregations.StatsAgg.merged(parts).toRow();
      assertEquals(expected.get("count"), merged.get("count"), "split " + split);
      assertEquals(expected.get("min"), merged.get("min"), "split " + split);
      assertEquals(expected.get("max"), merged.get("max"), "split " + split);
      assertEquals(
          (double) expected.get("avg"), (double) merged.get("avg"), 1e-3, "split " + split);
      assertEquals(
          (double) expected.get("stddev"), (double) merged.get("stddev"), 1e-3, "split " + split);
    }
  }

  @Test
  void emptyPartsChangeNothing() {
    Aggregations.StatsAgg a = new Aggregations.StatsAgg();
    a.add(3);
    a.add(5);
    Aggregations.StatsAgg merged =
        Aggregations.StatsAgg.merged(
            List.of(new Aggregations.StatsAgg(), a, new Aggregations.StatsAgg()));
    assertEquals(a.toRow(), merged.toRow());
    assertNull(Aggregations.StatsAgg.merged(List.of()).toRow().get("avg"));
  }

  @Test
  void mergedTimeRangesEqualOneRange() {
    Aggregations.TimeRangeAgg a = new Aggregations.TimeRangeAgg();
    Aggregations.TimeRangeAgg b = new Aggregations.TimeRangeAgg();
    Aggregations.TimeRangeAgg one = new Aggregations.TimeRangeAgg();
    long[][] events = {{100, 5}, {40, 1}, {300, 50}, {200, 0}};
    for (int i = 0; i < events.length; i++) {
      Map<String, Object> ev = Map.of("startTime", events[i][0], "duration", events[i][1]);
      (i % 2 == 0 ? a : b).offer(ev, List.of("startTime"), List.of("duration"));
      one.offer(ev, List.of("startTime"), List.of("duration"));
    }
    Aggregations.TimeRangeAgg merged = new Aggregations.TimeRangeAgg();
    merged.merge(a);
    merged.merge(new Aggregations.TimeRangeAgg());
    merged.merge(b);
    long[] mm1 = new long[2], c1 = new long[1], mm2 = new long[2], c2 = new long[1];
    one.snapshot(mm1, c1);
    merged.snapshot(mm2, c2);
    assertEquals(40, mm2[0]);
    assertEquals(350, mm2[1]);
    assertEquals(c1[0], c2[0]);
    assertEquals(mm1[0], mm2[0]);
    assertEquals(mm1[1], mm2[1]);
  }
}
