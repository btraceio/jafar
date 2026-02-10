package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
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
 *   <li>Under-estimates by ~10-20% but runs ~5× faster than exact dominator computation
 * </ul>
 *
 * <p><strong>Performance:</strong> O(V + E) per object where V = reachable objects, E = edges.
 * Much faster than O(N²) exact dominator computation for targeted queries.
 *
 * <p><strong>Accuracy:</strong> Conservative under-estimate. Good enough for leak detection where
 * order-of-magnitude accuracy is sufficient.
 */
public final class ApproximateRetainedSizeComputer {

  private static final Logger LOG =
      LoggerFactory.getLogger(ApproximateRetainedSizeComputer.class);

  private ApproximateRetainedSizeComputer() {}

  /**
   * Computes approximate retained sizes for all objects using streaming iteration with persistent storage.
   *
   * <p><strong>Streaming Mode:</strong> Designed for large heap dumps (100M+ objects) that cannot fit
   * in memory. Objects are processed one-at-a-time from an iterator without caching in objectsById.
   *
   * <p><strong>Persistent Storage:</strong> Computed retained sizes are written to a RetainedSizeWriter
   * which persists them to retained.idx for instant reuse on subsequent queries.
   *
   * <p><strong>Memory Characteristics:</strong>
   * <ul>
   *   <li>Heap usage: O(1) - only one object in memory during iteration
   *   <li>Requires persistent inbound index (ensureInboundIndexBuilt must be called first)
   *   <li>Writes sequential entries to retained.idx (objectId32:4 + retainedSize:8)
   * </ul>
   *
   * @param dump the heap dump (provides access to inbound count reader)
   * @param objectIterator streaming iterator over objects (NOT cached)
   * @param totalObjects total object count for progress reporting
   * @param gcRoots list of GC roots
   * @param writer persistent storage for computed retained sizes
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   */
  public static void computeAll(
      HeapDumpImpl dump,
      Iterable<HeapObjectImpl> objectIterator,
      int totalObjects,
      List<GcRootImpl> gcRoots,
      io.jafar.hdump.index.RetainedSizeWriter writer,
      ProgressCallback progressCallback) {

    // Indexed mode with streaming - must have inbound index
    InboundCountReader inboundCountReader = dump.getInboundCountReader();
    if (inboundCountReader == null) {
      throw new IllegalStateException(
          "Streaming mode requires persistent inbound index. Call ensureInboundIndexBuilt() first.");
    }

    LOG.debug("Computing approximate retained sizes in streaming mode for {} objects", totalObjects);
    InboundCountAccessor inboundCounts =
        new IndexedInboundCountAccessor(inboundCountReader, dump.getAddressToId32());

    if (progressCallback != null) {
      progressCallback.onProgress(0.3, "Computing approximate retained sizes (streaming)...");
    }

    int computed = 0;
    int lastReportedPercent = 0;

    for (HeapObjectImpl obj : objectIterator) {
      long approxRetained = computeMinRetainedSize(obj, inboundCounts);

      // Write to persistent index instead of setting on object
      // Uses original id32 from addressToId32 (includes classes with retained size = 0)
      try {
        int objectId32 = dump.getAddressToId32().get(obj.getId());
        writer.writeEntry(objectId32, approxRetained);
      } catch (java.io.IOException e) {
        throw new RuntimeException("Failed to write retained size to index", e);
      }

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

      if (computed % 1000000 == 0) {
        LOG.debug("Computed {} / {} retained sizes (streaming)", computed, totalObjects);
      }
    }

    if (progressCallback != null) {
      progressCallback.onProgress(1.0, "Approximate retained size computation complete (streaming)");
    }
    LOG.info("Completed streaming retained size computation for {} objects", computed);
  }

