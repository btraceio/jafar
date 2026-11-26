package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.index.InboundCountReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes approximate retained sizes using BFS traversal without full dominator tree construction.
 *
 * <p>This approximation is based on Eclipse MAT's "minimum retained size" approach:
 *
 * <ul>
 *   <li>Starts BFS from a target object
 *   <li>Stops expanding at objects with multiple inbound references (shared objects)
 *   <li>Sums shallow sizes of exclusively reachable objects
 *   <li>Under-estimates: everything behind a shared object is left out
 * </ul>
 *
 * <p><strong>Performance:</strong> {@link #computeMinRetainedSize} is a BFS per object, O(V + E) of
 * the visited part. In IN_MEMORY mode {@code computeAll} computes the same values for all objects
 * at once in O((N + E) log E) ({@link MinRetainedSizes}); a per-object BFS for all objects is
 * quadratic on singly linked chains. The INDEXED (streaming) mode still runs one BFS per object.
 *
 * <p><strong>Accuracy:</strong> Conservative under-estimate. Good enough for leak detection where
 * order-of-magnitude accuracy is sufficient.
 */
public final class ApproximateRetainedSizeComputer {

  private static final Logger LOG = LoggerFactory.getLogger(ApproximateRetainedSizeComputer.class);

  private ApproximateRetainedSizeComputer() {}

  /** How INDEXED mode computes; {@link #AUTO} unless a test pins one. */
  enum IndexedStrategy {
    /** All at once if the working set fits in free heap, else {@link #BUDGETED}. */
    AUTO,
    /** {@link MinRetainedSizes} on the whole graph. */
    ALL_AT_ONCE,
    /** Per-object BFS for small expanded sets, all at once for the subgraph of the large ones. */
    BUDGETED,
    /** Per-object BFS for every object (quadratic on long once-referenced chains). */
    PER_OBJECT
  }

  /**
   * How one dump computes INDEXED retained sizes. Held per dump ({@code HeapDumpImpl}), never in
   * static state: the strategy defaults to {@code
   * -Djafar.hdump.retained.strategy=AUTO|ALL_AT_ONCE|BUDGETED|PER_OBJECT}, read at compute time;
   * tests pin the strategy, shrink the budget or replace the free-heap figure on the dump they
   * open.
   */
  static final class RetainedSizeOptions {
    /** Pinned strategy, or null for the system property (default AUTO). */
    volatile IndexedStrategy strategy;

    /** Budget, in BFS steps, below which an object keeps its per-object BFS. */
    volatile int budget = 256;

    /** Free heap in bytes. */
    volatile java.util.function.LongSupplier availableHeap =
        () -> {
          Runtime rt = Runtime.getRuntime();
          return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        };

    /** Times an object's subgraph did not fit and it fell back to one BFS. */
    final java.util.concurrent.atomic.AtomicInteger subgraphFallbacks =
        new java.util.concurrent.atomic.AtomicInteger();

    /** The strategy: the pinned one, else {@code jafar.hdump.retained.strategy}, else AUTO. */
    IndexedStrategy strategy() {
      IndexedStrategy pinned = strategy;
      if (pinned != null) return pinned;
      String value = System.getProperty("jafar.hdump.retained.strategy");
      if (value == null || value.isBlank()) return IndexedStrategy.AUTO;
      try {
        return IndexedStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        LOG.warn(
            "Ignoring -Djafar.hdump.retained.strategy={} (expected one of {}); using AUTO",
            value,
            java.util.Arrays.toString(IndexedStrategy.values()));
        return IndexedStrategy.AUTO;
      }
    }

    boolean fits(long bytes) {
      return bytes < availableHeap.getAsLong() / 2;
    }
  }

  /**
   * Transient heap {@link MinRetainedSizes} needs for {@code n} nodes and {@code e} edges (its
   * documented peak, 75n + 4e + 28f with f &le; e, rounded up), plus the arrays filled here.
   */
  static long allAtOnceBytes(long n, long e) {
    return 88L * n + 32L * e;
  }

  /**
   * INDEXED mode: {@link #computeMinRetainedSize} for every id32 in order, written to {@code
   * writer}, without materialising objects. Each object's value is that of the per-object BFS over
   * loaded objects: the graph is the on-disk strong references ({@link StrongRefsIndex}, via {@link
   * IndexedRefs}), inbound counts come from {@code inbound.idx}, shallow sizes from {@code
   * objects.idx}. Class entries get 0, as before (a loaded class entry has shallow size 0 and no
   * readable references).
   *
   * <p>A per-object BFS is quadratic on long chains of once-referenced objects (each expands the
   * whole tail), so:
   *
   * <ul>
   *   <li>When the whole computation fits in free heap ({@link #allAtOnceBytes}, under half of what
   *       is available), {@link MinRetainedSizes} computes every value at once.
   *   <li>Otherwise ({@link IndexedStrategy#BUDGETED}): one pass builds the once-referenced forest
   *       ({@code fparent}) and, bottom-up, each node's subtree work (nodes plus their references:
   *       what its BFS costs) capped at a budget; nodes on an only-referrer cycle never complete
   *       and count as large. Objects whose expanded set is within the budget get their per-object
   *       BFS (bounded by the budget plus its frontier). The large ones get a placeholder; one
   *       multi-source BFS collects everything they expand into, {@link MinRetainedSizes} runs on
   *       that subgraph alone (nodes keep their global inbound counts), and the placeholders are
   *       patched. Transient heap: 16 bytes per id32 for the forest pass, plus {@link
   *       #allAtOnceBytes} of the large objects' subgraph; if even that does not fit, those objects
   *       fall back to the per-object BFS, with a warning.
   * </ul>
   */
  static void computeAllIndexed(
      HeapDumpImpl dump,
      io.jafar.hdump.index.RetainedSizeWriter writer,
      ProgressCallback progressCallback) {
    InboundCountReader inbound = dump.getInboundCountReader();
    if (inbound == null) {
      throw new IllegalStateException(
          "Streaming mode requires persistent inbound index. Call ensureInboundIndexBuilt() first.");
    }
    if (progressCallback != null) {
      progressCallback.onProgress(0.3, "Building reference graph index...");
    }
    StrongRefsIndex stored = dump.strongRefsIndex();
    IndexedRefs refs = new IndexedRefs(dump, stored);
    final int n = refs.size();
    long edges = stored != null ? stored.start(n) : -1;
    RetainedSizeOptions options = dump.retainedSizeOptions();
    IndexedStrategy strategy = options.strategy();
    boolean canAllAtOnce = stored != null && edges < Integer.MAX_VALUE - 8;
    if (strategy == IndexedStrategy.AUTO) {
      strategy =
          canAllAtOnce && options.fits(allAtOnceBytes(n, edges))
              ? IndexedStrategy.ALL_AT_ONCE
              : IndexedStrategy.BUDGETED;
    }
    if (strategy == IndexedStrategy.ALL_AT_ONCE && !canAllAtOnce) {
      strategy = IndexedStrategy.BUDGETED;
    }
    LOG.debug(
        "Indexed retained sizes: {} ({} entries, {} references)",
        strategy,
        n,
        edges >= 0 ? edges : "unknown");
    try {
      switch (strategy) {
        case ALL_AT_ONCE -> allAtOnce(refs, stored, inbound, n, (int) edges, writer);
        case BUDGETED -> budgeted(refs, inbound, n, options, writer, progressCallback);
        default -> perObject(refs, inbound, n, writer, progressCallback);
      }
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to write retained size to index", e);
    }
    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Approximate retained size computation complete");
    }
    LOG.info("Completed indexed retained size computation for {} entries ({})", n, strategy);
  }

  private static void allAtOnce(
      IndexedRefs refs,
      StrongRefsIndex stored,
      InboundCountReader inbound,
      int n,
      int edges,
      io.jafar.hdump.index.RetainedSizeWriter writer)
      throws java.io.IOException {
    int[] ss = new int[n + 1];
    int[] su = new int[edges];
    int[] shallow = new int[n];
    int[] inb = new int[n];
    for (int v = 0; v < n; v++) {
      ss[v + 1] = (int) stored.start(v + 1);
      shallow[v] = refs.shallowSize(v);
      inb[v] = inbound.getInboundCount(v);
    }
    for (int i = 0; i < edges; i++) {
      su[i] = stored.ref(i);
    }
    long[] result = MinRetainedSizes.compute(n, ss, su, shallow, inb);
    for (int t = 0; t < n; t++) {
      writer.writeEntry(t, refs.isClassEntry(t) ? 0 : result[t]);
    }
  }

  private static void perObject(
      IndexedRefs refs,
      InboundCountReader inbound,
      int n,
      io.jafar.hdump.index.RetainedSizeWriter writer,
      ProgressCallback cb)
      throws java.io.IOException {
    Bfs bfs = new Bfs(refs, inbound, n);
    for (int t = 0; t < n; t++) {
      writer.writeEntry(t, refs.isClassEntry(t) ? 0 : bfs.size(t));
      report(cb, t, n);
    }
  }

  private static void budgeted(
      IndexedRefs refs,
      InboundCountReader inbound,
      int n,
      RetainedSizeOptions options,
      io.jafar.hdump.index.RetainedSizeWriter writer,
      ProgressCallback cb)
      throws java.io.IOException {
    java.util.BitSet large = largeObjects(refs, inbound, n, options.budget);
    Bfs bfs = new Bfs(refs, inbound, n);
    // The large objects first, so retained.idx is only ever written complete and in order.
    it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap largeValues =
        largeValues(refs, inbound, n, large, bfs, options);
    for (int t = 0; t < n; t++) {
      long value;
      if (refs.isClassEntry(t)) {
        value = 0;
      } else if (large.get(t)) {
        value = largeValues.get(t);
      } else {
        value = bfs.size(t);
      }
      writer.writeEntry(t, value);
      report(cb, t, n);
    }
  }

  /**
   * The objects whose per-object BFS would exceed {@code budget} steps: referenced at most once,
   * with a once-referenced subtree whose work (each node 1 + its references) exceeds the budget, or
   * on an only-referrer cycle (their subtree never completes). Transient: 16 bytes per id32.
   */
  private static java.util.BitSet largeObjects(
      IndexedRefs refs, InboundCountReader inbound, int n, int budget) {
    final int cap = budget == Integer.MAX_VALUE ? budget : budget + 1;
    // Once-referenced forest: fparent[c] = c's only referrer, when that referrer is expandable;
    // work[v] starts as what v itself costs a BFS (1 + its references), capped.
    int[] fparent = new int[n];
    int[] work = new int[n];
    java.util.Arrays.fill(fparent, -1);
    for (int v = 0; v < n; v++) {
      if (inbound.getInboundCount(v) > 1) continue;
      int k = refs.strongRefs(v);
      work[v] = (int) Math.min(cap, 1L + k);
      int[] r = refs.refs();
      for (int i = 0; i < k; i++) {
        int c = r[i];
        if (inbound.getInboundCount(c) == 1) fparent[c] = v;
      }
    }
    // Subtree work, bottom-up (Kahn over fparent); only-referrer cycles never complete.
    int[] pending = new int[n];
    for (int c = 0; c < n; c++) {
      if (fparent[c] >= 0) pending[fparent[c]]++;
    }
    int[] queue = new int[n];
    int tail = 0;
    for (int v = 0; v < n; v++) {
      if (inbound.getInboundCount(v) <= 1 && pending[v] == 0) queue[tail++] = v;
    }
    for (int head = 0; head < tail; head++) {
      int u = queue[head];
      int p = fparent[u];
      if (p >= 0) {
        work[p] = (int) Math.min(cap, (long) work[p] + work[u]);
        if (--pending[p] == 0) queue[tail++] = p;
      }
    }
    java.util.BitSet large = new java.util.BitSet(n);
    for (int t = 0; t < n; t++) {
      // pending[t] > 0: t is an only-referrer cycle member
      if (inbound.getInboundCount(t) <= 1 && (pending[t] > 0 || work[t] > budget)) large.set(t);
    }
    return large;
  }

  /**
   * The values of the large objects, all at once on the subgraphs they expand into. Sources are
   * taken in id order into a batch (one multi-source BFS: expanded nodes and their frontier, so the
   * subgraph is closed) until the batch would no longer fit in the heap; {@link MinRetainedSizes}
   * then computes the batch (nodes keep their global inbound counts) and every large object in it
   * gets its value — also those reached as part of another's subtree, which are then skipped as
   * sources. Only one source whose subgraph alone does not fit falls back to one BFS, with a
   * warning.
   */
  private static it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap largeValues(
      IndexedRefs refs,
      InboundCountReader inbound,
      int n,
      java.util.BitSet large,
      Bfs bfs,
      RetainedSizeOptions options) {
    it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap values =
        new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(Math.max(16, large.cardinality()));
    int[] local = new int[n]; // id32 -> index in the current batch + 1
    it.unimi.dsi.fastutil.ints.IntArrayList nodes = new it.unimi.dsi.fastutil.ints.IntArrayList();
    long[] edges = {0};
    for (int t = large.nextSetBit(0); t >= 0; t = large.nextSetBit(t + 1)) {
      if (values.containsKey(t)) continue; // computed as part of an earlier source's subtree
      int mark = nodes.size();
      long markEdges = edges[0];
      collect(refs, inbound, t, local, nodes, edges);
      if (edges[0] < Integer.MAX_VALUE - 8
          && options.fits(allAtOnceBytes(nodes.size(), edges[0]))) {
        continue;
      }
      // t does not fit with the batch: compute the batch without t, then t on its own
      for (int i = mark; i < nodes.size(); i++) local[nodes.getInt(i)] = 0;
      nodes.size(mark);
      edges[0] = markEdges;
      computeBatch(refs, inbound, large, local, nodes, values);
      collect(refs, inbound, t, local, nodes, edges);
      if (edges[0] < Integer.MAX_VALUE - 8
          && options.fits(allAtOnceBytes(nodes.size(), edges[0]))) {
        continue;
      }
      LOG.warn(
          "Object id32={} expands into {} objects ({} references), too many for the heap; "
              + "computing it with one BFS",
          t,
          nodes.size(),
          edges[0]);
      options.subgraphFallbacks.incrementAndGet();
      for (int i = 0; i < nodes.size(); i++) local[nodes.getInt(i)] = 0;
      nodes.clear();
      edges[0] = 0;
      values.put(t, bfs.size(t));
    }
    computeBatch(refs, inbound, large, local, nodes, values);
    return values;
  }

  /** Adds what {@code t} expands into (it, the expanded nodes, their frontier) to the batch. */
  private static void collect(
      IndexedRefs refs,
      InboundCountReader inbound,
      int t,
      int[] local,
      it.unimi.dsi.fastutil.ints.IntArrayList nodes,
      long[] edges) {
    if (local[t] != 0) return; // already in this batch (inside another source's subtree)
    int head = nodes.size();
    nodes.add(t);
    local[t] = nodes.size();
    for (; head < nodes.size(); head++) {
      int u = nodes.getInt(head);
      if (inbound.getInboundCount(u) > 1) continue; // frontier: not expanded
      int k = refs.strongRefs(u);
      edges[0] += k;
      int[] r = refs.refs();
      for (int j = 0; j < k; j++) {
        int c = r[j];
        if (local[c] == 0) {
          nodes.add(c);
          local[c] = nodes.size();
        }
      }
    }
  }

  /** {@link MinRetainedSizes} on the batch; records every large object in it; clears the batch. */
  private static void computeBatch(
      IndexedRefs refs,
      InboundCountReader inbound,
      java.util.BitSet large,
      int[] local,
      it.unimi.dsi.fastutil.ints.IntArrayList nodes,
      it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap values) {
    int m = nodes.size();
    if (m == 0) return;
    int[] ss = new int[m + 1];
    int[] shallow = new int[m];
    int[] inb = new int[m];
    it.unimi.dsi.fastutil.ints.IntArrayList su = new it.unimi.dsi.fastutil.ints.IntArrayList();
    for (int x = 0; x < m; x++) {
      int u = nodes.getInt(x);
      shallow[x] = refs.shallowSize(u);
      inb[x] = inbound.getInboundCount(u);
      if (inb[x] <= 1) {
        int k = refs.strongRefs(u);
        int[] r = refs.refs();
        for (int j = 0; j < k; j++) su.add(local[r[j]] - 1);
      }
      ss[x + 1] = su.size();
    }
    long[] result = MinRetainedSizes.compute(m, ss, su.toIntArray(), shallow, inb);
    for (int x = 0; x < m; x++) {
      int u = nodes.getInt(x);
      if (large.get(u)) values.put(u, result[x]);
      local[u] = 0;
    }
    nodes.clear();
  }

  private static void report(ProgressCallback cb, int t, int n) {
    if (cb != null && (t + 1) % Math.max(n / 100, 50_000) == 0) {
      double progress = 0.3 + 0.7 * ((t + 1) / (double) n);
      cb.onProgress(
          progress,
          String.format("Computing approximate retained sizes (%d%%)...", (int) (progress * 100)));
    }
  }

  /** The per-object BFS on the INDEXED graph, with one reusable visited-stamp array. */
  private static final class Bfs {
    private final IndexedRefs refs;
    private final InboundCountReader inbound;
    private final int[] mark;
    private int[] queue = new int[16];
    private int stamp;

    Bfs(IndexedRefs refs, InboundCountReader inbound, int n) {
      this.refs = refs;
      this.inbound = inbound;
      this.mark = new int[n];
    }

    long size(int t) {
      if (++stamp == 0) {
        java.util.Arrays.fill(mark, 0);
        stamp = 1;
      }
      int head = 0;
      int tail = 0;
      queue[tail++] = t;
      mark[t] = stamp;
      long total = 0;
      while (head < tail) {
        int u = queue[head++];
        total += refs.shallowSize(u);
        if (inbound.getInboundCount(u) > 1) {
          continue; // shared: not expanded
        }
        int k = refs.strongRefs(u);
        int[] r = refs.refs();
        for (int i = 0; i < k; i++) {
          int c = r[i];
          if (mark[c] != stamp) {
            mark[c] = stamp;
            if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, tail * 2);
            queue[tail++] = c;
          }
        }
      }
      if (queue.length > 1 << 16 && tail < 1 << 12) {
        queue = new int[16]; // do not keep one huge BFS's queue
      }
      return total;
    }
  }

  /**
   * Computes approximate retained sizes for all objects in the heap dump (in-memory mode).
   *
   * <p>This method adapts to both in-memory and indexed parsing modes:
   *
   * <ul>
   *   <li><strong>In-memory mode:</strong> Builds inbound counts by scanning objectsById map
   *   <li><strong>Indexed mode:</strong> Uses persistent InboundCountReader from disk
   * </ul>
   *
   * <p>The retained sizes are set directly on HeapObjectImpl instances via package-private setters.
   *
   * @param dump the heap dump (provides access to inbound count reader if in indexed mode)
   * @param objectsById map of object IDs to objects
   * @param gcRoots list of GC roots
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   */
  public static void computeAll(
      HeapDumpImpl dump,
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<GcRootImpl> gcRoots,
      ProgressCallback progressCallback) {

    int totalObjects = objectsById.size();

    // Check if we're in indexed mode (inbound count reader will be non-null after
    // ensureInboundIndexBuilt)
    InboundCountReader inboundCountReader = dump.getInboundCountReader();
    InboundCountAccessor inboundCounts;

    if (inboundCountReader != null) {
      // Indexed mode: use persistent inbound index
      LOG.debug("Using persistent inbound index for {} objects", totalObjects);
      inboundCounts = new IndexedInboundCountAccessor(inboundCountReader, dump.getAddressToId32());

      // Skip progress for index building since it's already done
      if (progressCallback != null) {
        progressCallback.onProgress(0.3, "Computing approximate retained sizes...");
      }
    } else {
      // In-memory mode: all objects at once on the int graph (same definition, near-linear).
      if (progressCallback != null) {
        progressCallback.onProgress(0.0, "Building reference graph...");
      }
      // Reuse the path-query graph when one exists (same nodes and edges) instead of holding two.
      HeapGraph cached = dump.cachedPathGraph();
      HeapGraph graph =
          cached != null && cached.size() == objectsById.size()
              ? cached
              : HeapGraph.build(objectsById);
      if (progressCallback != null) {
        progressCallback.onProgress(0.3, "Computing approximate retained sizes...");
      }
      long[] sizes = MinRetainedSizes.compute(graph);
      for (int v = 0; v < sizes.length; v++) {
        graph.nodes[v].setRetainedSize(sizes[v]);
      }
      if (progressCallback != null) {
        progressCallback.onProgress(1.0, "Approximate retained size computation complete");
      }
      LOG.debug("Completed approximate retained size computation for {} objects", sizes.length);
      return;
    }

    LOG.debug("Computing approximate retained sizes...");

    int computed = 0;
    int lastReportedPercent = 0;

    for (HeapObjectImpl obj : objectsById.values()) {
      long approxRetained = computeMinRetainedSize(obj, inboundCounts);
      obj.setRetainedSize(approxRetained);
      computed++;

      // Report progress every 1% or every 50K objects (whichever is less frequent)
      if (progressCallback != null && computed % Math.max(totalObjects / 100, 50000) == 0) {
        double progress = 0.3 + (0.7 * (computed / (double) totalObjects));
        int percentComplete = (int) (progress * 100);
        if (percentComplete > lastReportedPercent) {
          progressCallback.onProgress(
              progress,
              String.format("Computing approximate retained sizes (%d%%)...", percentComplete));
          lastReportedPercent = percentComplete;
        }
      }

      if (computed % 100000 == 0) {
        LOG.debug("Computed {} / {} retained sizes", computed, totalObjects);
      }
    }

    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Approximate retained size computation complete");
    }
    LOG.debug("Completed approximate retained size computation for {} objects", computed);
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

  /**
   * Computes the minimum retained size for a single object.
   *
   * <p>This is a conservative under-estimate of the true retained size. It counts only objects that
   * are exclusively owned (single inbound reference) or have no paths to GC roots except through
   * the target object.
   *
   * @param target the object to compute retained size for
   * @param inboundCounts accessor for inbound reference counts
   * @return approximate retained size in bytes
   */
  public static long computeMinRetainedSize(HeapObject target, InboundCountAccessor inboundCounts) {
    if (target == null) {
      return 0;
    }

    // BFS traversal from target
    LongOpenHashSet visited = new LongOpenHashSet();
    Deque<HeapObject> queue = new ArrayDeque<>();
    queue.add(target);
    visited.add(target.getId());

    long totalSize = 0;

    while (!queue.isEmpty()) {
      HeapObject current = queue.poll();
      totalSize += current.getShallowSize();

      int inboundCount = inboundCounts.get(current.getId());

      // If object has multiple inbound references, it's potentially shared
      // Don't expand further from shared objects
      if (inboundCount > 1) {
        continue;
      }

      // Expand to strong outbound references (skip Reference.referent)
      current
          .getStrongOutboundReferences()
          .forEach(
              ref -> {
                if (!visited.contains(ref.getId())) {
                  visited.add(ref.getId());
                  queue.add(ref);
                }
              });
    }

    return totalSize;
  }

  /**
   * Overload for backward compatibility with Long2IntOpenHashMap. Used by code that builds inbound
   * counts in-memory.
   */
  public static long computeMinRetainedSize(HeapObject target, Long2IntOpenHashMap inboundCounts) {
    return computeMinRetainedSize(target, new InMemoryInboundCountAccessor(inboundCounts));
  }

  /**
   * Abstraction for accessing inbound reference counts. Allows transparent switching between
   * in-memory and indexed modes.
   */
  interface InboundCountAccessor {
    /**
     * Returns the inbound reference count for an object.
     *
     * @param objectId 64-bit object ID
     * @return inbound reference count (0 if object has no inbound references)
     */
    int get(long objectId);
  }

  /** In-memory accessor backed by Long2IntOpenHashMap. Used when parsing in in-memory mode. */
  static class InMemoryInboundCountAccessor implements InboundCountAccessor {
    private final Long2IntOpenHashMap counts;

    InMemoryInboundCountAccessor(Long2IntOpenHashMap counts) {
      this.counts = counts;
    }

    @Override
    public int get(long objectId) {
      return counts.get(objectId);
    }
  }

  /**
   * Indexed accessor backed by InboundCountReader. Used when parsing in indexed mode with
   * persistent inbound index.
   */
  static class IndexedInboundCountAccessor implements InboundCountAccessor {
    private final InboundCountReader reader;
    private final Long2IntOpenHashMap addressToId32;

    IndexedInboundCountAccessor(InboundCountReader reader, Long2IntOpenHashMap addressToId32) {
      this.reader = reader;
      this.addressToId32 = addressToId32;
    }

    @Override
    public int get(long objectId) {
      // Map 64-bit object ID to 32-bit sequential ID
      int id32 = addressToId32.get(objectId);
      if (id32 == -1) {
        return 0; // Object not found
      }
      return reader.getInboundCount(id32);
    }
  }
}
