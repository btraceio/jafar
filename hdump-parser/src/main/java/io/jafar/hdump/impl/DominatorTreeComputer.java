package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the full dominator tree of the strong-reference graph with the Lengauer-Tarjan algorithm
 * ("simple" variant: path compression, no balancing; O(E log N)).
 *
 * <p>The graph is first converted to dense int indexes in compressed-sparse-row form, so the
 * algorithm itself runs on {@code int[]} arrays only: no boxing, no hash lookups per step, and no
 * fixed-point iteration (the earlier iterative Cooper-Harvey-Kennedy implementation needed tens of
 * passes over the heap on cyclic graphs and could stop before converging).
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>Nodes are the heap objects in the given map; edges are {@link
 *       HeapObjectImpl#getStrongOutboundReferenceIds()} that name an object in the map.
 *   <li>A virtual root (index 0) has an edge to every GC root object; GC root objects therefore
 *       have no dominator ({@link HeapObjectImpl#getDominator()} stays {@code null}).
 *   <li>Only objects reachable from the GC roots get a dominator and a retained size; retained size
 *       is the shallow size of the object plus the retained sizes of its dominator-tree children.
 * </ul>
 *
 * <p>Iterative throughout: deep object graphs (long linked lists) cannot overflow the stack.
 * References are decoded only for objects reachable from the GC roots.
 *
 * <p><strong>Memory</strong> (transient, on top of the heap objects; N objects, E strong references
 * between reachable objects): the id index ({@code long[]} + {@code Long2IntOpenHashMap}, about 8N
 * + 12N..24N bytes), the successor CSR built during the DFS (4N + 4E, released before the
 * Lengauer-Tarjan step), the predecessor CSR (4N + 4E) and eleven {@code int[N]} working arrays
 * (44N). Peak is roughly 70N + 4E bytes plus the id index, e.g. about 1.2 GB for 10M objects with 3
 * references each. The {@link Result} kept for {@code getDominatedObjects} holds the id index and
 * the children CSR (about 8N + 12N..24N + 8N bytes).
 */
public final class DominatorTreeComputer {

  private static final Logger LOG = LoggerFactory.getLogger(DominatorTreeComputer.class);

  private static final long[] EMPTY_IDS = new long[0];

  private DominatorTreeComputer() {}

  /** Immediate dominator-tree children by object id. */
  interface Children {
    /** Ids of the objects immediately dominated by {@code dominatorId} (empty if none). */
    long[] childIds(long dominatorId);
  }

  /**
   * INDEXED mode: the children CSR over nodes {@code id32 + 1}; ids map through the dump's
   * address/id32 maps (no copy of them).
   */
  static final class IndexedChildren implements Children {
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap id32;
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap address;
    private final int[] childStart;
    private final int[] children;

    IndexedChildren(
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap id32,
        it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap address,
        int[] childStart,
        int[] children) {
      this.id32 = id32;
      this.address = address;
      this.childStart = childStart;
      this.children = children;
    }

    @Override
    public long[] childIds(long dominatorId) {
      int i = id32.get(dominatorId);
      if (i < 0) return EMPTY_IDS;
      int v = i + 1;
      int from = childStart[v];
      int to = childStart[v + 1];
      if (from == to) return EMPTY_IDS;
      long[] out = new long[to - from];
      for (int k = from; k < to; k++) out[k - from] = address.get(children[k] - 1);
      return out;
    }
  }

  /**
   * Immediate dominator-tree children, by object id. Children are stored per dominator as a
   * contiguous slice of one {@code int[]} (CSR), indexing {@link #ids}.
   */
  public static final class Result implements Children {
    private final long[] ids; // node index -> object id (index 0 = virtual root, unused)
    private final Long2IntOpenHashMap indexOf; // object id -> node index
    private final int[] childStart; // node index -> start in children; length nodes + 1
    private final int[] children; // node indexes

    Result(long[] ids, Long2IntOpenHashMap indexOf, int[] childStart, int[] children) {
      this.ids = ids;
      this.indexOf = indexOf;
      this.childStart = childStart;
      this.children = children;
    }

    /** Ids of the objects immediately dominated by {@code dominatorId} (empty if none). */
    @Override
    public long[] childIds(long dominatorId) {
      int v = indexOf.get(dominatorId);
      if (v <= 0) {
        return EMPTY_IDS;
      }
      int from = childStart[v];
      int to = childStart[v + 1];
      if (from == to) {
        return EMPTY_IDS;
      }
      long[] out = new long[to - from];
      for (int i = from; i < to; i++) {
        out[i - from] = ids[children[i]];
      }
      return out;
    }
  }

  /**
   * Computes the full dominator tree for all objects in {@code objectsById}.
   *
   * <p>Sets the immediate dominator and the exact retained size of each reachable object.
   *
   * @param dump the heap dump
   * @param objectsById map of object IDs to objects (the graph's node set)
   * @param gcRoots list of GC roots
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   * @return the dominator-tree children, for {@code getDominatedObjects}
   */
  public static Result computeFull(
      HeapDumpImpl dump,
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<GcRootImpl> gcRoots,
      ProgressCallback progressCallback) {

    long startTime = System.currentTimeMillis();
    final int n = objectsById.size() + 1; // + virtual root at index 0
    LOG.info("Computing full dominator tree for {} objects...", n - 1);
    report(progressCallback, 0.0, "Indexing objects...");

    // Dense node indexes
    HeapObjectImpl[] nodes = new HeapObjectImpl[n];
    long[] ids = new long[n];
    Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap(n);
    indexOf.defaultReturnValue(-1);
    {
      int i = 1;
      for (HeapObjectImpl obj : objectsById.values()) {
        nodes[i] = obj;
        ids[i] = obj.getId();
        indexOf.put(obj.getId(), i);
        i++;
      }
    }
    int[] roots = rootNodes(gcRoots, indexOf::get, n);

    // References are decoded only for objects the DFS reaches, so unreachable objects do not get
    // their reference arrays decoded and cached.
    Tree tree =
        compute(
            n,
            roots,
            (w, out) -> {
              for (long ref : nodes[w].getStrongOutboundReferenceIds()) {
                int x = indexOf.get(ref);
                if (x > 0) out.add(x);
              }
            },
            v -> nodes[v].getShallowSize(),
            progressCallback);

    for (int w = tree.reached - 1; w >= 1; w--) {
      HeapObjectImpl obj = nodes[tree.vertex[w]];
      obj.setRetainedSize(tree.retained[w]);
      int d = tree.idom[w];
      obj.setDominator(d != 0 ? nodes[tree.vertex[d]] : null); // null: root or several roots
    }

    LOG.info(
        "Dominator tree computation completed in {}ms ({} reachable objects)",
        System.currentTimeMillis() - startTime,
        tree.reached - 1);
    report(progressCallback, 1.0, "Dominator tree computation complete");
    return new Result(ids, indexOf, tree.childStart, tree.children);
  }

  /** Appends the successor nodes of {@code node} (all &gt; 0) to {@code out}, in order. */
  @FunctionalInterface
  interface Successors {
    void append(int node, IntBuffer out);
  }

  /** The dominator tree over nodes {@code 1..n-1} (node 0 is the virtual root). */
  static final class Tree {
    int reached; // reached nodes, virtual root included
    int[] vertex; // preorder -> node
    int[] idom; // preorder -> preorder of the immediate dominator (0: the virtual root)
    long[] retained; // preorder -> retained size
    int[] childStart; // node -> start in children; length n + 1
    int[] children; // dominator-tree children (nodes), in preorder within each slice
  }

  /** The GC root objects as nodes (&gt; 0), deduplicated, in first-seen order. */
  static int[] rootNodes(
      List<GcRootImpl> gcRoots, java.util.function.LongToIntFunction nodeOf, int n) {
    boolean[] isRoot = new boolean[n];
    int[] out = new int[gcRoots.size()];
    int k = 0;
    for (GcRoot root : gcRoots) {
      int r = nodeOf.applyAsInt(root.getObjectId());
      if (r > 0 && !isRoot[r]) {
        isRoot[r] = true;
        out[k++] = r;
      }
    }
    return java.util.Arrays.copyOf(out, k);
  }

  /**
   * DFS from the virtual root (whose successors are {@code roots}), Lengauer-Tarjan, retained sizes
   * bottom-up and the children CSR — independent of where nodes and edges come from.
   */
  static Tree compute(
      int n,
      int[] roots,
      Successors successors,
      java.util.function.IntUnaryOperator shallow,
      ProgressCallback progressCallback) {
    report(progressCallback, 0.1, "Depth-first traversal...");
    Traversal t = depthFirst(n, roots, successors);
    final int reached = t.count;
    int[] vertex = t.vertex;
    int[] parent = t.parent;

    report(progressCallback, 0.4, "Building predecessor graph...");
    int[] predStart = new int[reached + 1];
    int[] pred = buildPredecessors(t.succStart, t.succ, reached, predStart);
    t = null; // successors no longer needed: release before the LT arrays are allocated

    report(progressCallback, 0.5, "Computing immediate dominators...");
    int[] idom = lengauerTarjan(reached, parent, predStart, pred); // preorder numbers
    pred = null;
    predStart = null;

    report(progressCallback, 0.8, "Computing exact retained sizes...");
    long[] retained = new long[reached];
    for (int w = reached - 1; w >= 1; w--) {
      long r = retained[w] + shallow.applyAsInt(vertex[w]);
      retained[w] = r;
      int d = idom[w];
      if (d != 0) {
        retained[d] += r;
      }
    }

    // Children CSR indexed by node (not preorder) so lookups go id -> node -> slice.
    int[] childStart = new int[n + 1];
    for (int w = 1; w < reached; w++) {
      int d = idom[w];
      if (d != 0) {
        childStart[vertex[d] + 1]++;
      }
    }
    for (int v = 0; v < n; v++) {
      childStart[v + 1] += childStart[v];
    }
    int[] children = new int[childStart[n]];
    int[] fill = new int[n];
    for (int w = 1; w < reached; w++) {
      int d = idom[w];
      if (d != 0) {
        int dv = vertex[d];
        children[childStart[dv] + fill[dv]++] = vertex[w];
      }
    }

    Tree tree = new Tree();
    tree.reached = reached;
    tree.vertex = vertex;
    tree.idom = idom;
    tree.retained = retained;
    tree.childStart = childStart;
    tree.children = children;
    return tree;
  }

  private static void report(ProgressCallback cb, double progress, String message) {
    if (cb != null) {
      cb.onProgress(progress, message);
    }
  }

  /** DFS result: preorder numbering and the successors of each reached node, by preorder. */
  private static final class Traversal {
    int count; // reached nodes, including the virtual root
    int[] vertex; // preorder -> node
    int[] parent; // preorder -> parent's preorder
    int[] succStart; // preorder -> start in succ; length count + 1
    int[] succ; // successor nodes (node indexes, not preorder)
  }

  /**
   * Iterative DFS from the virtual root (node 0, whose successors are the GC root objects,
   * deduplicated, in first-seen order). A node's successor list is decoded when the node is
   * discovered and appended to the CSR, so the CSR rows are in preorder.
   */
  private static Traversal depthFirst(int n, int[] roots, Successors successors) {
    int[] dfn = new int[n];
    int[] vertex = new int[n];
    int[] parent = new int[n];
    int[] succStart = new int[n + 1];
    IntBuffer succ = new IntBuffer(Math.max(16, n * 2));

    // Virtual root, preorder 0.
    int count = 1;
    dfn[0] = 1;
    succStart[0] = 0;
    for (int r : roots) {
      succ.add(r);
    }
    succStart[1] = succ.size;

    int[] stackPre = new int[n]; // preorder of the frame's node
    int[] stackEdge = new int[n]; // next index into succ
    int sp = 0;
    stackPre[0] = 0;
    stackEdge[0] = 0;
    sp = 1;
    while (sp > 0) {
      int top = sp - 1;
      int a = stackPre[top];
      int e = stackEdge[top];
      int end = succStart[a + 1];
      int[] sa = succ.data;
      while (e < end && dfn[sa[e]] != 0) {
        e++;
      }
      if (e == end) {
        sp--;
        continue;
      }
      stackEdge[top] = e + 1;
      int w = sa[e];
      int b = count++;
      dfn[w] = b + 1;
      vertex[b] = w;
      parent[b] = a;
      // Decode w's successors now: its row is the next one in the CSR.
      successors.append(w, succ);
      succStart[b + 1] = succ.size;
      stackPre[sp] = b;
      stackEdge[sp] = succStart[b];
      sp++;
    }

    Traversal t = new Traversal();
    t.count = count;
    t.vertex = vertex;
    t.parent = parent;
    t.succStart = succStart;
    t.succ = succ.data;
    // Translate successor node indexes to preorder numbers in place (all are reached).
    int[] sa = succ.data;
    for (int i = 0, m = succ.size; i < m; i++) {
      sa[i] = dfn[sa[i]] - 1;
    }
    return t;
  }

  /** Growable int array. */
  static final class IntBuffer {
    int[] data;
    int size;

    IntBuffer(int capacity) {
      data = new int[capacity];
    }

    void add(int v) {
      if (size == data.length) {
        long grown = Math.max(16L, (long) size + (size >> 1));
        if (grown > Integer.MAX_VALUE - 8) {
          throw new IllegalStateException("Too many references for dominator computation");
        }
        data = java.util.Arrays.copyOf(data, (int) grown);
      }
      data[size++] = v;
    }
  }

  /** Predecessor lists over preorder numbers, from successor rows that are already in preorder. */
  private static int[] buildPredecessors(
      int[] succStart, int[] succ, int reached, int[] predStart) {
    for (int a = 0; a < reached; a++) {
      for (int e = succStart[a], end = succStart[a + 1]; e < end; e++) {
        predStart[succ[e] + 1]++;
      }
    }
    for (int i = 0; i < reached; i++) {
      predStart[i + 1] += predStart[i];
    }
    int[] pred = new int[predStart[reached]];
    int[] fill = new int[reached];
    for (int a = 0; a < reached; a++) {
      for (int e = succStart[a], end = succStart[a + 1]; e < end; e++) {
        int b = succ[e];
        pred[predStart[b] + fill[b]++] = a;
      }
    }
    return pred;
  }

  /**
   * Lengauer-Tarjan over preorder numbers {@code 0..count-1} (0 = entry). Returns {@code idom}
   * indexed by preorder number ({@code idom[0] = 0}).
   */
  static int[] lengauerTarjan(int count, int[] parent, int[] predStart, int[] pred) {
    int[] semi = new int[count];
    int[] idom = new int[count];
    int[] ancestor = new int[count];
    int[] label = new int[count];
    int[] bucketHead = new int[count];
    int[] bucketNext = new int[count];
    int[] stack = new int[count];
    for (int v = 0; v < count; v++) {
      semi[v] = v;
      label[v] = v;
      ancestor[v] = -1;
      bucketHead[v] = -1;
    }

    for (int w = count - 1; w >= 1; w--) {
      // Step 2: semidominator
      int s = semi[w];
      for (int i = predStart[w], end = predStart[w + 1]; i < end; i++) {
        int v = pred[i];
        int u = eval(v, ancestor, label, semi, stack);
        if (semi[u] < s) {
          s = semi[u];
        }
      }
      semi[w] = s;
      bucketNext[w] = bucketHead[s];
      bucketHead[s] = w;
      int p = parent[w];
      ancestor[w] = p; // link(parent, w)

      // Step 3: implicit idoms for the parent's bucket
      for (int v = bucketHead[p]; v != -1; v = bucketNext[v]) {
        int u = eval(v, ancestor, label, semi, stack);
        idom[v] = semi[u] < semi[v] ? u : p;
      }
      bucketHead[p] = -1;
    }

    // Step 4: explicit idoms, in preorder
    for (int w = 1; w < count; w++) {
      if (idom[w] != semi[w]) {
        idom[w] = idom[idom[w]];
      }
    }
    idom[0] = 0;
    return idom;
  }

  /**
   * EVAL with path compression, iterative. Returns the vertex with minimal semidominator on the
   * forest path from {@code v} (exclusive of the forest root).
   */
  private static int eval(int v, int[] ancestor, int[] label, int[] semi, int[] stack) {
    if (ancestor[v] == -1) {
      return v;
    }
    // Collect the path v -> ... while the ancestor has its own ancestor.
    int sp = 0;
    int x = v;
    while (ancestor[ancestor[x]] != -1) {
      stack[sp++] = x;
      x = ancestor[x];
    }
    // Compress from the top of the path down.
    while (sp > 0) {
      int y = stack[--sp];
      int a = ancestor[y];
      if (semi[label[a]] < semi[label[y]]) {
        label[y] = label[a];
      }
      ancestor[y] = ancestor[a];
    }
    return label[v];
  }

  /** Callback interface for progress updates during computation. */
  @FunctionalInterface
  public interface ProgressCallback {
    /**
     * Called periodically during computation.
     *
     * @param progress value between 0.0 and 1.0
     * @param message description of current step
     */
    void onProgress(double progress, String message);
  }
}
