package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import io.jafar.hdump.test.GraphHeapDumpWriter;
import io.jafar.hdump.test.GraphHeapDumpWriter.RootType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares the HPROF graph operators (full dominator tree, approximate retained size, path to GC
 * root) against {@link DominatorOracle} on synthetic graphs written by {@link GraphHeapDumpWriter}.
 * Every object is compared, not a sample (except path queries on large graphs).
 */
class DominatorOracleTest {

  // ================================================================ random shapes

  @ParameterizedTest(name = "random cyclic graph seed={0}")
  @ValueSource(longs = {1, 2, 3, 4, 5, 6})
  void randomSparseCyclicGraph(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(seed);
    int n = 300 + rnd.nextInt(1200);
    double p = 0.3 + rnd.nextDouble() * 0.2;
    int roots = 1 + rnd.nextInt(5);
    // odd seeds use tiny segments so multi-segment parsing is covered too
    verifyAll(randomGraph(rnd, n, p, false, roots), dir, seed % 2 == 1 ? 3000 : 1 << 24);
  }

  @ParameterizedTest(name = "random DAG seed={0}")
  @ValueSource(longs = {1, 2, 3, 4, 5, 6})
  void randomDag(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(1000 + seed);
    int n = 300 + rnd.nextInt(1200);
    double p = 0.3 + rnd.nextDouble() * 0.3;
    verifyAll(randomGraph(rnd, n, p, true, 2 + n / 30), dir, 1 << 24);
  }

  @ParameterizedTest(name = "random dense-ish cyclic graph seed={0}")
  @ValueSource(longs = {1, 2, 3})
  void randomDenserCyclicGraph(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(2000 + seed);
    verifyAll(randomGraph(rnd, 500, 0.7, false, 3), dir, 1 << 24);
  }

  /**
   * The shape the all-objects minimum-retained-size computation ({@code MinRetainedSizes}) is built
   * around: a tree of once-referenced objects (each node's only referrer is its parent), shared
   * leaves referenced from several branches and from the same node twice, shared inner nodes that
   * cut the tree, and unreachable cycles of once-referenced objects with subtrees hanging off them
   * (the per-object fallback path).
   */
  @ParameterizedTest(name = "forest with shared leaves seed={0}")
  @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
  void forestWithSharedLeaves(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(3000 + seed);
    int treeNodes = 400 + rnd.nextInt(800);
    int shared = 20 + rnd.nextInt(60);
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(0);
    int[] tree = new int[treeNodes];
    for (int i = 0; i < treeNodes; i++) {
      tree[i] = w.addObjectArray(64);
      if (i > 0) {
        // mostly deep (recent parent), sometimes wide
        int parent = rnd.nextInt(3) == 0 ? rnd.nextInt(i) : Math.max(0, i - 1 - rnd.nextInt(3));
        w.addEdge(tree[parent], tree[i]);
      }
    }
    int[] leaves = new int[shared];
    for (int j = 0; j < shared; j++) {
      leaves[j] = rnd.nextInt(4) == 0 ? w.addObjectArray(2) : w.addByteArray(rnd.nextInt(100));
      int refs = 2 + rnd.nextInt(5);
      for (int k = 0; k < refs; k++) {
        int from = tree[rnd.nextInt(treeNodes)];
        w.addEdge(from, leaves[j]);
        if (rnd.nextInt(5) == 0) {
          w.addEdge(from, leaves[j]); // duplicate reference from the same referrer
        }
      }
    }
    // shared arrays among the leaves point back into the tree: the target gets a second referrer
    for (int j = 0; j < shared; j++) {
      if (w.slotCountOf(leaves[j]) > 0 && rnd.nextBoolean()) {
        w.addEdge(leaves[j], tree[rnd.nextInt(treeNodes)]);
      }
    }
    // unreachable once-referenced cycles, each with a small subtree and a shared leaf
    for (int c = 0; c < 3; c++) {
      int len = 1 + rnd.nextInt(5);
      int[] cyc = new int[len];
      for (int i = 0; i < len; i++) cyc[i] = w.addObjectArray(3);
      for (int i = 0; i < len; i++) w.addEdge(cyc[i], cyc[(i + 1) % len]);
      int hang = w.addObjectArray(2);
      w.addEdge(cyc[0], hang);
      w.addEdge(hang, w.addByteArray(10));
      w.addEdge(hang, leaves[rnd.nextInt(shared)]);
    }
    w.addRoot(tree[0], RootType.JAVA_FRAME);
    verifyAll(w, dir, 1 << 24);
  }

