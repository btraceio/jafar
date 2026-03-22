package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.util.ClassNameUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
      long retained = obj.getRetainedSizeIfAvailable();
      retainedSizes[idx] = retained >= 0 ? retained : obj.getShallowSize();
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

    // Label propagation: adopt majority label among neighbors.
    // Uses an allocation-free voting scheme: labels are node indices in [0, actualCount), so
    // votes[label] directly counts occurrences. `touched` tracks which entries to zero out
    // after each node to avoid a full O(N) clear per node.
    if (useLabelPropagation) {
      int[] votes = new int[actualCount]; // indexed by label value; all-zero initially
      int[] touched = new int[64]; // labels seen for current node; grown as needed
      for (int iter = 0; iter < maxIterations; iter++) {
        int changedCount = 0;
        for (int i = 0; i < actualCount; i++) {
          int[] refs = adjacency[i];
          if (refs.length == 0) continue;

          // Ensure touched buffer is large enough (degree + 1 for self)
          int needed = refs.length + 1;
          if (touched.length < needed) {
            touched = new int[needed * 2];
          }

          // Tally votes: self label counts as 1
          int touchedCount = 0;
          int selfLabel = labels[i];
          votes[selfLabel] = 1;
          touched[touchedCount++] = selfLabel;

          for (int refIdx : refs) {
            int lbl = labels[refIdx];
            if (votes[lbl] == 0) {
              touched[touchedCount++] = lbl;
            }
            votes[lbl]++;
          }

          // Find the label with the most votes; break ties by preferring the smaller label
          // for deterministic convergence
          int bestLabel = selfLabel;
          int bestCount = votes[selfLabel];
          for (int t = 0; t < touchedCount; t++) {
            int lbl = touched[t];
            if (votes[lbl] > bestCount || (votes[lbl] == bestCount && lbl < bestLabel)) {
              bestCount = votes[lbl];
              bestLabel = lbl;
            }
          }

          // Reset only the touched entries (avoids full O(N) clear)
          for (int t = 0; t < touchedCount; t++) {
            votes[touched[t]] = 0;
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

      // Determine anchor type and root path count using the pre-built gcRootTypes map.
      // This avoids O(N) findPathToGcRoot calls per cluster (which caused O(clusters×N) total).
      int rootPathCount = 0;
      String anchorType = "UNKNOWN";
      String anchorObject = "unknown";
      for (int memberIdx : members) {
        long objId = idIndex[memberIdx];
        GcRoot.Type rootType = gcRootTypes.get(objId);
        if (rootType != null) {
          rootPathCount++;
          if ("UNKNOWN".equals(anchorType)) {
            anchorType = rootType.name();
            String cls = classNames[memberIdx];
            anchorObject = (cls != null ? cls : "unknown") + "@" + Long.toHexString(objId);
          }
        }
      }
      // Fall back to label node if no direct GC root found in cluster members
      if (rootPathCount == 0) {
        int labelIdx = entry.getKey();
        if (labelIdx < actualCount) {
          long labelId = idIndex[labelIdx];
          GcRoot.Type labelRootType = gcRootTypes.get(labelId);
          if (labelRootType != null) {
            anchorType = labelRootType.name();
            String cls = classNames[labelIdx];
            anchorObject = (cls != null ? cls : "unknown") + "@" + Long.toHexString(labelId);
          }
        }
        rootPathCount = 1; // prevent division by zero
      }

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
