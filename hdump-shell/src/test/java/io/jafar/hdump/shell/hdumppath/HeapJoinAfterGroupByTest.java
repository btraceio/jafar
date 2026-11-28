package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.MinimalHprofBuilder;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #260: a heap-to-heap {@code join(session=N)} after {@code groupBy} compared the grouped
 * rows with the baseline's ungrouped rows, on the root's default key (className), so no group found
 * its baseline: {@code baseline.exists=false}, {@code countDelta == count}. Both documented diff
 * examples computed nothing.
 */
class HeapJoinAfterGroupByTest {

  @TempDir Path dir;

  @Test
  void documentedObjectDiffMatchesEachGroupToItsBaselineGroup() throws IOException {
    List<Map<String, Object>> rows =
        join("objects | groupBy(class, agg=count) | join(session=1) | sortBy(countDelta desc)");

    Map<String, Object> foo = row(rows, "class", "com.example.Foo");
    assertEquals(true, foo.get("baseline.exists"), foo.toString());
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), foo.toString());
    assertEquals(2L, ((Number) foo.get("countDelta")).longValue(), foo.toString());
    assertEquals("com.example.Foo", rows.get(0).get("class"), "sorted by countDelta: " + rows);
  }

  @Test
  void documentedGcRootDiffComparesRootCounts() throws IOException {
    List<Map<String, Object>> rows = join("gcroots | groupBy(type, agg=count) | join(session=1)");

    Map<String, Object> frames = row(rows, "type", "UNKNOWN"); // addGcRoot writes ROOT_UNKNOWN
    assertEquals(1L, ((Number) frames.get("baseline.count")).longValue(), frames.toString());
    assertEquals(2L, ((Number) frames.get("countDelta")).longValue(), frames.toString());
  }

  @Test
  void stepsAfterTheGroupByDoNotHideBaselineGroups() throws IOException {
    // top(1) on the baseline would keep Bar (2 instances) and drop Foo (1)
    List<Map<String, Object>> rows =
        join("objects | groupBy(class, agg=count) | top(1, by=count) | join(session=1)");

    Map<String, Object> foo = row(rows, "class", "com.example.Foo");
    assertEquals(true, foo.get("baseline.exists"), rows.toString());
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), rows.toString());
  }

  @Test
  void aGroupOnlyInTheNewDumpHasNoBaseline() throws IOException {
    List<Map<String, Object>> rows =
        join(
            "objects | groupBy(class, agg=count) | join(session=1)"
                + " | filter(baseline.exists = false)");

    assertEquals(1, rows.size(), rows.toString());
    assertEquals("com.example.OnlyNew", rows.get(0).get("class"));
  }

  @Test
  void aJoinAfterCountHasNoKeyAndSaysSo() throws IOException {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> join("objects | count | join(session=1)"));
    assertTrue(e.getMessage().contains("groupBy"), e.getMessage());
  }

  @Test
  void aNullGroupMatchesItsBaselineGroup() throws IOException {
    // arrayLength is null for every instance: one null group in each dump
    Map<String, Object> nulls =
        join("objects | groupBy(arrayLength, agg=count) | join(session=1)").get(0);

    assertEquals(true, nulls.get("baseline.exists"), nulls.toString());
    assertEquals(3L, ((Number) nulls.get("baseline.count")).longValue(), nulls.toString());
    assertEquals(1L, ((Number) nulls.get("countDelta")).longValue(), nulls.toString());
  }

  @Test
  void aCompositeKeyWithANullFieldMatches() throws IOException {
    List<Map<String, Object>> rows =
        join("objects | groupBy(class, arrayLength, agg=count) | join(session=1)");

    Map<String, Object> foo = row(rows, "class", "com.example.Foo");
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), rows.toString());
  }

  @Test
  void everyGroupFieldIsPartOfTheKey() throws IOException {
    // arrayLength is null for every row, so only the second field tells the groups apart
    List<Map<String, Object>> rows =
        join("objects | groupBy(arrayLength, class, agg=count) | join(session=1)");

    assertEquals(
        false, row(rows, "class", "com.example.OnlyNew").get("baseline.exists"), rows.toString());
    Map<String, Object> foo = row(rows, "class", "com.example.Foo");
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), rows.toString());
  }

  @Test
  void byMustNameAGroupFieldAfterGroupBy() throws IOException {
    Map<String, Object> foo =
        row(
            join("objects | groupBy(class, agg=count) | join(session=1, by=class)"),
            "class",
            "com.example.Foo");
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), foo.toString());

    // An alias names the same field: className is what groupBy(class) groups on. The grouped
    // rows carry a class column only, so the key must be read from it.
    List<Map<String, Object>> aliased =
        join("objects | groupBy(class, agg=count) | join(session=1, by=className)");
    Map<String, Object> aliasedFoo = row(aliased, "class", "com.example.Foo");
    assertEquals(1L, ((Number) aliasedFoo.get("baseline.count")).longValue(), aliased.toString());
    assertEquals(
        false,
        row(aliased, "class", "com.example.OnlyNew").get("baseline.exists"),
        aliased.toString());

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> join("objects | groupBy(class, agg=count) | join(session=1, by=count)"));
    assertTrue(e.getMessage().contains("group field: class"), e.getMessage());
  }

  @Test
  void aJoinAfterSumOrStatsHasNoKeyEither() throws IOException {
    for (String agg : List.of("sum(shallow)", "stats(shallow)")) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> join("objects | " + agg + " | join(session=1)"),
              agg);
      assertTrue(e.getMessage().contains("groupBy"), e.getMessage());
    }
  }

  @Test
  void aJoinBeforeTheGroupByIsRejected() throws IOException {
    // The baseline would have to run the first join before grouping
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                join(
                    "objects | groupBy(class, agg=count) | join(session=1)"
                        + " | groupBy(class, agg=count) | join(session=1)"));
    assertTrue(e.getMessage().contains("cannot repeat the earlier join"), e.getMessage());
  }

  @Test
  void aJoinAfterTheGroupByDoesNotStopASecondJoin() throws IOException {
    // The second join's baseline is the prefix up to the groupBy; the first join is not repeated
    Map<String, Object> foo =
        row(
            join("objects | groupBy(class, agg=count) | join(session=1) | join(session=1)"),
            "class",
            "com.example.Foo");
    assertEquals(true, foo.get("baseline.exists"), foo.toString());
    assertEquals(1L, ((Number) foo.get("baseline.count")).longValue(), foo.toString());
  }

  @Test
  void aJoinWithoutGroupByDoesNotStopASecondJoin() throws IOException {
    List<Map<String, Object>> rows = join("classes | join(session=1) | join(session=1)");

    Map<String, Object> foo = row(rows, "name", "com.example.Foo");
    assertEquals(true, foo.get("baseline.exists"), foo.toString());
  }

  @Test
  void sortingBeforeAHeadChangesTheBaselineGroups() throws IOException {
    // Sorted by class, head(1) keeps Foo here and Bar in the baseline: Foo has no baseline group.
    // Without the sortBy the baseline's head(1) is its first object, a Foo.
    Map<String, Object> foo =
        row(
            join("objects | sortBy(class) | head(1) | groupBy(class, agg=count) | join(session=1)"),
            "class",
            "com.example.Foo");
    assertEquals(false, foo.get("baseline.exists"), foo.toString());
  }

  private List<Map<String, Object>> join(String query) throws IOException {
    Path current =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "com/example/Foo", 1)
            .addClass(3, "com/example/OnlyNew", 1)
            .addInstance(100, 2)
            .addInstance(101, 2)
            .addInstance(102, 2)
            .addInstance(103, 3)
            .addGcRoot(100)
            .addGcRoot(101)
            .addGcRoot(102)
            .write(dir);
    Path baseline =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "com/example/Foo", 1)
            .addClass(4, "com/example/Bar", 1)
            .addInstance(200, 2)
            .addInstance(201, 4)
            .addInstance(202, 4)
            .addGcRoot(200)
            .write(dir);
    try (HeapSession cur = HeapSession.open(current);
        HeapSession base = HeapSession.open(baseline)) {
      SessionResolver resolver =
          ref -> Optional.of(new SessionManager.SessionRef<>(1, "base", base));
      return HdumpPathEvaluator.evaluate(cur, HdumpPathParser.parse(query), resolver);
    }
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String key, Object value) {
    return rows.stream()
        .filter(r -> value.equals(r.get(key)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + key + "=" + value + " in " + rows));
  }
}
