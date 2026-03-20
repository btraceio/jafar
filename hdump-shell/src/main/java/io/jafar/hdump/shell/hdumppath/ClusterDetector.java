package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import io.jafar.hdump.util.ClassNameUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects leak clusters by identifying densely-connected subgraphs with high retained size but few
 * GC root anchors. Uses label propagation on the reference graph.
 */
public final class ClusterDetector {

  /**
   * Label propagation is skipped for heaps above this size to avoid excessive memory usage from
   * bidirectional adjacency arrays (~8 bytes per edge pair). For larger heaps, clusters are formed
   * from initial neighbor-based seeding only.
   */
  static final int MAX_LABEL_PROPAGATION_OBJECTS = 5_000_000;

  /** Detection result containing cluster rows and a membership index. */
  public record Result(List<Map<String, Object>> rows, Map<Integer, long[]> membership) {}

  private ClusterDetector() {}

  /**
   * Detects leak clusters in the heap dump.
   *
   * @param dump the heap dump to analyze
   * @param minRetainedSize minimum retained size for a cluster to be reported (bytes)
   * @param maxIterations maximum label propagation iterations (capped at 20)
   * @return detection result with cluster rows and membership index
   */
  public static Result detect(HeapDump dump, long minRetainedSize, int maxIterations) {
    maxIterations = Math.min(maxIterations, 20);
    int objectCount = dump.getObjectCount();
    if (objectCount == 0) {
      return new Result(List.of(), Map.of());
    }

    // Build GC root lookup: objectId -> root type
    Map<Long, GcRoot.Type> gcRootTypes = new HashMap<>();
    for (GcRoot root : dump.getGcRoots()) {
      gcRootTypes.put(root.getObjectId(), root.getType());
    }

    // Pass 1: Build ID index and cache per-object scoring data.
    // A second pass is needed for reference resolution because forward references (object A
    // referencing object B where B appears later in the stream) cannot be resolved until all
    // object IDs are indexed.
    long[] idIndex = new long[objectCount];
    long[] retainedSizes = new long[objectCount];
    String[] classNames = new String[objectCount];
    Map<Long, Integer> idToSeq = new HashMap<>(objectCount * 4 / 3 + 1);
    int idx = 0;
    var objectIter = dump.getObjects().iterator();
    while (objectIter.hasNext()) {
      HeapObject obj = objectIter.next();
      idIndex[idx] = obj.getId();
      idToSeq.put(obj.getId(), idx);
      retainedSizes[idx] = obj.getRetainedSize();
      HeapClass cls = obj.getHeapClass();
      classNames[idx] = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : null;
      idx++;
    }
    int actualCount = idx;

    boolean useLabelPropagation = actualCount <= MAX_LABEL_PROPAGATION_OBJECTS && maxIterations > 0;

    // Pass 2: Seed labels and build adjacency (requires all IDs indexed for ref resolution)
    int[] labels = new int[actualCount];
    int[] edgeCounts = useLabelPropagation ? new int[actualCount] : null;
    int[][] tempRefs = useLabelPropagation ? new int[actualCount][] : null;
    int[] refBuf = new int[64];

    var objIter2 = dump.getObjects().iterator();
    idx = 0;
    while (objIter2.hasNext()) {
      HeapObject obj = objIter2.next();
      long[] refIds = getOutboundRefIds(obj);

      // Resolve ref IDs to sequential indices
      int refCount = 0;
      if (refBuf.length < refIds.length) {
        refBuf = new int[refIds.length];
      }
      for (long refId : refIds) {
        Integer refSeq = idToSeq.get(refId);
        if (refSeq != null) {
          refBuf[refCount++] = refSeq;
        }
      }

      // Seed label
      if (gcRootTypes.containsKey(obj.getId())) {
        labels[idx] = idx;
      } else if (refCount > 0) {
        labels[idx] = refBuf[0];
      } else {
        labels[idx] = idx;
      }

      if (useLabelPropagation) {
        int[] trimmed = new int[refCount];
        System.arraycopy(refBuf, 0, trimmed, 0, refCount);
        tempRefs[idx] = trimmed;
        // Count bidirectional edges
        edgeCounts[idx] += refCount;
        for (int r = 0; r < refCount; r++) {
          edgeCounts[refBuf[r]]++;
        }
      }
      idx++;
    }

    // Build compact bidirectional adjacency arrays from edge counts and tempRefs
    int[][] adjacency = null;
    if (useLabelPropagation) {
      adjacency = new int[actualCount][];
      int[] fillPos = new int[actualCount];
      for (int i = 0; i < actualCount; i++) {
        adjacency[i] = new int[edgeCounts[i]];
      }
      for (int i = 0; i < actualCount; i++) {
        int[] refs = tempRefs[i];
        for (int ref : refs) {
          adjacency[i][fillPos[i]++] = ref;
          adjacency[ref][fillPos[ref]++] = i;
        }
      }
      tempRefs = null;
      edgeCounts = null;
    }

    // Label propagation: adopt majority label among neighbors
    // Reuse a single map across all nodes to avoid per-node allocation
    if (useLabelPropagation) {
      Map<Integer, int[]> labelCounts = new HashMap<>();
      for (int iter = 0; iter < maxIterations; iter++) {
        int changedCount = 0;
        for (int i = 0; i < actualCount; i++) {
          int[] refs = adjacency[i];
          if (refs.length == 0) continue;

          labelCounts.clear();
          labelCounts.computeIfAbsent(labels[i], k -> new int[1])[0] = 1;
          for (int refIdx : refs) {
            int[] cnt = labelCounts.get(labels[refIdx]);
            if (cnt == null) {
              cnt = new int[1];
              labelCounts.put(labels[refIdx], cnt);
            }
            cnt[0]++;
          }

          int bestLabel = labels[i];
          int bestCount = 0;
          for (var e : labelCounts.entrySet()) {
            if (e.getValue()[0] > bestCount) {
              bestCount = e.getValue()[0];
              bestLabel = e.getKey();
            }
          }
          if (bestLabel != labels[i]) {
            labels[i] = bestLabel;
            changedCount++;
          }
        }
        // Early termination if <0.1% labels changed
        if (changedCount < actualCount / 1000) {
          break;
        }
      }
    }

    adjacency = null; // Free

    // Form clusters — group by final label
    Map<Integer, List<Integer>> clusterMembers = new HashMap<>();
    for (int i = 0; i < actualCount; i++) {
      clusterMembers.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
    }

    // Score clusters using cached per-object data (no random HeapDump lookups)
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<Integer, long[]> membership = new LinkedHashMap<>();
    int clusterId = 1;

    for (var entry : clusterMembers.entrySet()) {
      List<Integer> members = entry.getValue();
      if (members.size() <= 1) continue;

      long retainedSize = 0;
      Map<String, Integer> classCounts = new HashMap<>();
      for (int memberIdx : members) {
        long ret = retainedSizes[memberIdx];
        if (ret > 0) retainedSize += ret;

        String name = classNames[memberIdx];
        if (name != null) {
          classCounts.merge(name, 1, Integer::sum);
        }
      }

      if (retainedSize < minRetainedSize) continue;

      // Find dominant class
      String dominantClass = "unknown";
      int maxCount = 0;
      for (var ce : classCounts.entrySet()) {
        if (ce.getValue() > maxCount) {
          maxCount = ce.getValue();
          dominantClass = ce.getKey();
        }
      }

      // Sample GC root paths (up to 10 objects)
      int rootPathCount = 0;
      Set<Long> seenRootIds = new HashSet<>();
      String anchorType = "UNKNOWN";
      String anchorObject = "unknown";
      int sampled = 0;
      for (int memberIdx : members) {
        if (sampled >= 10) break;
        long objId = idIndex[memberIdx];
        HeapObject obj = dump.getObjectById(objId).orElse(null);
        if (obj == null) continue;

        List<PathStep> path = dump.findPathToGcRoot(obj);
        if (!path.isEmpty()) {
          HeapObject rootObj = path.get(0).object();
          if (rootObj != null && seenRootIds.add(rootObj.getId())) {
            rootPathCount++;
          }
          if (sampled == 0 && rootObj != null) {
            anchorObject = rootObj.getDescription();
            // O(1) anchor type lookup via pre-built map
            GcRoot.Type type = gcRootTypes.get(rootObj.getId());
            if (type != null) {
              anchorType = type.name();
            }
          }
        }
        sampled++;
      }
      // Prevent division by zero; clusters without sampled root paths are scored as if anchored
      // once
      if (rootPathCount == 0) rootPathCount = 1;

      double score = (double) retainedSize / rootPathCount;

      // Build membership array
      long[] memberIds = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        memberIds[i] = idIndex[members.get(i)];
      }
      membership.put(clusterId, memberIds);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(HdumpPath.ClusterFields.ID, clusterId);
      row.put(HdumpPath.ClusterFields.OBJECT_COUNT, members.size());
      row.put(HdumpPath.ClusterFields.RETAINED_SIZE, retainedSize);
      row.put(HdumpPath.ClusterFields.ROOT_PATH_COUNT, rootPathCount);
      row.put(HdumpPath.ClusterFields.SCORE, score);
      row.put(HdumpPath.ClusterFields.DOMINANT_CLASS, dominantClass);
      row.put(HdumpPath.ClusterFields.ANCHOR_TYPE, anchorType);
      row.put(HdumpPath.ClusterFields.ANCHOR_OBJECT, anchorObject);
      rows.add(row);

      clusterId++;
    }

    return new Result(rows, membership);
  }

  /** Extracts outbound reference IDs from a heap object via the public streaming API. */
  private static long[] getOutboundRefIds(HeapObject obj) {
    return obj.getOutboundReferences().mapToLong(HeapObject::getId).toArray();
  }
}
