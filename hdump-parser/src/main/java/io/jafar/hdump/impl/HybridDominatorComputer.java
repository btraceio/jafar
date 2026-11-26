package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hybrid dominator computation combining fast approximate retained sizes with selective exact
 * computation.
 *
 * <p>This approach recognizes that users typically care about the top N largest retainers, not
 * exact retained sizes for all objects. The strategy:
 *
 * <ul>
 *   <li><strong>Phase 1:</strong> Fast approximate retained sizes for all objects (~8 bytes/object)
 *   <li><strong>Phase 2:</strong> Identify "interesting" objects (top N, leak patterns, etc.)
 *   <li><strong>Phase 3:</strong> Exact dominator computation only for interesting subgraph
 * </ul>
 *
 * <p><strong>Memory savings:</strong> For 100M objects:
 *
 * <ul>
 *   <li>All approximate: 800 MB
 *   <li>Exact for top 0.1%: +15 MB
 *   <li>Total: ~815 MB vs 15 GB for full exact computation (95% reduction)
 * </ul>
 */
public final class HybridDominatorComputer {

  private static final Logger LOG = LoggerFactory.getLogger(HybridDominatorComputer.class);

  private HybridDominatorComputer() {}

  /**
   * Identifies interesting objects for exact computation.
   *
   * <p>Combines multiple heuristics to find objects worth exact analysis:
   *
   * <ul>
   *   <li>Top N by approximate retained size
   *   <li>Known leak-prone classes (ThreadLocal, ClassLoader, etc.)
   *   <li>Objects with unusually high reference counts
   *   <li>Objects matching user-provided patterns
   * </ul>
   *
   * @param dump the heap dump (uses lazy loading with LRU cache in indexed mode)
   * @param topN number of top retainers to include
   * @param classPatterns optional class name patterns to match (e.g., "*.cache.*", "ThreadLocal")
   * @return set of object IDs deemed interesting
   */
  public static LongOpenHashSet identifyInterestingObjects(
      HeapDumpImpl dump, int topN, Set<String> classPatterns) {

    LOG.info("Identifying interesting objects (topN={}, patterns={})", topN, classPatterns);

    LongOpenHashSet interesting = new LongOpenHashSet();

    Set<String> leakProneClasses =
        Set.of(
            "java.lang.ThreadLocal",
            "java.lang.ThreadLocal$ThreadLocalMap",
            "java.lang.ThreadLocal$ThreadLocalMap$Entry",
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.HashMap",
            "java.util.WeakHashMap",
            "java.lang.ref.WeakReference",
            "java.lang.ref.SoftReference");

    // Pre-compile patterns once before the scan
    List<Pattern> compiledPatterns =
        (classPatterns != null && !classPatterns.isEmpty())
            ? classPatterns.stream()
                .map(HybridDominatorComputer::compilePattern)
                .collect(Collectors.toList())
            : Collections.emptyList();

    // Single pass over all objects — covers all three heuristics at once.
    // Heuristic 1 uses a bounded min-heap so we never sort more than topN+1 entries in memory.
    // [retained, objectId]  — ascending by retained so poll() removes the smallest
    TopN topNQueue = new TopN(topN);
    int[] leakProneCount = {0};
    int[] patternCount = {0};

    dump.getObjects()
        .forEach(
            obj -> {
              long retained = obj.getRetainedSize();
              HeapClass cls = obj.getHeapClass();
              String className = cls != null ? cls.getName() : null;

              // Heuristic 1: maintain bounded top-N by retained size
              if (retained > 0) {
                topNQueue.offer(retained, obj.getId());
              }

              // Heuristic 2: large instances of known leak-prone classes
              if (className != null
                  && leakProneClasses.contains(className)
                  && retained > 1024 * 1024) {
                interesting.add(obj.getId());
                leakProneCount[0]++;
              }

              // Heuristic 3: user-provided class patterns
              if (className != null) {
                for (Pattern p : compiledPatterns) {
                  if (p.matcher(className).matches()) {
                    interesting.add(obj.getId());
                    patternCount[0]++;
                    break;
                  }
                }
              }
            });

    // Drain the top-N queue into the interesting set
    for (long[] entry : topNQueue.entries()) {
      interesting.add(entry[1]);
    }
    LOG.debug(
        "Single-pass heuristics: {} top retainers, {} leak-prone, {} pattern matches",
        topNQueue.size(),
        leakProneCount[0],
        patternCount[0]);

    LOG.info("Identified {} interesting objects total", interesting.size());
    return interesting;
  }

  /**
   * The bounded top-N of {@code (retained, id)} pairs: offer every pair, then poll the smallest
   * when over capacity — exactly the {@link PriorityQueue} operations this heuristic always did, so
   * the selection among equal retained sizes is unchanged (it depends on the heap's array layout,
   * which every offer/poll permutes). Only the allocation changes: the pair array {@code poll()}
   * hands back is reused for the next offer, so at most {@code topN + 1} arrays exist instead of
   * one per object.
   */
  static final class TopN {
    private final int topN;
    private final PriorityQueue<long[]> queue;
    private long[] spare;

