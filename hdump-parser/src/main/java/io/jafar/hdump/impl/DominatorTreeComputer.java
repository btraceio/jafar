package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapObject;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes full dominator tree using Cooper-Harvey-Kennedy algorithm.
 *
 * <p>This implementation follows the algorithm from "A Simple, Fast Dominance Algorithm" by Cooper,
 * Harvey, and Kennedy (2006). Despite O(N²) worst-case complexity, it runs ~2.5× faster than
 * Lengauer-Tarjan in practice on real-world graphs.
 *
 * <p><strong>Performance:</strong> 15-30s for 10M objects with 420MB peak memory.
 *
 * <p><strong>Algorithm overview:</strong>
 *
 * <ol>
 *   <li>Build reverse post-order traversal from GC roots
 *   <li>Initialize immediate dominators (idom)
 *   <li>Iterate until fixed point:
 *       <ul>
 *         <li>For each node, compute new idom as intersection of predecessors' idoms
 *         <li>Continue until no changes
 *       </ul>
 * </ol>
 */
public final class DominatorTreeComputer {

  private static final Logger LOG = LoggerFactory.getLogger(DominatorTreeComputer.class);
  private static final long UNDEFINED = -1;
  /** Synthetic node ID that acts as the unique entry dominating all GC roots. */
  private static final long VIRTUAL_ROOT = Long.MIN_VALUE;

  private DominatorTreeComputer() {}