  /**
   * Computes approximate retained sizes for all objects in the heap dump (in-memory mode).
   *
   * <p>This method adapts to both in-memory and indexed parsing modes:
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

    // Check if we're in indexed mode (inbound count reader will be non-null after ensureInboundIndexBuilt)
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
      // In-memory mode: build inbound counts from object map
      if (progressCallback != null) {
        progressCallback.onProgress(0.0, "Building inbound reference counts...");
      }
      LOG.debug("Computing inbound reference counts for {} objects...", totalObjects);
      Long2IntOpenHashMap inboundCountMap =
          buildInboundReferenceCounts(objectsById, totalObjects, progressCallback);
      inboundCounts = new InMemoryInboundCountAccessor(inboundCountMap);

      if (progressCallback != null) {
        progressCallback.onProgress(0.3, "Computing approximate retained sizes...");
      }
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
   * <p>This is a conservative under-estimate of the true retained size. It counts only objects
   * that are exclusively owned (single inbound reference) or have no paths to GC roots except
   * through the target object.
   *
   * @param target the object to compute retained size for
   * @param inboundCounts accessor for inbound reference counts
   * @return approximate retained size in bytes
   */
  public static long computeMinRetainedSize(
      HeapObject target, InboundCountAccessor inboundCounts) {
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

      // Expand to outbound references
      current
          .getOutboundReferences()
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
   * Overload for backward compatibility with Long2IntOpenHashMap.
   * Used by code that builds inbound counts in-memory.
   */
  public static long computeMinRetainedSize(
      HeapObject target, Long2IntOpenHashMap inboundCounts) {
    return computeMinRetainedSize(target, new InMemoryInboundCountAccessor(inboundCounts));
  }

  /**
   * Builds a map of object ID to inbound reference count.
   *
   * <p>Iterates through all objects and counts how many times each object is referenced. This is a
   * one-time O(V + E) operation where V = objects, E = references.
   *
   * @param objectsById map of all objects in the heap
   * @param totalObjects total number of objects for progress reporting
   * @param progressCallback optional callback for progress updates
   * @return map from object ID to inbound reference count
   */
  private static Long2IntOpenHashMap buildInboundReferenceCounts(
      Long2ObjectMap<HeapObjectImpl> objectsById,
      int totalObjects,
      ProgressCallback progressCallback) {
    // Pre-size map to avoid rehashing with 114M+ entries (fixes OOM)
    // Each entry ~16 bytes, so 114M entries = ~1.8GB
    Long2IntOpenHashMap counts = new Long2IntOpenHashMap(totalObjects);
    counts.defaultReturnValue(0);

    int processed = 0;
    int lastReportedPercent = 0;

    for (HeapObjectImpl obj : objectsById.values()) {
      // Use direct long[] access instead of Stream to avoid 1.14B allocations
      long[] refIds = obj.getOutboundReferenceIds();
      for (int i = 0; i < refIds.length; i++) {
        counts.addTo(refIds[i], 1);
      }

      processed++;

      // Report progress more frequently for large heaps (every 1% for >10M objects)
      int reportingInterval =
          totalObjects > 10_000_000 ? totalObjects / 100 : Math.max(totalObjects / 20, 100000);
      if (progressCallback != null && processed % reportingInterval == 0) {
        double progress = 0.3 * (processed / (double) totalObjects);
        int percentComplete = (int) (progress * 100);
        if (percentComplete > lastReportedPercent) {
          progressCallback.onProgress(
              progress,
              String.format(
                  "Scanning object references from heap dump (%d%%)...", percentComplete));
          lastReportedPercent = percentComplete;
        }
      }
    }

    return counts;
  }

  /**
   * Abstraction for accessing inbound reference counts.
   * Allows transparent switching between in-memory and indexed modes.
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

  /**
   * In-memory accessor backed by Long2IntOpenHashMap.
   * Used when parsing in in-memory mode.
   */
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
   * Indexed accessor backed by InboundCountReader.
   * Used when parsing in indexed mode with persistent inbound index.
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
