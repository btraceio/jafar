package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.impl.ApproximateRetainedSizeComputer.IndexedStrategy;
import io.jafar.hdump.index.IndexFormat;
import io.jafar.hdump.test.GraphHeapDumpWriter;
import io.jafar.hdump.test.GraphHeapDumpWriter.RootType;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * INDEXED mode's approximate retained sizes against the oracle: the per-object BFS rule with
 * inbound counts over every object field (inbound.idx counts {@code referent} too) and expansion
 * over strong references, computed on the on-disk graph (strongrefs.idx). Also: the strong-ref
 * index is built once and reused on reopen.
 */
class IndexedModeTest {

  @ParameterizedTest(name = "random graph seed={0}")
  @ValueSource(longs = {1, 2, 3, 4, 5, 6})
  void approximateSizesOnRandomGraphs(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(7000 + seed);
    int n = 300 + rnd.nextInt(700);
    verify(DominatorOracleTest.randomGraph(rnd, n, 0.45, seed % 2 == 0, 3), dir);
  }

  /**
   * INDEXED {@code identifyInterestingObjects} reads objects.idx and retained.idx instead of
   * loading every object; it must pick exactly what a {@code getObjects()} loop with the same top-N
   * queue and pattern matching picks — same order, so the same choice among equal sizes — on a
   * fresh dump and after an exact computation left exact sizes on cached instances, with a cache
   * that holds the heap and one that does not.
   */
  @ParameterizedTest(name = "interesting objects, indexed, seed={0}")
  @ValueSource(longs = {1, 2, 3})
  void interestingObjectsMatchAGetObjectsLoop(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(8000 + seed);
    GraphHeapDumpWriter w =
        DominatorOracleTest.randomGraph(rnd, 400 + rnd.nextInt(400), 0.45, false, 3);
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    for (int cache : new int[] {100_000, 50}) {
      ParserOptions options =
          ParserOptions.builder()
              .parsingMode(HeapDumpParser.ParsingMode.INDEXED)
              .objectCacheSize(cache)
              .build();
      try (HeapDump heap = HeapDumpParser.parse(file, options)) {
        HeapDumpImpl dump = (HeapDumpImpl) heap;
        dump.computeDominators();
        // After an exact computation, only a cache that holds the heap gives a defined answer:
        // with a smaller one, which exact sizes survive depends on what loading evicted.
        for (int round = 0; round < (cache >= 100_000 ? 2 : 1); round++) {
          for (int k : new int[] {5, 20, 100}) {
            for (java.util.Set<String> patterns :
                java.util.List.of(java.util.Set.<String>of(), java.util.Set.of("?B"))) {
              assertEquals(
                  referenceInteresting(dump, k, patterns),
                  HybridDominatorComputer.identifyInterestingObjects(dump, k, patterns),
                  "cache " + cache + " round " + round + " k " + k + " patterns " + patterns);
            }
          }
          // second round: exact sizes now sit on cached instances
          dump.computeExactForClasses(java.util.Set.of("*WeakReference", "test/Node"), null);
        }
      }
    }
  }

  /** The heuristics of identifyInterestingObjects over getObjects(), as before the change. */
  private static it.unimi.dsi.fastutil.longs.LongOpenHashSet referenceInteresting(
      HeapDumpImpl dump, int k, java.util.Set<String> patterns) {
    var queue = new HybridDominatorComputer.TopN(k);
    var result = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    java.util.List<java.util.regex.Pattern> compiled = new java.util.ArrayList<>();
    for (String p : patterns) {
      compiled.add(
          java.util.regex.Pattern.compile(
              p.replace(".", "\\.").replace("*", ".*").replace("?", ".")));
    }
    dump.getObjects()
        .forEach(
            o -> {
              long retained = o.getRetainedSize();
              if (retained > 0) queue.offer(retained, o.getId());
              String name = o.getHeapClass() != null ? o.getHeapClass().getName() : null;
              if (name != null) {
                for (var p : compiled) {
                  if (p.matcher(name).matches()) {
                    result.add(o.getId());
                    break;
                  }
                }
              }
            });
    for (long[] e : queue.entries()) result.add(e[1]);
    return result;
  }

