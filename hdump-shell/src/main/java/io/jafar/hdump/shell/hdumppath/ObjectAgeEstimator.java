package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Estimates the structural age of heap objects from a single snapshot.
 *
 * <p>Computes a 0–100 age score per object using three signals:
 *
 * <ol>
 *   <li><b>GC root type</b> — system classes and JNI globals score higher than thread-local roots
 *   <li><b>Inbound reference count</b> — objects referenced by many others have survived more GC
 *       cycles
 *   <li><b>BFS depth from GC roots</b> — objects closer to roots tend to be longer-lived
 * </ol>
 *
 * <p>Age buckets:
 *
 * <ul>
 *   <li>{@code ephemeral} — score 0–25
 *   <li>{@code medium} — score 26–50
 *   <li>{@code tenured} — score 51–75
 *   <li>{@code permanent} — score 76–100
 * </ul>
 *
 * <p>Computation is O(n + edges) via two passes: an inbound-count pass and a multi-source BFS from
 * all GC roots through the outbound reference graph. No dominator tree is required.
 */
public final class ObjectAgeEstimator {

  private ObjectAgeEstimator() {}

  /** Per-object age estimation data, computed on demand from the cached result maps. */
  public record AgeData(int score, String bucket, String signals) {}

  /**
   * Cached result of an age estimation run. Holds the three raw signal maps; {@link
   * #getAgeData(long)} computes {@link AgeData} on demand to avoid storing strings for every
   * object.
   */
  public static final class Result {

    final Long2ObjectOpenHashMap<GcRoot.Type> rootTypeMap;
    final Long2IntOpenHashMap inboundCountMap;
    final Long2IntOpenHashMap depthMap;

    Result(
        Long2ObjectOpenHashMap<GcRoot.Type> rootTypeMap,
        Long2IntOpenHashMap inboundCountMap,
        Long2IntOpenHashMap depthMap) {
      this.rootTypeMap = rootTypeMap;
      this.inboundCountMap = inboundCountMap;
      this.depthMap = depthMap;
    }

    /**
     * Returns age data for the given object ID, computing score, bucket, and signals on demand.
     *
     * @param objectId heap object ID
     * @return age data
     */
    public AgeData getAgeData(long objectId) {
      GcRoot.Type rootType =
          rootTypeMap.containsKey(objectId) ? rootTypeMap.get(objectId) : GcRoot.Type.UNKNOWN;
      int refs = inboundCountMap.getOrDefault(objectId, 0);
      int depth = depthMap.getOrDefault(objectId, 0);

      int rootScore =
          switch (rootType) {
            case STICKY_CLASS -> 30;
            case JNI_GLOBAL -> 25;
            case THREAD_OBJ -> 5;
            case JAVA_FRAME, JNI_LOCAL -> 0;
            default -> 10;
          };
      int refScore = Math.min(25, refs * 2);
      int depthScore = Math.max(0, 10 - depth);
      int total = Math.min(100, rootScore + refScore + depthScore);

      String bucket =
          total <= 25
              ? "ephemeral"
              : total <= 50 ? "medium" : total <= 75 ? "tenured" : "permanent";
      String signals =
          "root:"
              + rootType.name()
              + ":+"
              + rootScore
              + ",refs:"
              + refs
              + ":+"
              + refScore
              + ",depth:"
              + depth
              + ":+"
              + depthScore;

      return new AgeData(total, bucket, signals);
    }
  }

  /**
   * Computes age estimates for all objects in the heap.
   *
   * <p>Performs two passes:
   *
   * <ol>
   *   <li>Inbound count pass — iterates all outbound references to build an inbound count map
   *   <li>BFS pass — multi-source BFS from all GC roots to assign root type and depth per object
   * </ol>
   *
   * @param dump the heap dump
   * @return age estimation result
   */
  public static Result compute(HeapDump dump) {
    int capacity = dump.getObjectCount();

    // Pass 1: inbound reference count
    Long2IntOpenHashMap inboundCount = new Long2IntOpenHashMap(capacity);
    dump.getObjects()
        .forEach(
            obj ->
                obj.getOutboundReferences()
                    .forEach(
                        ref -> {
                          long rid = ref.getId();
                          inboundCount.put(rid, inboundCount.get(rid) + 1);
                        }));

    // Pass 2: multi-source BFS from all GC roots through outbound reference graph
    Long2ObjectOpenHashMap<GcRoot.Type> rootTypeMap = new Long2ObjectOpenHashMap<>(capacity);
    Long2IntOpenHashMap depthMap = new Long2IntOpenHashMap(capacity);

    Queue<HeapObject> queue = new ArrayDeque<>();

    dump.getGcRoots()
        .forEach(
            root -> {
              HeapObject obj = root.getObject();
              if (obj == null) return;
              long id = obj.getId();
              if (!rootTypeMap.containsKey(id)) {
                rootTypeMap.put(id, root.getType());
                depthMap.put(id, 0);
                queue.add(obj);
              }
            });

    while (!queue.isEmpty()) {
      HeapObject cur = queue.poll();
      long id = cur.getId();
      GcRoot.Type rootType = rootTypeMap.get(id);
      int childDepth = depthMap.get(id) + 1;

      cur.getOutboundReferences()
          .forEach(
              ref -> {
                long refId = ref.getId();
                if (!rootTypeMap.containsKey(refId)) {
                  rootTypeMap.put(refId, rootType);
                  depthMap.put(refId, childDepth);
                  queue.add(ref);
                }
              });
    }

    return new Result(rootTypeMap, inboundCount, depthMap);
  }
}
