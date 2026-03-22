package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.util.ClassNameUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects structurally identical object subgraphs using FNV-1a 64-bit fingerprinting.
 *
 * <p>Algorithm (two-pass, allocation-bounded):
 *
 * <ol>
 *   <li><strong>Pass 1</strong> — stream all objects once and count how many objects share the same
 *       {@code (className, shallowSize)} key. Only the counts are stored ({@code Map<String,
 *       Integer>}). No {@code HeapObject} references are retained.
 *   <li><strong>Pass 2</strong> — stream all objects again. Skip objects whose {@code (className,
 *       shallowSize)} group has fewer than 2 members. For each candidate, compute the structural
 *       fingerprint on the fly and record only the object <em>ID</em> ({@code long}) in the
 *       fingerprint group. No {@code HeapObject} references are retained beyond the current stream
 *       element.
 *   <li>Drop singleton fingerprint groups; sort by {@code wastedBytes} descending; build result
 *       rows.
 * </ol>
 *
 * <p>Peak memory is {@code O(candidate_objects)} longs rather than {@code O(all_objects)} {@code
 * HeapObject} references, which avoids OOM on large heaps.
 */
public final class SubgraphFingerprinter {

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x00000100000001b3L;

  /** Sentinel returned when a back-edge (cycle) is detected during fingerprinting. */
  private static final long BACK_EDGE_SENTINEL = 0xdeadbeefcafebabeL;

  /** Fingerprinting result: duplicate group rows and a map from group ID to member object IDs. */
  public record Result(List<Map<String, Object>> rows, Map<Integer, long[]> memberIds) {}

  private SubgraphFingerprinter() {}

  /**
   * Computes duplicate subgraph groups for all objects in the heap.
   *
   * @param dump the heap dump to analyse
   * @param depth fingerprint depth (0 = class name only; higher = deeper field traversal)
   * @return result containing duplicate group rows and member ID index
   */
  public static Result compute(HeapDump dump, int depth) {
    // Pass 1: count objects per (className#shallowSize) group.
    // Stores only String keys + int counts — no HeapObject references.
    Map<String, Integer> groupCounts = new HashMap<>();
    dump.getObjects()
        .forEach(
            obj -> {
              HeapClass cls = obj.getHeapClass();
              if (cls == null) return;
              String key = cls.getName() + "#" + obj.getShallowSize();
              groupCounts.merge(key, 1, Integer::sum);
            });

    // Pass 2: for candidate groups only, compute fingerprint on the fly and store object IDs.
    // FingerprintGroup stores scalars + List<Long> IDs — no HeapObject references.
    Map<Long, FingerprintGroup> byFingerprint = new HashMap<>();
    dump.getObjects()
        .forEach(
            obj -> {
              HeapClass cls = obj.getHeapClass();
              if (cls == null) return;
              String key = cls.getName() + "#" + obj.getShallowSize();
              if (groupCounts.getOrDefault(key, 0) < 2) return;

              int[] nodeCount = {0};
              long fp = computeFingerprint(obj, depth, new ArrayDeque<>(), nodeCount);

              long shallowSize = obj.getShallowSize();
              String humanName =
                  cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";
              int nc = nodeCount[0];

              byFingerprint
                  .computeIfAbsent(fp, k -> new FingerprintGroup(shallowSize, humanName, nc))
                  .ids
                  .add(obj.getId());
            });

    // Drop singletons, sort by wastedBytes desc, build rows
    List<Map.Entry<Long, FingerprintGroup>> survivors =
        byFingerprint.entrySet().stream()
            .filter(e -> e.getValue().ids.size() >= 2)
            .sorted(
                (a, b) -> {
                  long wa = (long) (a.getValue().ids.size() - 1) * a.getValue().shallowSize;
                  long wb = (long) (b.getValue().ids.size() - 1) * b.getValue().shallowSize;
                  return Long.compare(wb, wa);
                })
            .toList();

    List<Map<String, Object>> rows = new ArrayList<>(survivors.size());
    Map<Integer, long[]> memberIds = new LinkedHashMap<>(survivors.size() * 2);
    int groupId = 1;

    for (Map.Entry<Long, FingerprintGroup> entry : survivors) {
      FingerprintGroup group = entry.getValue();
      int copies = group.ids.size();
      long wastedBytes = (long) (copies - 1) * group.shallowSize;

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(HdumpPath.DuplicateFields.ID, groupId);
      row.put(HdumpPath.DuplicateFields.ROOT_CLASS, group.className);
      row.put(HdumpPath.DuplicateFields.FINGERPRINT, String.format("%016x", entry.getKey()));
      row.put(HdumpPath.DuplicateFields.COPIES, copies);
      row.put(HdumpPath.DuplicateFields.UNIQUE_SIZE, group.shallowSize);
      row.put(HdumpPath.DuplicateFields.WASTED_BYTES, wastedBytes);
      row.put(HdumpPath.DuplicateFields.DEPTH, depth);
      row.put(HdumpPath.DuplicateFields.NODE_COUNT, group.nodeCount);
      rows.add(row);

      long[] ids = group.ids.stream().mapToLong(Long::longValue).toArray();
      memberIds.put(groupId, ids);
      groupId++;
    }

    return new Result(rows, memberIds);
  }

