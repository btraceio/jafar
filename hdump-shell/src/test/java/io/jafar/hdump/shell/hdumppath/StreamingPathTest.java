package io.jafar.hdump.shell.hdumppath;

import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_INT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_OBJECT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.fieldData;
import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.MinimalHprofBuilder;
import io.jafar.hdump.shell.MinimalHprofBuilder.Field;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming path {@code objects} queries take on heaps over 5M objects, driven on a small heap
 * by lowering {@link HdumpPathEvaluator#streamingThreshold}.
 *
 * <p>Heap: {@code Object <- AbstractMap <- HashMap(table, size) <- com.example.MyMap}, a field-less
 * {@code java.util.Map}, one HashMap (capacity 16, size 3), one MyMap (capacity 64, size 1), and
 * 150 {@code com.example.Plain} objects after them, by id and in the file, so the stream holds more
 * than the 100 objects the non-aggregating cap keeps. (The old cap, a heap with a constant
 * comparator whose {@code poll()} removes the head, kept the 2nd to 100th object and the last one.)
 */
class StreamingPathTest {

  @TempDir static Path tempDir;

  private static HeapSession session;
  private long savedThreshold;

  @BeforeAll
  static void open() throws IOException {
    MinimalHprofBuilder b =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "java/util/AbstractMap", 1)
            .addClass(
                3,
                "java/util/HashMap",
                2,
                new Field("table", TYPE_OBJECT),
                new Field("size", TYPE_INT))
            .addClass(4, "com/example/MyMap", 3)
            .addClass(5, "java/util/Map", 1)
            .addClass(6, "[Ljava/util/HashMap$Node;", 1)
            .addClass(7, "com/example/Plain", 1)
            // A real dump has a CLASS_DUMP for [B, so INDEXED mode gives it a class-instances
            // index entry that lists only the class object itself
            .addClass(8, "[B", 1)
            .addPrimitiveArray(104, 8, 10)
            .addPrimitiveArray(105, 8, 20)
            .addInstance(100, 3, fieldData().ref(102).i32(3).bytes())
            .addInstance(101, 4, fieldData().ref(103).i32(1).bytes())
            .addObjectArray(102, 6, new long[16])
            .addObjectArray(103, 6, new long[64])
            .addGcRoot(100);
    for (long id = 300; id < 450; id++) {
      b.addInstance(id, 7);
    }
    Path hprof = b.write(tempDir);
    session = HeapSession.open(hprof);
  }

  @AfterAll
  static void close() throws Exception {
    if (session != null) {
      session.close();
    }
  }

  @BeforeEach
  void streamEverything() {
    savedThreshold = HdumpPathEvaluator.streamingThreshold;
    HdumpPathEvaluator.streamingThreshold = 0;
  }

  @AfterEach
  void restore() {
    HdumpPathEvaluator.streamingThreshold = savedThreshold;
  }

  private static List<Map<String, Object>> run(String q) {
    return HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse(q));
  }

  @Test
  void topReturnsTheLargestValuesFirst() {
    // Was the smallest values, ascending: the bounded heap evicted the best row
    List<Map<String, Object>> top = run("objects | top(2, arrayLength)");
    assertEquals(64, top.get(0).get("arrayLength"));
    assertEquals(20, top.get(1).get("arrayLength"));
  }

  @Test
  void wasteThenTopStreamsEveryObject() {
    // Was waste() over the 100 objects the cap kept, which did not include the HashMap
    List<Map<String, Object>> top = run("objects | waste() | top(2, wastedBytes)");
    assertEquals("com.example.MyMap", top.get(0).get("className"));
    assertEquals("java.util.HashMap", top.get(1).get("className"));
  }

  @Test
  void reportStyleWasteQueryFiltersWhileStreaming() {
    // An instanceof type filter streams only maps, so this guards the row-op chain with filter()
    List<Map<String, Object>> rows =
        run(
            "objects/instanceof/java.util.HashMap | waste() | filter(wastedBytes > 200)"
                + " | top(10, wastedBytes)");
    assertEquals(1, rows.size());
    assertEquals("com.example.MyMap", rows.get(0).get("className"));
  }

  @Test
  void instanceofInterfaceFailsOnTheStreamingPathToo() {
    assertThrows(
        IllegalArgumentException.class, () -> run("objects/instanceof/java.util.Map | count"));
  }

  @Test
  void wasteThenCountAndGroupByStreamEveryObject() {
    // Were capped at 100 objects: waste() | count said 100
    assertEquals(156L, ((Number) run("objects | waste() | count").get(0).get("count")).longValue());
    List<Map<String, Object>> groups =
        run(
            "objects/instanceof/java.util.HashMap | waste() | groupBy(class, agg=sum,"
                + " value=wastedBytes)");
    assertEquals(2, groups.size());
  }

  @Test
  void joinAfterAStreamedHeadReachesTheResolver() {
    // The streamed paths ran later ops without the resolver: "join() is not supported in this
    // context". With one, an unknown session is reported as such.
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HdumpPathEvaluator.evaluate(
                    session,
                    HdumpPathParser.parse(
                        "objects | waste() | top(2, wastedBytes) | join(session=9)"),
                    ref -> Optional.empty()));
    assertTrue(e.getMessage().contains("Cannot resolve session: 9"), e.getMessage());
  }

  @Test
  void streamedTopOnAMissingFieldFailsLikeTheInMemoryPath() {
    // Was 3 arbitrary rows on the streaming path, an error on the in-memory one
    assertThrows(IllegalArgumentException.class, () -> run("objects | top(3, nosuchfield)"));
  }

  @Test
  void uppercaseOfAnAliasRewritesTheCanonicalColumn() {
    Map<String, Object> row =
        run("objects/com.example.MyMap | waste() | top(1, wastedBytes) | uppercase(class)").get(0);
    // Was className unchanged plus a second column class=COM.EXAMPLE.MYMAP
    assertEquals("COM.EXAMPLE.MYMAP", row.get("className"));
    // (keySet().contains would resolve the alias; check the stored keys)
    assertFalse(List.copyOf(row.keySet()).contains("class"), row.keySet().toString());
  }

  @Test
  void indexedStreamingCountsNoClassObjects(@TempDir Path dir) throws IOException {
    // The class-instances index files each class's own class object under the class; streaming
    // through it counted that as an instance (MyMap: 2 instead of 1)
    Path copy = dir.resolve("copy.hprof");
    java.nio.file.Files.copy(session.getFilePath(), copy);
    try (HeapSession indexed = HeapSession.open(copy, ParserOptions.INDEXED)) {
      List<Map<String, Object>> rows =
          HdumpPathEvaluator.evaluate(
              indexed, HdumpPathParser.parse("objects/com.example.MyMap | count"));
      assertEquals(1L, ((Number) rows.get(0).get("count")).longValue());
      rows =
          HdumpPathEvaluator.evaluate(
              indexed, HdumpPathParser.parse("objects/instanceof/java.util.HashMap | count"));
      assertEquals(2L, ((Number) rows.get(0).get("count")).longValue());
    }
  }

  /** Numbers compared as doubles: the paths may box a count as Integer or Long. */
  private static List<Map<String, Object>> normalized(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            r -> {
              Map<String, Object> m = new java.util.TreeMap<>();
              r.forEach((k, v) -> m.put(k, v instanceof Number n ? n.doubleValue() : v));
              return m;
            })
        .toList();
  }

  private static List<Map<String, Object>> inMemory(String q) {
    long t = HdumpPathEvaluator.streamingThreshold;
    HdumpPathEvaluator.streamingThreshold = Long.MAX_VALUE;
    try {
      return run(q);
    } finally {
      HdumpPathEvaluator.streamingThreshold = t;
    }
  }

  @Test
  void streamedGroupByAgreesWithTheInMemoryPath() {
    for (String q :
        List.of(
            "objects | groupBy(class) | top(3, class)",
            "objects | groupBy(class) | top(2, count)",
            "objects | groupBy(class, agg=sum, value=shallow) | sortBy(class)",
            "objects | groupBy(class) | sortBy(class)",
            // the cases two groupBy implementations disagreed on (4th review of #225)
            "objects | groupBy(class, agg=sum, value=arrayLength)",
            "objects | groupBy(class, agg=avg, value=nosuch)",
            "objects | groupBy(class, agg=sum, value=shallow, sortBy=value, asc=true)",
            "objects | groupBy(class, agg=min, value=shallow, sortBy=value)",
            "objects | groupBy(arrayLength, sortBy=key, asc=true)",
            "objects | groupBy(class, arrayLength, agg=avg, value=arrayLength)")) {
      // groupBy | top(3, class) used to rank by the aggregate first: different rows
      assertEquals(normalized(inMemory(q)), normalized(run(q)), q);
    }
  }

  @Test
  void streamedSumChecksTheFieldAndNamesTheColumnLikeTheInMemoryPath() {
    // Was {sum=0.0} streamed, an error in memory
    assertThrows(
        IllegalArgumentException.class,
        () -> run("objects/instanceof/java.util.HashMap | waste() | sum(nosuchfield)"));
    // Was {sum=...} streamed, {shallow=...} in memory
    assertEquals(
        normalized(inMemory("objects | waste() | sum(shallow)")),
        normalized(run("objects | waste() | sum(shallow)")));
  }

  @Test
  void indexedStreamingFindsPrimitiveArrays(@TempDir Path dir) throws IOException {
    // The class-instances index has no primitive arrays; streaming [B through it found nothing
    Path copy = dir.resolve("copy.hprof");
    java.nio.file.Files.copy(session.getFilePath(), copy);
    try (HeapSession indexed = HeapSession.open(copy, ParserOptions.INDEXED)) {
      for (String q : List.of("objects/[B | count", "objects/byte[] | count")) {
        List<Map<String, Object>> rows =
            HdumpPathEvaluator.evaluate(indexed, HdumpPathParser.parse(q));
        assertEquals(2L, ((Number) rows.get(0).get("count")).longValue(), q);
      }
      assertEquals(2, indexed.getHeapDump().getClassByName("[B").get().getInstanceCount());
    }
  }

  @Test
  void groupByEdgeCasesHaveOneDefinition() {
    // A value= field missing on a row is skipped, not NaN; a group without values reads null
    Map<String, Object> plain =
        inMemory("objects | groupBy(class, agg=sum, value=arrayLength)").stream()
            .filter(r -> "com.example.Plain".equals(r.get("class")))
            .findFirst()
            .orElseThrow();
    assertTrue(plain.containsKey("sum"), plain.toString());
    assertNull(plain.get("sum"), plain.toString());
    // sortBy=value sorts by the aggregate column ("shallow" here), which in memory it did not
    List<Map<String, Object>> asc =
        inMemory("objects | groupBy(class, agg=sum, value=shallow, sortBy=value, asc=true)");
    for (int i = 1; i < asc.size(); i++) {
      double prev = ((Number) asc.get(i - 1).get("shallow")).doubleValue();
      double cur = ((Number) asc.get(i).get("shallow")).doubleValue();
      assertTrue(prev <= cur, asc.toString());
    }
    // groups without a key value sort last in both directions
    List<Map<String, Object>> byKey =
        inMemory("objects | groupBy(arrayLength, sortBy=key, asc=true)");
    assertNull(byKey.get(byKey.size() - 1).get("arrayLength"), byKey.toString());
    // the count has one type
    assertInstanceOf(Long.class, inMemory("objects | groupBy(class)").get(0).get("count"));
  }
}
