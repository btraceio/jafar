package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.index.InboundCountReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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

  /**
   * INDEXED mode: {@link #computeMinRetainedSize} for every id32 in order, written to {@code
   * writer}, without materialising objects.
   *
   * <p>Each BFS walks the on-disk strong-reference graph ({@link StrongRefsIndex}, through {@link
   * IndexedRefs}), reads inbound counts from {@code inbound.idx} and shallow sizes from {@code
   * objects.idx}, with one reusable visited-stamp array (4 bytes per id32) — no {@link
   * HeapObjectImpl}, no LRU cache, no per-object sets. Values are those of the per-object BFS over
   * loaded objects: same graph (references that name an object), same inbound counts, same shallow
   * size formula. Class entries get 0, as before (a loaded class entry has shallow size 0 and no
   * readable references).
   *
   * <p>Still one BFS per object, so a long singly linked chain costs quadratic time here; the
   * all-at-once computation used in IN_MEMORY mode needs about 60 bytes per object of working
   * arrays, which this mode does not assume it has.
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
    IndexedRefs refs = new IndexedRefs(dump, dump.strongRefsIndex());
    final int n = refs.size();
    int[] mark = new int[n];
    int[] queue = new int[16];
    int stamp = 0;
    int lastReportedPercent = 0;
    int reportEvery = Math.max(n / 100, 50_000);
    try {
      for (int t = 0; t < n; t++) {
        long size = 0;
        if (!refs.isClassEntry(t)) {
          if (++stamp == 0) { // wrapped: reset the stamps
            java.util.Arrays.fill(mark, 0);
            stamp = 1;
          }
          int head = 0;
          int tail = 0;
          queue[tail++] = t;
          mark[t] = stamp;
          while (head < tail) {
            int u = queue[head++];
            size += refs.shallowSize(u);
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
        }
        writer.writeEntry(t, size);
        if (progressCallback != null && (t + 1) % reportEvery == 0) {
          double progress = 0.3 + 0.7 * ((t + 1) / (double) n);
          int percent = (int) (progress * 100);
          if (percent > lastReportedPercent) {
            progressCallback.onProgress(
                progress, String.format("Computing approximate retained sizes (%d%%)...", percent));
            lastReportedPercent = percent;
          }
        }
      }
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to write retained size to index", e);
    }
    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Approximate retained size computation complete");
    }
    LOG.info("Completed indexed retained size computation for {} entries", n);
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