    TopN(int topN) {
      this.topN = topN;
      this.queue = new PriorityQueue<>(topN + 1, Comparator.comparingLong(a -> a[0]));
    }

    void offer(long retained, long id) {
      long[] e = spare != null ? spare : new long[2];
      spare = null;
      e[0] = retained;
      e[1] = id;
      queue.offer(e);
      if (queue.size() > topN) {
        spare = queue.poll(); // evict smallest
      }
    }

    int size() {
      return queue.size();
    }

    /** The kept pairs, in the queue's iteration order. */
    Iterable<long[]> entries() {
      return queue;
    }
  }

  /**
   * Expands the interesting object set to include their full dominator paths to GC roots.
   *
   * <p>This ensures exact computation includes all objects needed to compute accurate dominators
   * for the interesting set.
   *
   * <p><b>Note for indexed mode:</b> This method streams through all objects to build inbound
   * references. With LRU cache, objects are loaded on-demand and automatically evicted, keeping
   * memory usage bounded (~40 bytes per cached object).
   *
   * @param dump the heap dump (uses lazy loading in indexed mode)
   * @param gcRoots GC roots
   * @param interesting initial set of interesting object IDs
   * @return expanded set including dominator paths
   */
  public static LongOpenHashSet expandToDominatorPaths(
      HeapDumpImpl dump, List<GcRootImpl> gcRoots, LongOpenHashSet interesting) {

    LOG.info("Expanding {} interesting objects to include dominator paths...", interesting.size());

    LongOpenHashSet expanded = new LongOpenHashSet(interesting);

    HeapGraph graph = dump.pathGraph(); // IN_MEMORY only; null in INDEXED mode
    if (graph != null) {
      expandOnGraph(graph, gcRoots, interesting, expanded);
      LOG.info(
          "Expanded to {} objects ({}% of heap)",
          expanded.size(), String.format("%.2f", expanded.size() * 100.0 / dump.getObjectCount()));
      return expanded;
    }

    // Build reverse reference map for traversal (streams through all objects)
    // In indexed mode with LRU cache, objects are loaded on-demand
    Long2ObjectMap<List<Long>> inboundRefs = buildInboundReferences(dump);

    // For each interesting object, traverse backwards to GC roots
    Set<Long> gcRootIds = gcRoots.stream().map(GcRootImpl::getObjectId).collect(Collectors.toSet());

    for (long objId : interesting) {
      addDominatorPath(objId, inboundRefs, gcRootIds, expanded);
    }

    LOG.info(
        "Expanded to {} objects ({}% of heap)",
        expanded.size(), String.format("%.2f", expanded.size() * 100.0 / dump.getObjectCount()));

    return expanded;
  }

  /**
   * Computes exact dominators for a subgraph of interesting objects.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Extracts the subgraph containing only interesting objects
   *   <li>Filters GC roots to only those pointing into the subgraph
   *   <li>Runs full dominator tree computation on the reduced graph
   *   <li>Updates retained sizes and dominator relationships for subgraph objects
   * </ol>
   *
   * @param dump the heap dump (uses lazy loading in indexed mode)
   * @param gcRoots all GC roots
   * @param interestingSet set of object IDs to compute exact dominators for
   * @param progressCallback optional progress callback
   */
  public static void computeExactForSubgraph(
      HeapDumpImpl dump,
      List<GcRootImpl> gcRoots,
      LongOpenHashSet interestingSet,
      DominatorTreeComputer.ProgressCallback progressCallback) {

    LOG.info("Computing exact dominators for subgraph of {} objects", interestingSet.size());

    // Create filtered object map with only interesting objects
    // In indexed mode, uses getObjectByIdInternal() which leverages LRU cache
    Long2ObjectMap<HeapObjectImpl> subgraphObjects = new Long2ObjectOpenHashMap<>();
    for (long objId : interestingSet) {
      HeapObjectImpl obj = dump.getObjectByIdInternal(objId);
      if (obj != null) {
        subgraphObjects.put(objId, obj);
      }
    }

    // Filter GC roots to only those pointing into subgraph
    List<GcRootImpl> subgraphRoots =
        gcRoots.stream()
            .filter(root -> interestingSet.contains(root.getObjectId()))
            .collect(Collectors.toList());

    LOG.debug(
        "Filtered subgraph: {} objects, {} GC roots", subgraphObjects.size(), subgraphRoots.size());

    // Compute exact dominators for subgraph
    // The returned children map is not needed here; the call reports progress and marks
    // exactness via the subgraph computation below.
    DominatorTreeComputer.computeFull(dump, subgraphObjects, subgraphRoots, progressCallback);

    // Mark these objects as having exact retained sizes
    for (HeapObjectImpl obj : subgraphObjects.values()) {
      obj.setHasExactRetainedSize(true);
    }

    LOG.info("Exact dominator computation complete for {} objects", subgraphObjects.size());
  }

