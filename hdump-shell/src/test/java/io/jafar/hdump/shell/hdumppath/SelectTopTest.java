package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.hdump.shell.hdumppath.HdumpPath.TopOp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The bounded top-N selection used by the streaming path (heaps over 5M objects), tested directly
 * because a unit test cannot afford a heap that large.
 */
class SelectTopTest {

  private static Stream<Map<String, Object>> rows(Long... values) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Long v : values) {
      Map<String, Object> m = new HashMap<>();
      m.put("v", v);
      out.add(m);
    }
    return out.stream();
  }

  private static List<Object> values(List<Map<String, Object>> rows) {
    return rows.stream().map(m -> m.get("v")).toList();
  }

  @Test
  void descendingReturnsTheLargestFirst() {
    // Was [1, 2, 3]: the heap evicted the largest row and the result was sorted ascending
    assertEquals(
        List.of(9L, 7L, 5L),
        values(
            HdumpPathEvaluator.selectTop(rows(1L, 9L, 3L, 7L, 2L, 5L), new TopOp(3, "v", true))));
  }

  @Test
  void ascendingReturnsTheSmallestFirst() {
    assertEquals(
        List.of(1L, 2L, 3L),
        values(
            HdumpPathEvaluator.selectTop(rows(1L, 9L, 3L, 7L, 2L, 5L), new TopOp(3, "v", false))));
  }

  @Test
  void rowsWithoutAValueAreWorstInBothDirections() {
    List<Object> desc =
        values(HdumpPathEvaluator.selectTop(rows(null, 4L, null, 8L), new TopOp(3, "v", true)));
    assertEquals(8L, desc.get(0));
    assertEquals(4L, desc.get(1));
    assertEquals(null, desc.get(2));
    List<Object> asc =
        values(HdumpPathEvaluator.selectTop(rows(null, 4L, null, 8L), new TopOp(2, "v", false)));
    assertEquals(List.of(4L, 8L), asc);
  }

  @Test
  void withoutAnOrderFieldItIsTheFirstN() {
    assertEquals(
        List.of(5L, 4L),
        values(HdumpPathEvaluator.selectTop(rows(5L, 4L, 3L), new TopOp(2, null))));
  }
}