  /**
   * Recursively computes the structural fingerprint of {@code obj}.
   *
   * @param obj object to fingerprint
   * @param depth remaining recursion depth (0 = class name only)
   * @param path stack of ancestor object IDs for cycle detection
   * @param nodeCount mutable counter incremented for each unique object visited
   * @return FNV-1a 64-bit fingerprint
   */
  private static long computeFingerprint(
      HeapObject obj, int depth, ArrayDeque<Long> path, int[] nodeCount) {
    long id = obj.getId();

    if (path.contains(id)) {
      return BACK_EDGE_SENTINEL;
    }

    nodeCount[0]++;

    if (depth == 0) {
      HeapClass cls = obj.getHeapClass();
      return fnv1a(cls != null ? cls.getName() : "");
    }

    path.push(id);
    try {
      HeapClass cls = obj.getHeapClass();
      String className = cls != null ? cls.getName() : "";

      if (obj.isArray()) {
        return fingerprintArray(obj, className, depth, path, nodeCount);
      } else {
        return fingerprintInstance(obj, cls, className, depth, path, nodeCount);
      }
    } finally {
      path.pop();
    }
  }

  private static long fingerprintInstance(
      HeapObject obj,
      HeapClass cls,
      String className,
      int depth,
      ArrayDeque<Long> path,
      int[] nodeCount) {
    long h = fnv1a(className);

    String strVal = obj.getStringValue();
    if (strVal != null) {
      h = mix(h, fnv1a(strVal));
    }

    if (cls == null) return h;

    List<HeapField> fields = new ArrayList<>(cls.getAllInstanceFields());
    fields.sort(Comparator.comparing(HeapField::getName));

    for (HeapField field : fields) {
      h = mix(h, fnv1a(field.getName()));
      h = mix(h, fnv1a(field.getTypeName()));

      Object val = obj.getFieldValue(field.getName());
      if (val == null) {
        h = mix(h, 0L);
      } else if (val instanceof HeapObject refObj) {
        h = mix(h, computeFingerprint(refObj, depth - 1, path, nodeCount));
      } else {
        h = mix(h, hashPrimitive(val));
      }
    }

    return h;
  }

  private static long fingerprintArray(
      HeapObject obj, String className, int depth, ArrayDeque<Long> path, int[] nodeCount) {
    int len = obj.getArrayLength();
    long h = mix(fnv1a(className), len);

    Object[] elements = obj.getArrayElements();
    if (elements == null) return h;

    if (len <= 100) {
      for (Object elem : elements) {
        h = mix(h, hashElement(elem, depth, path, nodeCount));
      }
    } else {
      for (int i = 0; i < 10; i++) {
        h = mix(h, hashElement(elements[i], depth, path, nodeCount));
      }
      for (int i = len - 10; i < len; i++) {
        h = mix(h, hashElement(elements[i], depth, path, nodeCount));
      }
    }

    return h;
  }

  private static long hashElement(Object elem, int depth, ArrayDeque<Long> path, int[] nodeCount) {
    if (elem == null) return 0L;
    if (elem instanceof HeapObject refObj) {
      return computeFingerprint(refObj, depth - 1, path, nodeCount);
    }
    return hashPrimitive(elem);
  }

  private static long fnv1a(String s) {
    long h = FNV_OFFSET;
    if (s == null) return h;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xFFL);
      h *= FNV_PRIME;
    }
    return h;
  }

  private static long mix(long h, long v) {
    return (h ^ v) * FNV_PRIME;
  }

  private static long hashPrimitive(Object v) {
    return switch (v) {
      case Boolean b -> b ? 1L : 0L;
      case Byte b -> (long) b;
      case Character c -> (long) c;
      case Short s -> (long) s;
      case Integer i -> (long) i;
      case Long l -> l;
      case Float f -> Float.floatToRawIntBits(f);
      case Double d -> Double.doubleToRawLongBits(d);
      default -> fnv1a(v.toString());
    };
  }

  /** Holds the metadata and member IDs for one fingerprint group. No HeapObject references. */
  private static final class FingerprintGroup {
    final long shallowSize;
    final String className;
    final int nodeCount;
    final List<Long> ids = new ArrayList<>();

    FingerprintGroup(long shallowSize, String className, int nodeCount) {
      this.shallowSize = shallowSize;
      this.className = className;
      this.nodeCount = nodeCount;
    }
  }
}