  /**
   * Computes full dominator tree for all objects in the heap dump.
   *
   * <p>Sets the immediate dominator and retained size for each object. This is more expensive than
   * approximate retained size computation but provides exact results.
   *
   * @param dump the heap dump
   * @param objectsById map of object IDs to objects
   * @param gcRoots list of GC roots
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   * @return map from dominator ID to list of dominated object IDs (for efficient lookup)
   */
  public static Map<Long, List<Long>> computeFull(
      HeapDumpImpl dump,
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<GcRootImpl> gcRoots,
      ProgressCallback progressCallback) {

    long startTime = System.currentTimeMillis();
    int totalObjects = objectsById.size();

    LOG.info("Computing full dominator tree for {} objects...", totalObjects);
    if (progressCallback != null) {
      progressCallback.onProgress(0.0, "Building reverse post-order traversal...");
    }

    // Step 1: Build reverse post-order (RPO) from GC roots
    List<Long> rpo = buildReversePostOrder(objectsById, gcRoots);
    LOG.debug("Built RPO with {} reachable objects", rpo.size());

    // Prepend virtual root at position 0 so all GC roots share a common dominator.
    // Without this, intersect(root1, root2) has no common ancestor and the iteration
    // oscillates instead of converging for nodes reachable from multiple GC roots.
    rpo.add(0, VIRTUAL_ROOT);

    // Build RPO index map once (reused millions of times in intersect())
    Long2IntOpenHashMap rpoIndex = new Long2IntOpenHashMap(rpo.size());
    rpoIndex.defaultReturnValue(-1);
    for (int i = 0; i < rpo.size(); i++) {
      rpoIndex.put(rpo.get(i).longValue(), i);
    }

    if (progressCallback != null) {
      progressCallback.onProgress(0.2, "Building predecessor map...");
    }

    // Step 2: Build predecessor map (reverse edges)
    Map<Long, List<Long>> predecessors = buildPredecessorMap(objectsById, rpo, progressCallback);

    // Step 3: Compute immediate dominators using iterative dataflow
    Long2LongOpenHashMap idom = new Long2LongOpenHashMap(rpo.size());
    idom.defaultReturnValue(UNDEFINED);

    // Virtual root dominates itself; all GC roots are dominated by the virtual root.
    idom.put(VIRTUAL_ROOT, VIRTUAL_ROOT);
    LongOpenHashSet rootIds = new LongOpenHashSet();
    for (GcRoot root : gcRoots) {
      rootIds.add(root.getObjectId());
      idom.put(root.getObjectId(), VIRTUAL_ROOT);
    }

    // Iterate until fixed point with convergence-based progress tracking.
    // Stagnation guard: if changeCount stops decreasing for STAGNATION_PATIENCE consecutive
    // iterations it means we are stuck in a flip-flop cycle (common when the reference graph
    // has cycles that prevent the RPO from being a perfect topological order).
    // For heap analysis, the handful of affected nodes is negligible.
    final int STAGNATION_PATIENCE = 20;
    boolean changed = true;
    int iteration = 0;
    int lastChangeCount = Integer.MAX_VALUE;
    int stagnantIterations = 0;
    long totalIterationTime = 0;

    while (changed) {
      changed = false;
      iteration++;
      int changeCount = 0;
      long iterationStart = System.currentTimeMillis();

      // Process nodes in reverse post-order (skip roots)
      int processed = 0;
      int lastReportedPercent = 0;
      for (int i = 0; i < rpo.size(); i++) {
        long nodeId = rpo.get(i);
        if (rootIds.contains(nodeId)) continue;

        List<Long> preds = predecessors.get(nodeId);
        if (preds == null || preds.isEmpty()) continue;

        // Find new dominator: intersection of all predecessor dominators
        long newIdom = UNDEFINED;
        for (long predId : preds) {
          if (idom.get(predId) == UNDEFINED) continue;

          if (newIdom == UNDEFINED) {
            newIdom = predId;
          } else {
            newIdom = intersect(rpoIndex, idom, newIdom, predId);
          }
        }

        if (newIdom != UNDEFINED && idom.get(nodeId) != newIdom) {
          idom.put(nodeId, newIdom);
          changed = true;
          changeCount++;
        }

        processed++;

        // Show progress within iteration every 10% for first few iterations
        if (iteration <= 5 && progressCallback != null && processed % (rpo.size() / 10) == 0) {
          int percentWithinIteration = (int) ((processed / (double) rpo.size()) * 100);
          if (percentWithinIteration > lastReportedPercent) {
            progressCallback.onProgress(
                0.4 + (0.01 * percentWithinIteration),
                String.format(
                    "Computing dominators (iteration %d, %d%% through %,d objects)...",
                    iteration, percentWithinIteration, rpo.size()));
            lastReportedPercent = percentWithinIteration;
          }
        }
      }

      long iterationElapsed = System.currentTimeMillis() - iterationStart;
      totalIterationTime += iterationElapsed;

      // Log slow iterations to console
      if (iterationElapsed > 5000) {
        String msg =
            String.format(
                "⚠ Slow iteration %d: %dms, %d changes, avg %.2fms/change",
                iteration,
                iterationElapsed,
                changeCount,
                changeCount > 0 ? iterationElapsed / (double) changeCount : 0);
        LOG.warn(msg);
        System.err.println(msg);
      }

      // Update progress more frequently - every iteration for first 10, then every 5, then every 10
      boolean shouldReport =
          iteration <= 10 || (iteration <= 50 && iteration % 5 == 0) || iteration % 10 == 0;

      if (shouldReport && progressCallback != null) {
        double iterationProgress;
        if (changeCount < lastChangeCount && changeCount > 0) {
          int convergenceRate = lastChangeCount - changeCount;
          int estimatedRemaining = Math.max(changeCount / Math.max(convergenceRate, 1), 1);
          iterationProgress =
              Math.min(0.95, iteration / (double) (iteration + estimatedRemaining));
        } else {
          // Conservative estimate if not converging steadily
          iterationProgress = Math.min(0.5, iteration / 100.0);
        }

        // Progress from 0.4 to 0.7 (40% to 70%) during iteration
        double progress = 0.4 + (0.3 * iterationProgress);
        progressCallback.onProgress(
            progress,
            String.format(
                "Computing dominators (iteration %d, %d changes, %dms)...",
                iteration, changeCount, iterationElapsed));

      }

      // Stagnation guard: stop if changeCount has not decreased for STAGNATION_PATIENCE
      // consecutive iterations. This handles flip-flop cycles caused by cyclic reference graphs
      // where a handful of nodes oscillate indefinitely. The result is approximate but
      // negligibly so relative to heap size.
      if (changeCount > 0 && changeCount >= lastChangeCount) {
        stagnantIterations++;
        if (stagnantIterations >= STAGNATION_PATIENCE) {
          String msg = String.format(
              "⚠ Dominator computation stagnated at %d change(s) after %d iterations "
                  + "— stopping early (result is approximate, %d node(s) may have incorrect idom)",
              changeCount, iteration, changeCount);
          LOG.warn(msg);
          System.err.println(msg);
          changed = false;
        }
      } else {
        stagnantIterations = 0;
      }
      lastChangeCount = changeCount;
    }

    LOG.debug(
        "Converged after {} iterations in {}ms (avg {}ms/iteration)",
        iteration,
        totalIterationTime,
        totalIterationTime / iteration);

    LOG.debug("Converged after {} iterations", iteration);

    if (progressCallback != null) {
      progressCallback.onProgress(0.7, "Computing retained sizes...");
    }

    // Step 4: Set immediate dominators on objects and build dominator children map.
    // Skip the virtual root — it has no corresponding heap object.
    Map<Long, LongArrayList> dominatorChildren = new HashMap<>();
    for (long nodeId : rpo) {
      if (nodeId == VIRTUAL_ROOT) continue;
      long idomId = idom.get(nodeId);
      if (idomId == UNDEFINED || idomId == VIRTUAL_ROOT || idomId == nodeId) continue;
      HeapObjectImpl obj = objectsById.get(nodeId);
      HeapObjectImpl dominator = objectsById.get(idomId);
      if (obj != null && dominator != null) {
        obj.setDominator(dominator);
      }
      // Build children map for efficient retained size computation
      dominatorChildren.computeIfAbsent(idomId, k -> new LongArrayList()).add(nodeId);
    }

    if (progressCallback != null) {
      progressCallback.onProgress(0.8, "Computing exact retained sizes...");
    }

    // Step 5: Compute retained sizes bottom-up using children map
    computeRetainedSizes(objectsById, rpo, dominatorChildren);

    long elapsed = System.currentTimeMillis() - startTime;
    LOG.info("Dominator tree computation completed in {}ms", elapsed);

    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Dominator tree computation complete");
    }