  /**
   * Random graph: ~60% instances (4 slots), 20% object arrays, 10% byte arrays, 10% weak refs. Each
   * slot is filled with probability {@code p}; targets are uniform or local (to get depth).
   * Sprinkled in: self loops, duplicate edges, dangling ids. Unfilled slots stay null.
   */
  static GraphHeapDumpWriter randomGraph(Random rnd, int n, double p, boolean dag, int rootCount) {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(4);
    for (int i = 0; i < n; i++) {
      int k = rnd.nextInt(10);
      if (k < 6) w.addInstance();
      else if (k < 8) w.addObjectArray(1 + rnd.nextInt(6));
      else if (k < 9) w.addByteArray(rnd.nextInt(64));
      else w.addWeakReference();
    }
    for (int v = 0; v < n; v++) {
      int slots = w.slotCountOf(v);
      int prev = GraphHeapDumpWriter.NULL;
      for (int s = 0; s < slots; s++) {
        if (rnd.nextDouble() >= p) continue;
        int t;
        double c = rnd.nextDouble();
        if (c < 0.03) {
          t = v; // self loop
        } else if (c < 0.07 && prev >= 0) {
          t = prev; // duplicate edge
        } else if (c < 0.09) {
          t = GraphHeapDumpWriter.DANGLING;
        } else if (dag) {
          if (v == n - 1) continue;
          t =
              v
                  + 1
                  + (rnd.nextBoolean()
                      ? rnd.nextInt(n - v - 1)
                      : rnd.nextInt(Math.min(8, n - v - 1)));
          if (t == v) continue;
        } else if (rnd.nextBoolean()) {
          t = rnd.nextInt(n);
        } else {
          t = Math.floorMod(v + rnd.nextInt(41) - 20, n);
        }
        if (dag && t >= 0 && t <= v) continue;
        w.setSlot(v, s, t);
        prev = t;
      }
    }
    RootType[] types = RootType.values();
    Set<Integer> chosen = new HashSet<>();
    chosen.add(0);
    for (int i = 0; i < rootCount; i++) chosen.add(rnd.nextInt(n));
    for (int r : chosen) w.addRoot(r, types[rnd.nextInt(types.length)]);
    return w;
  }

  // ================================================================ hand-made shapes

  @Test
  void diamond(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int r = w.addInstance(), a = w.addInstance(), b = w.addInstance();
    int c = w.addInstance(), d = w.addInstance(), e = w.addInstance();
    w.addEdge(r, a);
    w.addEdge(a, b);
    w.addEdge(a, c);
    w.addEdge(b, d);
    w.addEdge(c, d);
    w.addEdge(d, e);
    w.addRoot(r, RootType.JAVA_FRAME);
    verifyAll(w, dir, 1 << 24);
  }

  /**
   * A {@code java.util.LinkedList}: the list object holds {@code first} and {@code last}, every
   * node holds {@code next} and {@code prev}, so the list object is the immediate dominator of
   * every node. An iterative (Cooper-Harvey-Kennedy) solver fixes one node per pass here, so a long
   * list used to trip its stagnation guard and leave each node "dominated" by its predecessor.
   */
  @Test
  void doublyLinkedListHeldAtBothEnds(@TempDir Path dir) throws IOException {
    int len = 600;
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int list = w.addInstance();
    int[] node = new int[len];
    for (int i = 0; i < len; i++) {
      node[i] = w.addInstance();
    }
    w.addEdge(list, node[0]);
    w.addEdge(list, node[len - 1]);
    for (int i = 0; i < len; i++) {
      if (i + 1 < len) {
        w.addEdge(node[i], node[i + 1]);
      }
      if (i > 0) {
        w.addEdge(node[i], node[i - 1]);
      }
    }
    w.addRoot(list, RootType.JAVA_FRAME);
    verifyAll(w, dir, 1 << 24);
  }