  /** An unknown -Djafar.hdump.retained.strategy is ignored (AUTO), not a broken class. */
  @Test
  void unknownStrategyPropertyFallsBackToAuto(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = DominatorOracleTest.randomGraph(new Random(5), 300, 0.45, false, 3);
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    String old = System.getProperty("jafar.hdump.retained.strategy");
    System.setProperty("jafar.hdump.retained.strategy", "no-such-strategy");
    try {
      long[] auto = indexedSizes(file, w, null, 256);
      Files.delete(dir.resolve("graph.hprof.idx").resolve(IndexFormat.RETAINED_INDEX_NAME));
      System.setProperty("jafar.hdump.retained.strategy", "per_object");
      assertArrayEquals(auto, indexedSizes(file, w, null, 256), "lower-case value accepted");
    } finally {
      if (old == null) System.clearProperty("jafar.hdump.retained.strategy");
      else System.setProperty("jafar.hdump.retained.strategy", old);
    }
  }

  /** Once-referenced chains, shared leaves, unreachable cycles, weak references. */
  @Test
  void approximateSizesOnTreesWithSharedLeavesAndCycles(@TempDir Path dir) throws IOException {
    Random rnd = new Random(77);
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int[] tree = new int[600];
    for (int i = 0; i < tree.length; i++) {
      tree[i] = w.addObjectArray(8);
      if (i > 0) w.addEdge(tree[Math.max(0, i - 1 - rnd.nextInt(3))], tree[i]);
    }
    for (int j = 0; j < 40; j++) {
      int leaf = w.addByteArray(rnd.nextInt(50));
      for (int k = 0; k < 2 + rnd.nextInt(3); k++) w.addEdge(tree[rnd.nextInt(tree.length)], leaf);
    }
    int weak = w.addWeakReference();
    w.setSlot(weak, 0, tree[300]); // referent: counted by inbound.idx, not expanded
    w.addEdge(tree[10], weak);
    int a = w.addObjectArray(2), b = w.addObjectArray(2);
    w.addEdge(a, b);
    w.addEdge(b, a);
    w.addEdge(a, w.addByteArray(5));
    w.addRoot(tree[0], RootType.JAVA_FRAME);
    verify(w, dir);
  }

