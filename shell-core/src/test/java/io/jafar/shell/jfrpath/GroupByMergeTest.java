package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Streaming groupBy aggregates per chunk thread and merges at the end. The merge must give what one
 * map would have, and must keep the distinct-group cap when no single thread reached it.
 */
class GroupByMergeTest {

  @AfterEach
  void clearCap() {
    System.clearProperty("jafar.groupby.maxGroups");
  }

  private static Map<Object, Aggregations.GroupAccumulator> partial(
      String func, Object[] keys, double[] values) {
    Map<Object, Aggregations.GroupAccumulator> m = new LinkedHashMap<>();
    for (int i = 0; i < keys.length; i++) {
      m.computeIfAbsent(keys[i], k -> new Aggregations.GroupAccumulator(func)).add(values[i]);
    }
    return m;
  }

  @Test
  void mergedAccumulatorsEqualOneAccumulator() {
    for (String func : List.of("count", "sum", "avg", "min", "max")) {
      var a = partial(func, new Object[] {"x", "y", "x"}, new double[] {1, 10, 3});
      var b = partial(func, new Object[] {"y", "x", "z"}, new double[] {-4, 7, 2});
      var merged = Aggregations.mergeGroups(List.of(a, b), "k");
      var one =
          partial(
              func, new Object[] {"x", "y", "x", "y", "x", "z"}, new double[] {1, 10, 3, -4, 7, 2});
      assertEquals(one.keySet(), merged.keySet(), func);
      for (Object key : one.keySet()) {
        assertEquals(one.get(key).getResult(), merged.get(key).getResult(), func + " " + key);
      }
    }
  }

  @Test
  void theCapHoldsAcrossThreads() {
    System.setProperty("jafar.groupby.maxGroups", "3");
    // each thread stayed under the cap; together they hold four distinct groups
    var a = partial("count", new Object[] {"a", "b"}, new double[] {1, 1});
    var b = partial("count", new Object[] {"c", "d"}, new double[] {1, 1});
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> Aggregations.mergeGroups(List.of(a, b), "k"));
    assertTrue(e.getMessage().contains("cap of 3"), e.getMessage());

    // exactly the cap across threads is fine, and shared keys do not count twice
    var c = partial("count", new Object[] {"a", "b"}, new double[] {1, 1});
    var d = partial("count", new Object[] {"b", "c"}, new double[] {1, 1});
    assertEquals(3, Aggregations.mergeGroups(List.of(c, d), "k").size());
  }

  @Test
  void noThreadMeansNoGroups() {
    assertTrue(Aggregations.mergeGroups(List.of(), "k").isEmpty());
  }
}