  /**
   * {@code HeapDumpImpl.computeExactForClasses}: the matching objects are expanded backwards to the
   * GC roots (a root is included but not expanded further) and exact dominators are computed on
   * that subgraph. Checks the subgraph itself (via {@code hasExactRetainedSize}) and its
   * dominators.
   */
  @ParameterizedTest(name = "exact dominators for a class pattern seed={0}")
  @ValueSource(longs = {1, 2, 3, 4})
  void exactForClassesExpandsToRootPaths(long seed, @TempDir Path dir) throws IOException {
    exactForClasses(seed, dir, ParserOptions.IN_MEMORY);
  }

  /** The same in INDEXED mode (expansion on the on-disk graph's reverse). */
  @ParameterizedTest(name = "exact dominators for a class pattern, indexed, seed={0}")
  @ValueSource(longs = {1, 2, 3, 4})
  void exactForClassesExpandsToRootPathsIndexed(long seed, @TempDir Path dir) throws IOException {
    exactForClasses(seed, dir, ParserOptions.INDEXED);
  }

  private static void exactForClasses(long seed, Path dir, ParserOptions options)
      throws IOException {
    Random rnd = new Random(6000 + seed);
    int n = 300 + rnd.nextInt(500);
    GraphHeapDumpWriter w = randomGraph(rnd, n, 0.4, false, 4);
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    int[][] adj = w.strongAdjacency();
    List<List<Integer>> preds = new ArrayList<>();
    for (int v = 0; v < n; v++) preds.add(new ArrayList<>());
    for (int u = 0; u < n; u++) for (int t : adj[u]) preds.get(t).add(u);
    boolean[] root = new boolean[n];
    for (int r : w.rootNodes()) root[r] = true;

    try (HeapDump dump = HeapDumpParser.parse(file, options)) {
      // expected subgraph: backward closure from every weak reference, not expanding roots
      boolean[] in = new boolean[n];
      java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
      int seeds = 0;
      for (int v = 0; v < n; v++) {
        if (obj(dump, v).getHeapClass().getName().equals("java/lang/ref/WeakReference")) {
          in[v] = true;
          queue.add(v);
          seeds++;
        }
      }
      assertTrue(seeds > 0, "fixture has no weak references");
      while (!queue.isEmpty()) {
        int v = queue.poll();
        if (root[v]) continue;
        for (int p : preds.get(v)) {
          if (!in[p]) {
            in[p] = true;
            queue.add(p);
          }
        }
      }
      ((HeapDumpImpl) dump).computeExactForClasses(Set.of("*WeakReference"), null);

      int[][] sub = new int[n][];
      for (int v = 0; v < n; v++) {
        sub[v] = in[v] ? java.util.Arrays.stream(adj[v]).filter(t -> in[t]).toArray() : new int[0];
      }
      long[] shallow = shallowSizes(dump, w);
      DominatorOracle o = new DominatorOracle(sub, w.rootNodes(), shallow);
      Mismatches mm = new Mismatches("exact for classes");
      for (int v = 0; v < n; v++) {
        HeapObjectImpl obj = obj(dump, v);
        mm.check(v, "inSubgraph", in[v], obj.hasExactRetainedSize());
        if (!in[v]) continue;
        mm.check(v, "idom", o.idom[v], nodeOf(obj.getDominator()));
        if (o.reachable[v]) mm.check(v, "retainedSize", o.retained[v], obj.getRetainedSize());
      }
      mm.assertNone();
    }
  }