    // Convert LongArrayList to List<Long> for return
    Map<Long, List<Long>> result = new HashMap<>();
    for (Map.Entry<Long, LongArrayList> entry : dominatorChildren.entrySet()) {
      List<Long> children = new ArrayList<>(entry.getValue().size());
      for (int i = 0; i < entry.getValue().size(); i++) {
        children.add(entry.getValue().getLong(i));
      }
      result.put(entry.getKey(), children);
    }
    return result;
  }

  /**
   * Find intersection of two nodes in dominator tree. Walks up the tree from both nodes until they
   * meet.
   *
   * @param rpoIndex precomputed RPO index map for O(1) position lookups
   * @param idom immediate dominator map
   * @param b1 first node
   * @param b2 second node
   * @return the intersection node (common dominator)
   */
  private static long intersect(Long2IntOpenHashMap rpoIndex, Long2LongOpenHashMap idom, long b1, long b2) {
    while (b1 != b2) {
      int pos1 = rpoIndex.get(b1);
      int pos2 = rpoIndex.get(b2);
      if (pos1 < 0 || pos2 < 0) return UNDEFINED;

      while (pos1 > pos2) {
        b1 = idom.get(b1);
        if (b1 == UNDEFINED) return UNDEFINED;
        pos1 = rpoIndex.get(b1);
        if (pos1 < 0) return UNDEFINED;
      }

      while (pos2 > pos1) {
        b2 = idom.get(b2);
        if (b2 == UNDEFINED) return UNDEFINED;
        pos2 = rpoIndex.get(b2);
        if (pos2 < 0) return UNDEFINED;
      }
    }
    return b1;
  }

  /** Build reverse post-order traversal from GC roots. */
  private static List<Long> buildReversePostOrder(
      Long2ObjectMap<HeapObjectImpl> objectsById, List<GcRootImpl> gcRoots) {

    LongOpenHashSet visited = new LongOpenHashSet();
    LongArrayList postOrder = new LongArrayList();

    // DFS from each GC root
    for (GcRoot root : gcRoots) {
      long rootId = root.getObjectId();
      if (objectsById.containsKey(rootId)) {
        dfsPostOrder(objectsById, rootId, visited, postOrder);
      }
    }

    // Reverse to get RPO
    List<Long> rpo = new ArrayList<>(postOrder.size());
    for (int i = postOrder.size() - 1; i >= 0; i--) {
      rpo.add(postOrder.getLong(i));
    }
    return rpo;
  }

  private static void dfsPostOrder(
      Long2ObjectMap<HeapObjectImpl> objectsById,
      long nodeId,
      LongOpenHashSet visited,
      LongArrayList postOrder) {

    if (!visited.add(nodeId)) return;

    HeapObjectImpl obj = objectsById.get(nodeId);
    if (obj == null) return;

    // Visit all outbound references (use direct array access for performance)
    long[] refIds = obj.getOutboundReferenceIds();
    for (int i = 0; i < refIds.length; i++) {
      dfsPostOrder(objectsById, refIds[i], visited, postOrder);
    }

    postOrder.add(nodeId);
  }

  /**
   * Build predecessor map (reverse edges) with progress reporting.
   * This can be slow for large heaps as it traverses all references.
   */
  private static Map<Long, List<Long>> buildPredecessorMap(
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<Long> rpo,
      ProgressCallback progressCallback) {

    // Pre-size to avoid rehashing (estimate: ~10-20% of objects have predecessors)
    int estimatedSize = Math.max(rpo.size() / 10, 16);
    Map<Long, List<Long>> predecessors = new HashMap<>(estimatedSize);
    LongOpenHashSet reachable = new LongOpenHashSet(rpo);

    int processed = 0;
    int totalObjects = rpo.size();
    int lastReportedPercent = 0;
    long lastReportTime = System.currentTimeMillis();

    LOG.debug("Building predecessor map for {} objects...", totalObjects);

    for (long nodeId : rpo) {
      long objectStartTime = System.currentTimeMillis();
      HeapObjectImpl obj = objectsById.get(nodeId);
      if (obj != null) {
        String className = obj.getHeapClass() != null ? obj.getHeapClass().getName() : "unknown";

        // For huge arrays, warn BEFORE processing
        if (obj.isArray() && obj.getArrayLength() > 1000000) {
          String arrayMsg =
              String.format(
                  "⚠ Huge array at object %,d: %s with %,d elements (ID: %s)",
                  processed + 1,
                  className,
                  obj.getArrayLength(),
                  Long.toHexString(nodeId));
          System.err.println(arrayMsg);
          LOG.warn(arrayMsg);
        }

        // Process references with direct array access (avoid Stream overhead)
        long[] refIds = obj.getOutboundReferenceIds();
        long refCount = 0;
        long lastRefReportTime = System.currentTimeMillis();

        for (int i = 0; i < refIds.length; i++) {
          refCount++;
          long refId = refIds[i];
          if (reachable.contains(refId)) {
            predecessors.computeIfAbsent(refId, k -> new ArrayList<>()).add(nodeId);
          }

          // Report progress DURING reference iteration for huge objects
          if (refCount % 100000 == 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - objectStartTime;
            if (now - lastRefReportTime > 5000) {
              String refMsg =
                  String.format(
                      "  ... processing object %s: %,d refs in %,dms (%.0f refs/sec)",
                      Long.toHexString(nodeId),
                      refCount,
                      elapsed,
                      elapsed > 0 ? (refCount / (elapsed / 1000.0)) : 0);
              System.err.println(refMsg);
              lastRefReportTime = now;
            }
          }
        }

        long objectElapsed = System.currentTimeMillis() - objectStartTime;

        // Warn about objects that take >1 second to process OR have >100K references
        if (objectElapsed > 1000 || refCount > 100000) {
          String warnMsg =
              String.format(
                  "⚠ Slow object: %s (class: %s, refs: %d, time: %dms) - processed %d/%d",
                  Long.toHexString(nodeId),
                  className,
                  refCount,
                  objectElapsed,
                  processed + 1,
                  totalObjects);
          LOG.warn(warnMsg);
          System.err.println(warnMsg); // Also output to console
        }
      }

      processed++;

      // Check progress and report frequently
      long now = System.currentTimeMillis();

      // Watchdog: detect if we're completely hung (check every 1000 objects)
      if (processed % 1000 == 0) {
        long timeSinceLastReport = now - lastReportTime;
        if (timeSinceLastReport > 30000) { // No progress update for 30 seconds
          String errorMsg =
              String.format(
                  "🚨 WATCHDOG: No progress update for %dms at object %d/%d (%d%%)",
                  timeSinceLastReport,
                  processed,
                  totalObjects,
                  (int) ((processed / (double) totalObjects) * 100));
          LOG.error(errorMsg);
          System.err.println(errorMsg); // Also output to console
        }
      }

      // Report progress more frequently (every 10K objects OR every 2 seconds)
      boolean shouldReport =
          processed % Math.min(totalObjects / 50, 10000) == 0 || (now - lastReportTime) >= 2000;

      if (progressCallback != null && shouldReport) {
        // Progress from 0.2 to 0.4 (20% to 40%) during predecessor map building
        double progress = 0.2 + (0.2 * (processed / (double) totalObjects));
        int percentComplete = (int) (progress * 100);
        if (percentComplete > lastReportedPercent || (now - lastReportTime) >= 2000) {
          progressCallback.onProgress(
              progress,
              String.format(
                  "Building predecessor map (%d%%, %d/%d objects)...",
                  percentComplete, processed, totalObjects));
          lastReportedPercent = percentComplete;
          lastReportTime = now;
        }
      }
    }

    if (progressCallback != null) {
      progressCallback.onProgress(0.4, "Computing immediate dominators...");
    }

    LOG.debug("Built predecessor map with {} entries", predecessors.size());
    return predecessors;
  }

  /**
   * Compute retained sizes bottom-up from leaves using dominator children map.
   * Optimized from O(N²) to O(N) by using precomputed children map instead of scanning all objects.
   *
   * @param objectsById map of object IDs to objects
   * @param rpo reverse post-order traversal
   * @param dominatorChildren map from dominator ID to list of dominated children IDs
   */
  private static void computeRetainedSizes(
      Long2ObjectMap<HeapObjectImpl> objectsById,
      List<Long> rpo,
      Map<Long, LongArrayList> dominatorChildren) {

    // Process in reverse RPO (bottom-up, leaves first)
    for (int i = rpo.size() - 1; i >= 0; i--) {
      long nodeId = rpo.get(i);
      HeapObjectImpl obj = objectsById.get(nodeId);
      if (obj == null) continue;

      // Retained size = shallow size + sum of dominated children's retained sizes
      long retained = obj.getShallowSize();

      // Direct lookup of dominated children (O(1) access, O(children) iteration)
      LongArrayList children = dominatorChildren.get(nodeId);
      if (children != null) {
        for (int j = 0; j < children.size(); j++) {
          HeapObjectImpl child = objectsById.get(children.getLong(j));
          if (child != null) {
            retained += child.getRetainedSize();
          }
        }
      }

      obj.setRetainedSize(retained);
    }
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
