package io.jafar.hdump.shell.hdumppath;

import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_INT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_OBJECT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.fieldData;
import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.MinimalHprofBuilder;
import io.jafar.hdump.shell.MinimalHprofBuilder.Field;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * 150 {@code com.example.Plain} objects after them, by id and in the file. The old non-aggregating
 * cap kept the last 100 objects of the stream (a constant-comparator heap evicts its oldest
 * element), so it never saw a map here.
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
    assertEquals(16, top.get(1).get("arrayLength"));
  }

  @Test
  void wasteThenTopStreamsEveryObject() {
    // Was waste() over 100 objects only (the last 100, all Plain): no row had a value
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
}