  /** The same object listed by several GC root records (as HotSpot does for thread locals). */
  @Test
  void duplicateRootRecords(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int r = w.addInstance(), a = w.addInstance(), b = w.addInstance(), c = w.addInstance();
    w.addEdge(r, a);
    w.addEdge(a, b);
    w.addEdge(b, c);
    w.addEdge(c, a);
    w.addRoot(r, RootType.JAVA_FRAME);
    w.addRoot(r, RootType.JAVA_FRAME);
    w.addRoot(b, RootType.JNI_LOCAL);
    w.addRoot(r, RootType.THREAD_OBJ);
    w.addRoot(b, RootType.JNI_GLOBAL);
    verifyAll(w, dir, 1 << 24);
  }

  /** No GC roots at all: nothing is reachable, nothing gets a dominator. */
  @Test
  void noRoots(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int a = w.addInstance(), b = w.addInstance();
    w.addEdge(a, b);
    w.addEdge(b, a);
    verifyAll(w, dir, 1 << 24);
  }

  /**
   * {@code HybridDominatorComputer.computeExactForSubgraph}: the dominator tree of the subgraph
   * induced by the chosen objects, with the GC roots that fall inside it.
   */
  @ParameterizedTest(name = "exact dominators on a subgraph seed={0}")
  @ValueSource(longs = {1, 2, 3, 4})
  void exactForSubgraph(long seed, @TempDir Path dir) throws IOException {
    Random rnd = new Random(4000 + seed);
    int n = 300 + rnd.nextInt(500);
    GraphHeapDumpWriter w = randomGraph(rnd, n, 0.45, false, 4);
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    int[][] adj = w.strongAdjacency();
    boolean[] in = new boolean[n];
    for (int v = 0; v < n; v++) in[v] = rnd.nextInt(10) < 7;
    for (int r : w.rootNodes()) in[r] = true;
    int[][] sub = new int[n][];
    for (int v = 0; v < n; v++) {
      if (!in[v]) {
        sub[v] = new int[0];
        continue;
      }
      sub[v] = java.util.Arrays.stream(adj[v]).filter(t -> in[t]).toArray();
    }
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      long[] shallow = shallowSizes(dump, w);
      DominatorOracle o = new DominatorOracle(sub, w.rootNodes(), shallow);
      it.unimi.dsi.fastutil.longs.LongOpenHashSet ids =
          new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      for (int v = 0; v < n; v++) if (in[v]) ids.add(w.objectId(v));
      List<GcRootImpl> roots = new ArrayList<>();
      for (var r : dump.getGcRoots()) roots.add((GcRootImpl) r);
      HybridDominatorComputer.computeExactForSubgraph((HeapDumpImpl) dump, roots, ids, null);
      Mismatches mm = new Mismatches("subgraph dominators");
      for (int v = 0; v < n; v++) {
        if (!in[v]) continue;
        HeapObjectImpl obj = obj(dump, v);
        mm.check(v, "idom", o.idom[v], nodeOf(obj.getDominator()));
        if (o.reachable[v]) mm.check(v, "retainedSize", o.retained[v], obj.getRetainedSize());
      }
      mm.assertNone();
    }
  }

  /**
   * An unreachable cycle of once-referenced objects with a long once-referenced chain hanging off
   * it (an orphaned singly linked list whose head keeps an owner back-pointer). Every cycle member
   * expands the cycle and the whole chain; every chain node expands its suffix.
   */
  @Test
  void longChainBelowCycle(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter small = cycleWithTail(3, 2_000);
    small.addRoot(small.addInstance(), RootType.JAVA_FRAME);
    verifyAll(small, dir, 1 << 24);

    int tail = 200_000;
    GraphHeapDumpWriter w = cycleWithTail(2, tail);
    Path file = dir.resolve("cycle-tail.hprof");
    w.write(file, 1 << 22);
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      long[] shallow = shallowSizes(dump, w);
      int n = w.nodeCount(); // nodes 0..1 cycle, 2..n-1 the chain
      long[] suffix = new long[n + 1];
      for (int i = n - 1; i >= 2; i--) suffix[i] = suffix[i + 1] + shallow[i];
      long all = suffix[2] + shallow[0] + shallow[1];
      assertTimeoutPreemptively(Duration.ofSeconds(60), () -> dump.computeDominators());
      Mismatches mm = new Mismatches("chain below cycle");
      for (int v = 0; v < n; v++) {
        long expected = v < 2 ? all : suffix[v];
        mm.check(v, "approx", expected, obj(dump, v).getRetainedSizeIfAvailable());
      }
      mm.assertNone();
    }
  }

  /** Nodes {@code 0 .. cycle-1} form a cycle; node 0 also references the chain's first node. */
  private static GraphHeapDumpWriter cycleWithTail(int cycle, int tail) {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    for (int i = 0; i < cycle + tail; i++) w.addInstance();
    for (int i = 0; i < cycle; i++) w.addEdge(i, (i + 1) % cycle);
    w.addEdge(0, cycle);
    for (int i = cycle; i + 1 < cycle + tail; i++) w.addEdge(i, i + 1);
    return w;
  }

  @Test
  void diamondLadder(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int top = w.addInstance();
    w.addRoot(top, RootType.UNKNOWN);
    for (int i = 0; i < 60; i++) {
      int l = w.addInstance(), r = w.addInstance(), bottom = w.addInstance();
      w.addEdge(top, l);
      w.addEdge(top, r);
      w.addEdge(l, bottom);
      w.addEdge(r, bottom);
      top = bottom;
    }
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void multipleRootsSharingSubgraphs(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(3);
    int r1 = w.addInstance(), r2 = w.addInstance(), r3 = w.addInstance();
    int x = w.addInstance(), y = w.addInstance(), z = w.addInstance();
    int p = w.addInstance(), q = w.addInstance();
    w.addEdge(r1, x);
    w.addEdge(r2, x);
    w.addEdge(x, y);
    w.addEdge(y, z);
    w.addEdge(r1, p);
    w.addEdge(r2, q);
    w.addEdge(r3, z); // z shared by x-subtree and r3
    w.addRoot(r1, RootType.JNI_GLOBAL);
    w.addRoot(r2, RootType.THREAD_OBJ);
    w.addRoot(r3, RootType.STICKY_CLASS);
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void rootAlsoReachableFromAnotherRoot(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int r1 = w.addInstance(), mid = w.addInstance(), r2 = w.addInstance();
    int leaf = w.addInstance(), leaf2 = w.addInstance();
    w.addEdge(r1, mid);
    w.addEdge(mid, r2); // r2 is a root and reachable from r1
    w.addEdge(r2, r1); // ... and a cycle back
    w.addEdge(r2, leaf);
    w.addEdge(leaf, leaf2);
    w.addRoot(r1, RootType.NATIVE_STACK);
    w.addRoot(r2, RootType.MONITOR_USED);
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void unreachableNodesAndCycles(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int root = w.addInstance(), b = w.addInstance(), c = w.addInstance();
    int u1 = w.addInstance(), u2 = w.addInstance(), u3 = w.addInstance();
    int lonely = w.addByteArray(100);
    w.addEdge(root, b);
    w.addEdge(b, c);
    w.addEdge(u1, u2);
    w.addEdge(u2, u1); // unreachable cycle
    w.addEdge(u2, u3);
    w.addEdge(u1, b); // unreachable node pointing into the reachable graph
    w.addEdge(u3, c);
    w.addRoot(root, RootType.THREAD_BLOCK);
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void selfLoopsDuplicateEdgesNullsDanglingAndArrays(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(4);
    int a = w.addInstance(), b = w.addInstance(), c = w.addInstance();
    int arr = w.addObjectArray(6), empty = w.addObjectArray(0), bytes = w.addByteArray(7);
    w.addEdge(a, a); // self loop
    w.addEdge(a, b);
    w.addEdge(a, b); // duplicate
    w.addDanglingEdge(a);
    // slot 3 of a stays null
    w.addEdge(b, b);
    w.addEdge(b, arr);
    w.setSlot(arr, 0, b);
    w.setSlot(arr, 2, c);
    w.setSlot(arr, 3, GraphHeapDumpWriter.DANGLING);
    w.setSlot(arr, 4, b); // arr[1], arr[5] null
    w.addEdge(c, bytes);
    w.addEdge(c, empty);
    w.addEdge(c, c);
    w.addRoot(a, RootType.UNKNOWN);
    w.addDanglingRoot(RootType.JNI_LOCAL); // root record for a nonexistent object: ignored
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void referenceReferentIsNotAStrongEdge(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(4);
    int r = w.addInstance();
    int weak = w.addWeakReference();
    int onlyViaReferent = w.addInstance(), sub = w.addInstance();
    int viaQueue = w.addInstance(), viaNext = w.addInstance();
    int viaDiscovered = w.addInstance();
    int alsoStrong = w.addInstance(), alsoStrongSub = w.addInstance();
    w.addEdge(r, weak);
    w.setSlot(weak, GraphHeapDumpWriter.REF_REFERENT, onlyViaReferent);
    w.addEdge(onlyViaReferent, sub);
    w.setSlot(weak, GraphHeapDumpWriter.REF_QUEUE, viaQueue);
    w.setSlot(weak, GraphHeapDumpWriter.REF_NEXT, viaNext);
    w.setSlot(weak, GraphHeapDumpWriter.REF_DISCOVERED, viaDiscovered);
    // a second weak ref whose referent is also strongly reachable elsewhere
    int weak2 = w.addWeakReference();
    w.addEdge(r, weak2);
    w.setSlot(weak2, GraphHeapDumpWriter.REF_REFERENT, alsoStrong);
    w.addEdge(r, alsoStrong);
    w.addEdge(alsoStrong, alsoStrongSub);
    // weak reference that is itself a root
    int weak3 = w.addWeakReference();
    w.setSlot(weak3, GraphHeapDumpWriter.REF_REFERENT, alsoStrongSub);
    w.addRoot(weak3, RootType.JNI_GLOBAL);
    w.addRoot(r, RootType.JAVA_FRAME);
    verifyAll(w, dir, 1 << 24);
  }

  /**
   * CURRENT BEHAVIOUR (not necessarily desired): objects reachable only from static fields are NOT
   * dominator-tree roots, so they (and what they hold) are unreachable for dominator purposes: no
   * dominator, and their children sets are empty. Path-to-GC-root, by contrast, does seed from
   * statics. The oracle mirrors both.
   */
  @Test
  void currentBehaviour_staticsAreNotDominatorRootsButArePathRoots(@TempDir Path dir)
      throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    int root = w.addInstance(), shared = w.addInstance();
    int s1 = w.addInstance(), s2 = w.addInstance(), s3 = w.addInstance();
    w.addEdge(root, shared);
    w.addEdge(s1, s2);
    w.addEdge(s2, s3);
    w.addEdge(s2, shared); // static-only subgraph points into the rooted graph
    w.addRoot(root, RootType.JAVA_FRAME);
    w.addStaticRef(s1);
    w.addStaticRef(shared); // also statically referenced: path from it has length 1
    verifyAll(w, dir, 1 << 24);
  }

  @Test
  void allRootTypes(@TempDir Path dir) throws IOException {
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(2);
    RootType[] types = RootType.values();
    int prev = -1;
    for (RootType t : types) {
      int v = w.addInstance();
      w.addRoot(v, t);
      if (prev >= 0) w.addEdge(prev, v);
      prev = v;
    }
    verifyAll(w, dir, 1 << 24);
  }

  // ================================================================ deep chain

  /**
   * Chain 0 -> 1 -> ... -> n-1 with node 0 as the only root. Depth 200k: neither the production DFS
   * nor path reconstruction may overflow the stack. Verified with the closed form instead of the
   * quadratic oracle.
   */
  @Test
  void longChain(@TempDir Path dir) throws IOException {
    int n = 200_000;
    GraphHeapDumpWriter w = new GraphHeapDumpWriter(1);
    for (int i = 0; i < n; i++) w.addInstance();
    for (int i = 0; i + 1 < n; i++) w.addEdge(i, i + 1);
    w.addRoot(0, RootType.JAVA_FRAME);
    Path file = dir.resolve("chain.hprof");
    w.write(file, 1 << 20);

    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      long[] shallow = shallowSizes(dump, w);
      long[] suffix = new long[n + 1];
      for (int i = n - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + shallow[i];

      dump.computeFullDominatorTree(null);
      Mismatches mm = new Mismatches("chain dominators");
      for (int v = 0; v < n; v++) {
        HeapObjectImpl o = obj(dump, v);
        mm.check(v, "idom", v == 0 ? -1 : v - 1, nodeOf(o.getDominator()));
        mm.check(v, "retained", suffix[v], o.getRetainedSize());
        List<HeapObject> kids = dump.getDominatedObjects(o);
        if (v + 1 < n) {
          mm.check(v, "children.size", 1, kids.size());
          if (kids.size() == 1) mm.check(v, "child", v + 1, nodeOf(kids.get(0)));
        } else {
          mm.check(v, "children.size", 0, kids.size());
        }
      }
      mm.assertNone();

      for (int target : new int[] {0, 1, n / 2, n - 1}) {
        List<PathStep> path = dump.findPathToGcRoot(obj(dump, target));
        assertEquals(target + 1, path.size(), "path length to " + target);
        assertEquals(0, nodeOf(path.get(0).object()));
        assertEquals(target, nodeOf(path.get(path.size() - 1).object()));
        for (int i = 0; i < path.size(); i++) assertEquals(i, nodeOf(path.get(i).object()));
      }
    }

    // Approximate (minimum) retained size: every node is referenced once, so each one expands the
    // whole tail. A per-object BFS is quadratic here (2*10^10 visits); the all-objects computation
    // must finish well inside the test timeout and give the suffix sums.
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      long[] shallow = shallowSizes(dump, w);
      long[] suffix = new long[n + 1];
      for (int i = n - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + shallow[i];
      assertTimeoutPreemptively(Duration.ofSeconds(60), () -> dump.computeDominators());
      Mismatches mm = new Mismatches("chain approximate");
      for (int v = 0; v < n; v++) {
        mm.check(v, "approx", suffix[v], obj(dump, v).getRetainedSizeIfAvailable());
      }
      mm.assertNone();
    }
  }

  // ================================================================ verification

  private static void verifyAll(GraphHeapDumpWriter w, Path dir, int segmentBytes)
      throws IOException {
    Path file = dir.resolve("graph.hprof");
    w.write(file, segmentBytes);
    int[][] adj = w.strongAdjacency();

    long[] shallow;
    // --- exact dominators + paths on one parse
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      shallow = shallowSizes(dump, w);
      DominatorOracle oracle = new DominatorOracle(adj, w.rootNodes(), shallow);
      checkDominators(dump, w, oracle);
      checkPaths(dump, w, adj);
    }
    // --- approximate retained sizes on a fresh parse (full tree also sets retained sizes)
    try (HeapDump dump = HeapDumpParser.parse(file, ParserOptions.IN_MEMORY)) {
      checkApproximate(dump, w, adj, shallow);
    }
  }

  private static void checkDominators(HeapDump dump, GraphHeapDumpWriter w, DominatorOracle o) {
    dump.computeFullDominatorTree(null);
    Mismatches mm = new Mismatches("full dominator tree");
    for (int v = 0; v < w.nodeCount(); v++) {
      HeapObjectImpl obj = obj(dump, v);
      mm.check(v, "idom", o.idom[v], nodeOf(obj.getDominator()));
      if (o.reachable[v]) {
        mm.check(v, "retainedSize", o.retained[v], obj.getRetainedSize());
      }
      Set<Integer> expected = new HashSet<>();
      for (int c : o.children(v)) expected.add(c);
      Set<Integer> actual = new HashSet<>();
      for (HeapObject c : dump.getDominatedObjects(obj)) actual.add(nodeOf(c));
      mm.check(v, "dominatedChildren", expected, actual);
    }
    mm.assertNone();
  }

  private static void checkApproximate(
      HeapDump dump, GraphHeapDumpWriter w, int[][] adj, long[] shallow) {
    dump.computeDominators();
    long[] expected = DominatorOracle.approximateRetained(adj, shallow);
    Mismatches mm = new Mismatches("approximate retained size");
    for (int v = 0; v < w.nodeCount(); v++) {
      mm.check(v, "approxRetained", expected[v], obj(dump, v).getRetainedSize());
    }
    mm.assertNone();
  }

  private static void checkPaths(HeapDump dump, GraphHeapDumpWriter w, int[][] adj) {
    int n = w.nodeCount();
    Set<Integer> rootSet = new HashSet<>();
    Set<Integer> seedSet = new HashSet<>();
    for (int r : w.rootNodes()) {
      rootSet.add(r);
      seedSet.add(r);
    }
    for (int s : w.staticTargets()) seedSet.add(s);
    int[] seeds = seedSet.stream().mapToInt(Integer::intValue).toArray();
    int[] dist = DominatorOracle.bfsDistances(adj, seeds);

    int[] targets;
    if (n <= 200) {
      targets = java.util.stream.IntStream.range(0, n).toArray();
    } else {
      Random rnd = new Random(n * 31L + 7);
      Set<Integer> pick = new HashSet<>();
      for (int i = 0; i < 80; i++) pick.add(rnd.nextInt(n));
      for (int v = 0; v < n && pick.size() < 100; v++) if (dist[v] < 0) pick.add(v); // unreachable
      targets = pick.stream().mapToInt(Integer::intValue).toArray();
    }

    Mismatches mm = new Mismatches("path to GC root");
    for (int t : targets) {
      List<PathStep> path = dump.findPathToGcRoot(obj(dump, t));
      if (dist[t] < 0) {
        mm.check(t, "path.size (unreachable)", 0, path.size());
        continue;
      }
      mm.check(t, "path.size", dist[t] + 1, path.size());
      if (path.isEmpty() || path.size() != dist[t] + 1) continue;
      int first = nodeOf(path.get(0).object());
      mm.check(t, "path starts at root/static", true, seedSet.contains(first));
      mm.check(t, "path ends at target", t, nodeOf(path.get(path.size() - 1).object()));
      for (int i = 0; i + 1 < path.size(); i++) {
        int from = nodeOf(path.get(i).object());
        int to = nodeOf(path.get(i + 1).object());
        boolean edge = false;
        if (from >= 0) for (int x : adj[from]) if (x == to) edge = true;
        mm.check(t, "path edge " + from + "->" + to, true, edge);
      }
    }
    mm.assertNone();
  }

  // ================================================================ helpers

  private static HeapObjectImpl obj(HeapDump dump, int node) {
    return (HeapObjectImpl)
        dump.getObjectById(GraphHeapDumpWriter.objectId(node))
            .orElseThrow(() -> new AssertionError("object for node " + node + " not parsed"));
  }

  private static int nodeOf(HeapObject o) {
    return o == null ? -1 : GraphHeapDumpWriter.nodeOfId(o.getId());
  }

  private static long[] shallowSizes(HeapDump dump, GraphHeapDumpWriter w) {
    long[] s = new long[w.nodeCount()];
    for (int v = 0; v < s.length; v++) {
      s[v] = obj(dump, v).getShallowSize();
      assertTrue(s[v] > 0, "shallow size of node " + v);
    }
    return s;
  }

  /** Collects mismatches; reports the count and the first few (lowest node numbers first). */
  private static final class Mismatches {
    private final String what;
    private final List<String> messages = new ArrayList<>();
    private int count;

    Mismatches(String what) {
      this.what = what;
    }

    void check(int node, String field, Object expected, Object actual) {
      if (expected instanceof Set<?> e && actual instanceof Set<?> a) {
        expected = new java.util.TreeSet<>(e);
        actual = new java.util.TreeSet<>(a);
      }
      if (!expected.equals(actual)) {
        count++;
        if (messages.size() < 5) {
          messages.add(
              "node " + node + " " + field + ": expected " + expected + " but was " + actual);
        }
      }
    }

    void assertNone() {
      if (count > 0) {
        fail(what + ": " + count + " mismatch(es); first: " + String.join(" | ", messages));
      }
    }
  }
}
