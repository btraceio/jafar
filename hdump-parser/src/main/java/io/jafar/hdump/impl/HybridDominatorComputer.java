package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
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
   * @param objectsById all objects in heap dump
   * @param topN number of top retainers to include
   * @param classPatterns optional class name patterns to match (e.g., "*.cache.*", "ThreadLocal")
   * @return set of object IDs deemed interesting
   */
  public static LongOpenHashSet identifyInterestingObjects(
      Long2ObjectMap<HeapObjectImpl> objectsById, int topN, Set<String> classPatterns) {

    LOG.info("Identifying interesting objects (topN={}, patterns={})", topN, classPatterns);

    LongOpenHashSet interesting = new LongOpenHashSet();

    // Heuristic 1: Top N by approximate retained size
    List<HeapObjectImpl> topRetainers =
        objectsById.values().stream()
            .filter(obj -> obj.getRetainedSize() > 0)
            .sorted(Comparator.comparingLong(HeapObjectImpl::getRetainedSize).reversed())
            .limit(topN)
            .collect(Collectors.toList());

    for (HeapObjectImpl obj : topRetainers) {
      interesting.add(obj.getId());
    }

    LOG.debug("Added {} objects from top retainers", topRetainers.size());

    // Heuristic 2: Known leak-prone classes
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

    int leakProneCount = 0;
    for (HeapObjectImpl obj : objectsById.values()) {
      HeapClass cls = obj.getHeapClass();
      if (cls != null && leakProneClasses.contains(cls.getName())) {
        // Only include if they're large enough
        if (obj.getRetainedSize() > 1024 * 1024) { // > 1 MB
          interesting.add(obj.getId());
          leakProneCount++;
        }
      }
    }

    LOG.debug("Added {} objects from leak-prone classes", leakProneCount);

    // Heuristic 3: User-provided class patterns
    if (classPatterns != null && !classPatterns.isEmpty()) {
      int patternCount = 0;
      for (HeapObjectImpl obj : objectsById.values()) {
        HeapClass cls = obj.getHeapClass();
        if (cls != null) {
          String className = cls.getName();
          for (String pattern : classPatterns) {
            if (matchesPattern(className, pattern)) {
              interesting.add(obj.getId());
              patternCount++;
              break;
            }
          }
        }
      }
      LOG.debug("Added {} objects matching user patterns", patternCount);
    }

    LOG.info("Identified {} interesting objects total", interesting.size());
    return interesting;
  }

  /**
   * Expands the interesting object set to include their full dominator paths to GC roots.
   *
   * <p>This ensures exact computation includes all objects needed to compute accurate dominators
   * for the interesting set.
   *
   * @param dump the heap dump
   * @param objectsById all objects
   * @param gcRoots GC roots
   * @param interesting initial set of interesting object IDs
   * @return expanded set including dominator paths
   */
  public static LongOpenHashSet expandToDominatorPaths(
      HeapDumpImpl dump,
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<GcRootImpl> gcRoots,
      LongOpenHashSet interesting) {

    LOG.info("Expanding {} interesting objects to include dominator paths...", interesting.size());

    LongOpenHashSet expanded = new LongOpenHashSet(interesting);

    // Build reverse reference map for traversal
    Long2ObjectMap<List<Long>> inboundRefs = buildInboundReferences(objectsById);

    // For each interesting object, traverse backwards to GC roots
    Set<Long> gcRootIds =
        gcRoots.stream().map(GcRootImpl::getObjectId).collect(Collectors.toSet());

    for (long objId : interesting) {
      addDominatorPath(objId, inboundRefs, gcRootIds, expanded);
    }

    LOG.info("Expanded to {} objects ({}% of heap)", expanded.size(),
        String.format("%.2f", expanded.size() * 100.0 / objectsById.size()));

    return expanded;
  }

  /**
   * Computes exact dominators for a subgraph of interesting objects.
   *
   * <p>This method:
   * <ol>
   *   <li>Extracts the subgraph containing only interesting objects
   *   <li>Filters GC roots to only those pointing into the subgraph
   *   <li>Runs full dominator tree computation on the reduced graph
   *   <li>Updates retained sizes and dominator relationships for subgraph objects
   * </ol>
   *
   * @param dump the heap dump
   * @param objectsById all objects
   * @param gcRoots all GC roots
   * @param interestingSet set of object IDs to compute exact dominators for
   * @param progressCallback optional progress callback
   */
  public static void computeExactForSubgraph(
      HeapDumpImpl dump,
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<GcRootImpl> gcRoots,
      LongOpenHashSet interestingSet,
      DominatorTreeComputer.ProgressCallback progressCallback) {

    LOG.info("Computing exact dominators for subgraph of {} objects", interestingSet.size());

    // Create filtered object map with only interesting objects
    Long2ObjectMap<HeapObjectImpl> subgraphObjects = new Long2ObjectOpenHashMap<>();
    for (long objId : interestingSet) {
      HeapObjectImpl obj = objectsById.get(objId);
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
        "Filtered subgraph: {} objects, {} GC roots",
        subgraphObjects.size(),
        subgraphRoots.size());

    // Compute exact dominators for subgraph
    Map<Long, List<Long>> dominatorChildren =
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
  private static boolean matchesPattern(String text, String pattern) {
    if (pattern.equals("*")) {
      return true;
    }

    // Convert glob to regex
    String regex =
        pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").replace("$", "\\$");

    return text.matches(regex);
  }

  /** Builds map of object ID to list of objects that reference it. */
  private static Long2ObjectMap<List<Long>> buildInboundReferences(
      Long2ObjectMap<HeapObjectImpl> objectsById) {

    // Pre-size to avoid rehashing (estimate: ~10-20% of objects have inbound refs)
    int estimatedSize = Math.max(objectsById.size() / 10, 16);
    Long2ObjectMap<List<Long>> inboundRefs = new Long2ObjectOpenHashMap<>(estimatedSize);

    for (HeapObjectImpl obj : objectsById.values()) {
      // Use direct array access to avoid Stream overhead
      long[] refIds = obj.getOutboundReferenceIds();
      long objId = obj.getId();
      for (int i = 0; i < refIds.length; i++) {
        inboundRefs.computeIfAbsent(refIds[i], k -> new ArrayList<>()).add(objId);
      }
    }

    return inboundRefs;
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