  // === Helper methods ===

  /**
   * Simple glob-style pattern matching.
   *
   * <p>Supports wildcards: * matches any sequence, ? matches single character
   */
  private static Pattern compilePattern(String pattern) {
    if (pattern.equals("*")) {
      return Pattern.compile(".*");
    }
    String regex =
        pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").replace("$", "\\$");
    return Pattern.compile(regex);
  }

  /** Builds map of object ID to list of objects that reference it. */
  /**
   * Builds inbound reference map by streaming through all objects. In indexed mode, objects are
   * loaded on-demand via LRU cache.
   */
  private static Long2ObjectMap<List<Long>> buildInboundReferences(HeapDumpImpl dump) {

    // Pre-size to avoid rehashing (estimate: ~10-20% of objects have inbound refs)
    int estimatedSize = Math.max(dump.getObjectCount() / 10, 16);
    Long2ObjectMap<List<Long>> inboundRefs = new Long2ObjectOpenHashMap<>(estimatedSize);

    // Stream through all objects without materializing (uses lazy loading in indexed mode)
    dump.getObjects()
        .forEach(
            heapObj -> {
              HeapObjectImpl obj = (HeapObjectImpl) heapObj;
              long[] refIds = obj.getStrongOutboundReferenceIds();
              long objId = obj.getId();
              for (int i = 0; i < refIds.length; i++) {
                inboundRefs.computeIfAbsent(refIds[i], k -> new ArrayList<>()).add(objId);
              }
            });

    return inboundRefs;
  }

  /**
   * {@link #addDominatorPath} for every interesting object at once, on the reverse of the int
   * graph. The union of the per-object backward BFSes is one multi-source backward BFS: whether a
   * node is expanded depends only on the node (GC roots are not), not on which BFS reached it.
   * Interesting objects that are not in the graph stay in {@code result} unexpanded, as before.
   */
  private static void expandOnGraph(
      HeapGraph g, List<GcRootImpl> gcRoots, LongOpenHashSet interesting, LongOpenHashSet result) {
    final int n = g.size();
    final int[] ss = g.succStart;
    final int[] su = g.succ;
    int[] predStart = new int[n + 1];
    for (int e = 0, m = ss[n]; e < m; e++) {
      predStart[su[e] + 1]++;
    }
    for (int v = 0; v < n; v++) {
      predStart[v + 1] += predStart[v];
    }
    int[] pred = new int[predStart[n]];
    int[] fill = new int[n];
    for (int u = 0; u < n; u++) {
      for (int e = ss[u], end = ss[u + 1]; e < end; e++) {
        int v = su[e];
        pred[predStart[v] + fill[v]++] = u;
      }
    }

    boolean[] isRoot = new boolean[n];
    for (GcRootImpl root : gcRoots) {
      int r = g.nodeOf(root.getObjectId());
      if (r >= 0) isRoot[r] = true;
    }
    boolean[] seen = new boolean[n];
    int[] queue = new int[n];
    int tail = 0;
    for (long id : interesting) {
      int v = g.nodeOf(id);
      if (v >= 0 && !seen[v]) {
        seen[v] = true;
        queue[tail++] = v;
      }
    }
    for (int head = 0; head < tail; head++) {
      int v = queue[head];
      result.add(g.nodes[v].getId());
      if (isRoot[v]) continue; // reached a GC root: stop expanding
      for (int i = predStart[v], end = predStart[v + 1]; i < end; i++) {
        int p = pred[i];
        if (!seen[p]) {
          seen[p] = true;
          queue[tail++] = p;
        }
      }
    }
  }

  /**
   * Recursively adds all objects on the path from target to GC roots.
   *
   * <p>Uses BFS to find shortest paths to any GC root.
   */
  private static void addDominatorPath(
      long targetId,
      Long2ObjectMap<List<Long>> inboundRefs,
      Set<Long> gcRootIds,
      LongOpenHashSet result) {

    if (gcRootIds.contains(targetId)) {
      return; // Already at a GC root
    }

    Queue<Long> queue = new ArrayDeque<>();
    LongOpenHashSet visited = new LongOpenHashSet();

    queue.add(targetId);
    visited.add(targetId);
    result.add(targetId);

    while (!queue.isEmpty()) {
      long current = queue.poll();

      if (gcRootIds.contains(current)) {
        continue; // Reached a GC root, stop expanding
      }

      List<Long> predecessors = inboundRefs.get(current);
      if (predecessors != null) {
        for (long pred : predecessors) {
          if (!visited.contains(pred)) {
            visited.add(pred);
            result.add(pred);
            queue.add(pred);
          }
        }
      }
    }
  }
}