  /**
   * On a real heap dump (class entries, references to class objects, Reference subclasses, empty
   * and Class[] arrays): for every id32, the references decoded from the dump, the stored row of
   * strongrefs.idx and the loaded object's getStrongOutboundReferenceIds() (mapped to id32, ids
   * that name no object dropped) are the same.
   */
  @Test
  void decodedStoredAndLoadedReferencesAgree(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("self.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(file.toString(), true);
    try (HeapDump heap = HeapDumpParser.parse(file, ParserOptions.INDEXED)) {
      HeapDumpImpl dump = (HeapDumpImpl) heap;
      IndexedRefs decoded = new IndexedRefs(dump);
      IndexedRefs stored = new IndexedRefs(dump, dump.strongRefsIndex());
      var address = dump.getId32ToAddress();
      var id32 = dump.getAddressToId32();
      int classEntries = 0;
      for (int v = 0; v < decoded.size(); v++) {
        // strongRefs() fills refs(): call it first (R11: operands evaluate left to right)
        int dk = decoded.strongRefs(v);
        int[] d = java.util.Arrays.copyOf(decoded.refs(), dk);
        int sk = stored.strongRefs(v);
        int[] st = java.util.Arrays.copyOf(stored.refs(), sk);
        assertArrayEquals(d, st, "decoded vs stored, id32 " + v);
        if (decoded.isClassEntry(v)) {
          classEntries++;
          assertEquals(0, d.length, "class entry " + v);
          continue;
        }
        HeapObjectImpl obj = dump.getObjectByIdInternal(address.get(v));
        int[] loaded =
            java.util.Arrays.stream(obj.getStrongOutboundReferenceIds())
                .mapToInt(id32::get)
                .filter(x -> x >= 0)
                .toArray();
        assertArrayEquals(loaded, d, "decoded vs loaded object, id32 " + v + " " + obj);
      }
      assertTrue(classEntries > 0, "a real dump has class entries");
    }
  }

  /** Every strategy, with budgets small enough that the budgeted path patches most objects. */
  private static final Object[][] STRATEGIES = {
    {IndexedStrategy.PER_OBJECT, 256, false},
    {IndexedStrategy.BUDGETED, 1, false},
    {IndexedStrategy.BUDGETED, 4, false},
    {IndexedStrategy.BUDGETED, 256, false},
    // the large objects' subgraph "does not fit": they fall back to one BFS each
    {IndexedStrategy.BUDGETED, 1, true},
    {IndexedStrategy.ALL_AT_ONCE, 256, false},
    {IndexedStrategy.AUTO, 256, false},
  };

  /** Computes INDEXED approximate sizes with a pinned strategy; returns them by node. */
  private static long[] indexedSizes(
      Path file, GraphHeapDumpWriter w, IndexedStrategy strategy, int budget) throws IOException {
    return indexedSizes(file, w, strategy, budget, false);
  }

  private static long[] indexedSizes(
      Path file, GraphHeapDumpWriter w, IndexedStrategy strategy, int budget, boolean noHeap)
      throws IOException {
    return indexedRun(file, w, strategy, budget, noHeap).sizes;
  }

  /** One INDEXED computation's values and how often its subgraph fallback ran. */
  private record Run(long[] sizes, int fallbacks) {}

  private static Run indexedRun(
      Path file, GraphHeapDumpWriter w, IndexedStrategy strategy, int budget, boolean noHeap)
      throws IOException {
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.INDEXED)) {
      HeapDumpImpl impl = (HeapDumpImpl) dump;
      assertTrue(impl.getObjectIndexReader() != null, "INDEXED mode");
      var options = impl.retainedSizeOptions();
      options.strategy = strategy;
      options.budget = budget;
      if (noHeap) options.availableHeap = () -> 0L;
      dump.computeDominators();
      long[] out = new long[w.nodeCount()];
      for (int v = 0; v < out.length; v++) {
        out[v] = dump.getObjectById(w.objectId(v)).orElseThrow().getRetainedSize();
      }
      return new Run(out, options.subgraphFallbacks.get());
    }
  }

  /**
   * Long chains of once-referenced objects: a 200k chain from a root, and one hanging off an
   * unreachable two-node cycle. The per-object BFS is quadratic here (2*10^10 visits); the budgeted
   * and all-at-once strategies must finish well inside the timeout with the suffix sums.
   */
  @Test
  void longChainsAreLinear(@TempDir Path dir) throws IOException {
    int tail = 200_000;
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    // nodes 0..1: an unreachable cycle; node 0 also references a chain of `tail` nodes;
    // a second, rooted chain of `tail` nodes follows
    for (int i = 0; i < 2 + 2 * tail; i++) w.addInstance();
    w.addEdge(0, 1);
    w.addEdge(1, 0);
    w.addEdge(0, 2);
    for (int i = 2; i + 1 < 2 + tail; i++) w.addEdge(i, i + 1);
    int rooted = 2 + tail;
    for (int i = rooted; i + 1 < rooted + tail; i++) w.addEdge(i, i + 1);
    w.addRoot(rooted, RootType.JAVA_FRAME);
    Path file = dir.resolve("chains.hprof");
    w.write(file, 1 << 22);
    long[] shallow;
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      shallow = new long[w.nodeCount()];
      for (int v = 0; v < shallow.length; v++) {
        shallow[v] = dump.getObjectById(w.objectId(v)).orElseThrow().getShallowSize();
      }
    }
    int n = w.nodeCount();
    long[] expected = new long[n];
    for (int i = rooted + tail - 1; i >= rooted; i--) {
      expected[i] = shallow[i] + (i + 1 < rooted + tail ? expected[i + 1] : 0);
    }
    for (int i = 2 + tail - 1; i >= 2; i--) {
      expected[i] = shallow[i] + (i + 1 < 2 + tail ? expected[i + 1] : 0);
    }
    expected[0] = expected[1] = shallow[0] + shallow[1] + expected[2];
    Path indexDir = dir.resolve("chains.hprof.idx");
    for (IndexedStrategy strategy :
        new IndexedStrategy[] {IndexedStrategy.BUDGETED, IndexedStrategy.ALL_AT_ONCE}) {
      Files.deleteIfExists(indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME));
      long[][] actual = new long[1][];
      assertTimeoutPreemptively(
          Duration.ofSeconds(60), () -> actual[0] = indexedSizes(file, w, strategy, 256));
      for (int v = 0; v < n; v++) {
        assertEquals(expected[v], actual[0][v], strategy + ": node " + v);
      }
    }
  }

  /**
   * True if budget 1 makes some object large: an object referenced at most once (counting every
   * field, as inbound.idx does) with at least one strong reference costs more than 1 to search.
   */
  private static boolean hasOnceReferencedChildren(GraphHeapDumpWriter w) {
    int[][] all = w.allAdjacency();
    int[][] strong = w.strongAdjacency();
    int[] inbound = new int[all.length];
    for (int[] out : all) for (int t : out) inbound[t]++;
    for (int v = 0; v < strong.length; v++)
      if (inbound[v] <= 1 && strong[v].length > 0) return true;
    return false;
  }

  private static void verify(GraphHeapDumpWriter w, Path dir) throws IOException {
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    long[] shallow;
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      shallow = new long[w.nodeCount()];
      for (int v = 0; v < shallow.length; v++) {
        shallow[v] = dump.getObjectById(w.objectId(v)).orElseThrow().getShallowSize();
      }
    }
    long[] expected =
        DominatorOracle.approximateRetained(w.strongAdjacency(), w.allAdjacency(), shallow);
    Path indexDir = dir.resolve("graph.hprof.idx");
    for (Object[] run : STRATEGIES) {
      Files.deleteIfExists(indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME));
      boolean noHeap = (Boolean) run[2];
      Run result = indexedRun(file, w, (IndexedStrategy) run[0], (Integer) run[1], noHeap);
      long[] actual = result.sizes();
      int ranFallback = result.fallbacks();
      if (!noHeap) {
        assertEquals(0, ranFallback, run[0] + " budget " + run[1] + ": unexpected fallback");
      } else if (hasOnceReferencedChildren(w)) {
        assertTrue(ranFallback > 0, "budget 1 with no heap must take the fallback");
      }
      assertTrue(Files.exists(indexDir.resolve(IndexFormat.STRONGREFS_INDEX_NAME)));
      int mismatches = 0;
      StringBuilder first = new StringBuilder();
      for (int v = 0; v < w.nodeCount(); v++) {
        if (actual[v] != expected[v] && mismatches++ < 5) {
          first.append(" node ").append(v).append(" expected ").append(expected[v]);
          first.append(" actual ").append(actual[v]).append(';');
        }
      }
      assertEquals(
          0,
          mismatches,
          run[0]
              + " budget "
              + run[1]
              + (noHeap ? " (subgraph fallback)" : "")
              + ": approximate size mismatches:"
              + first);
    }
    // reopen: strongrefs.idx reused, not rebuilt (same file, not a replacement)
    Path refsFile = indexDir.resolve(IndexFormat.STRONGREFS_INDEX_NAME);
    Object key = Files.readAttributes(refsFile, BasicFileAttributes.class).fileKey();
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.INDEXED)) {
      assertTrue(((HeapDumpImpl) dump).strongRefsIndex() != null);
    }
    if (key != null) {
      assertEquals(
          key,
          Files.readAttributes(refsFile, BasicFileAttributes.class).fileKey(),
          "strongrefs.idx rebuilt on reopen");
    }
    // a truncated index is detected and rebuilt, and gives the same answers
    try (var ch = java.nio.channels.FileChannel.open(refsFile, StandardOpenOption.WRITE)) {
      ch.truncate(Math.max(0, ch.size() - 4));
    }
    Files.delete(indexDir.resolve(IndexFormat.RETAINED_INDEX_NAME));
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.INDEXED)) {
      dump.computeDominators();
      for (int v = 0; v < w.nodeCount(); v++) {
        assertEquals(
            expected[v],
            dump.getObjectById(w.objectId(v)).orElseThrow().getRetainedSize(),
            "after rebuilding a truncated index, node " + v);
      }
    }
  }
}
