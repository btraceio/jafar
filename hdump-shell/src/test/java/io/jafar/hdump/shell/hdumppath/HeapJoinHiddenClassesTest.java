package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.MinimalHprofBuilder;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.compare.FrameNames;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #261: a heap diff joined classes by name, and a lambda or hidden class's name carries a
 * per-run part ({@code Collectors$$Lambda+0x0000000019009048} on JDK 21 and later, {@code
 * Foo$$Lambda$1} on JDK 8), so the same class never matched across two dumps and every one was
 * reported as only in the new dump.
 */
class HeapJoinHiddenClassesTest {

  @TempDir Path dir;

  @Test
  void theDocumentedNewClassesQueryReportsOnlyTheClassTheFamilyGained() throws IOException {
    // Three Main$$Lambda classes here, two in the baseline: one is new, plus OnlyNew
    List<Map<String, Object>> rows =
        join("classes | join(session=1) | filter(baseline.exists = false)", jdk25Names());

    assertEquals(
        List.of("com.example.Main$$Lambda", "com.example.OnlyNew"),
        rows.stream()
            .map(r -> FrameNames.stableClassName((String) r.get("name")))
            .sorted()
            .collect(Collectors.toList()),
        rows.toString());
  }

  @Test
  void aRenamedClassPairsWithItsBaselineClass() throws IOException {
    List<Map<String, Object>> rows =
        join("objects | groupBy(class, agg=count) | join(session=1)", jdk25Names());

    long matched =
        rows.stream()
            .filter(r -> ((String) r.get("class")).contains("$$Lambda"))
            .filter(r -> Boolean.TRUE.equals(r.get("baseline.exists")))
            .count();
    assertEquals(2, matched, rows.toString());
    Map<String, Object> marker = row(rows, "class", "com.example.Marker");
    assertEquals(1L, ((Number) marker.get("baseline.count")).longValue(), marker.toString());
  }

  @Test
  void jdk8LambdaNumbersAreIgnoredToo() throws IOException {
    List<Map<String, Object>> rows =
        join("classes | join(session=1) | filter(baseline.exists = false)", jdk8Names());

    assertEquals(List.of(), rows, rows.toString());
  }

  @Test
  void jdk8LambdaFormClassesThatShareANamePairInOrder() throws IOException {
    // JDK 8 gives every LambdaForm$MH class the same name: three here, one in the baseline
    Names names =
        new Names(
            List.of(
                "java/lang/invoke/LambdaForm$MH",
                "java/lang/invoke/LambdaForm$MH",
                "java/lang/invoke/LambdaForm$MH"),
            List.of("java/lang/invoke/LambdaForm$MH"),
            false);

    List<Map<String, Object>> rows =
        join("classes | join(session=1) | filter(baseline.exists = false)", names);

    assertEquals(2, rows.size(), rows.toString());
  }

  @Test
  void objectsOfALambdaClassMatchLikeObjectsOfAnyClass() throws IOException {
    // Object rows are not classes: each Main$$Lambda object finds the baseline's first, as each
    // Marker object does
    List<Map<String, Object>> rows = join("objects | join(session=1)", jdk25Names());

    List<Map<String, Object>> lambdas =
        rows.stream()
            .filter(r -> ((String) r.get("className")).contains("$$Lambda"))
            .collect(Collectors.toList());
    assertEquals(3, lambdas.size(), rows.toString());
    lambdas.forEach(r -> assertEquals(true, r.get("baseline.exists"), rows.toString()));
  }

  @Test
  void classesJoinedByAFieldTheyShareAllMatchItsFirstBaselineRow() throws IOException {
    // superClass is java.lang.Object for every class but Object itself: not a family of classes
    List<Map<String, Object>> rows = join("classes | join(session=1, by=superClass)", jdk25Names());

    List<Object> unmatched =
        rows.stream()
            .filter(r -> r.get("superClass") != null)
            .filter(r -> !Boolean.TRUE.equals(r.get("baseline.exists")))
            .map(r -> r.get("name"))
            .collect(Collectors.toList());
    assertEquals(List.of(), unmatched, rows.toString());
  }

  @Test
  void duplicateOrdinaryKeysStillMatchTheFirstBaselineRow() throws IOException {
    // Three Marker objects here, one in the baseline: every one finds it, as before #261
    List<Map<String, Object>> rows =
        join(
            "objects | filter(className = \"com.example.Marker\") | join(session=1)", jdk25Names());

    assertEquals(3, rows.size(), rows.toString());
    rows.forEach(r -> assertEquals(true, r.get("baseline.exists"), rows.toString()));
  }

  /** Hidden class names of each dump; {@code onlyNew} adds a class only the current dump has. */
  private record Names(List<String> current, List<String> baseline, boolean onlyNew) {}

  private static Names jdk25Names() {
    return new Names(
        List.of(
            "com/example/Main$$Lambda+0x000000002a040210",
            "com/example/Main$$Lambda+0x000000002a040438",
            "com/example/Main$$Lambda+0x000000002a040c00"),
        List.of(
            "com/example/Main$$Lambda+0x0000000019009048",
            "com/example/Main$$Lambda+0x0000000019009270"),
        true);
  }

  private static Names jdk8Names() {
    return new Names(
        List.of("com/example/Main$$Lambda$1", "com/example/Main$$Lambda$2"),
        List.of("com/example/Main$$Lambda$3", "com/example/Main$$Lambda$4"),
        false);
  }

  private List<Map<String, Object>> join(String query, Names names) throws IOException {
    MinimalHprofBuilder current =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "com/example/Marker", 1)
            .addInstance(100, 2)
            .addInstance(101, 2)
            .addInstance(102, 2);
    long id = 10;
    long obj = 110;
    for (String name : names.current()) {
      current.addClass(id, name, 1).addInstance(obj++, id++);
    }
    if (names.onlyNew()) {
      current.addClass(3, "com/example/OnlyNew", 1).addInstance(103, 3);
    }
    MinimalHprofBuilder baseline =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "com/example/Marker", 1)
            .addInstance(200, 2);
    obj = 210;
    for (String name : names.baseline()) {
      baseline.addClass(id, name, 1).addInstance(obj++, id++);
    }
    Path cur = current.write(dir);
    Path base = baseline.write(dir);
    try (HeapSession c = HeapSession.open(cur);
        HeapSession b = HeapSession.open(base)) {
      SessionResolver resolver = ref -> Optional.of(new SessionManager.SessionRef<>(1, "base", b));
      return HdumpPathEvaluator.evaluate(c, HdumpPathParser.parse(query), resolver);
    }
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String key, Object value) {
    return rows.stream()
        .filter(r -> value.equals(r.get(key)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + key + "=" + value + " in " + rows));
  }
}
