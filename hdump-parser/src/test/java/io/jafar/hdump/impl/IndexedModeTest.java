package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.index.IndexFormat;
import io.jafar.hdump.test.GraphHeapDumpWriter;
import io.jafar.hdump.test.GraphHeapDumpWriter.RootType;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.INDEXED)) {
      assertTrue(((HeapDumpImpl) dump).getObjectIndexReader() != null, "INDEXED mode");
      dump.computeDominators();
      assertTrue(Files.exists(indexDir.resolve(IndexFormat.STRONGREFS_INDEX_NAME)));
      int mismatches = 0;
      StringBuilder first = new StringBuilder();
      for (int v = 0; v < w.nodeCount(); v++) {
        HeapObject o = dump.getObjectById(w.objectId(v)).orElseThrow();
        long actual = o.getRetainedSize();
        if (actual != expected[v]) {
          if (mismatches++ < 5) {
            first.append(" node ").append(v).append(" expected ").append(expected[v]);
            first.append(" actual ").append(actual).append(';');
          }
        }
      }
      assertEquals(0, mismatches, "approximate retained size mismatches:" + first);
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
