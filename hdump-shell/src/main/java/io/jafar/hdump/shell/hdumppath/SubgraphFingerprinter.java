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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects structurally identical object subgraphs using FNV-1a 64-bit fingerprinting.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Group objects by {@code (className, shallowSize)} — a necessary cheap pre-filter.
 *   <li>Drop pre-groups with fewer than 2 members.
 *   <li>Within each surviving pre-group, compute a structural fingerprint for every member.
 *   <li>Group by fingerprint; identical fingerprint means identical subtree.
 *   <li>Drop singleton fingerprint groups; sort survivors by {@code wastedBytes} descending.
 * </ol>
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
    // Step 1: group by (className, shallowSize) as a cheap pre-filter
    Map<String, List<HeapObject>> byClassAndSize = new LinkedHashMap<>();
    dump.getObjects()
        .forEach(
            obj -> {
              HeapClass cls = obj.getHeapClass();
              if (cls == null) return;
              String key = cls.getName() + "#" + obj.getShallowSize();
              byClassAndSize.computeIfAbsent(key, k -> new ArrayList<>()).add(obj);
            });

    // Step 2+3: drop pre-groups with <2 members, then fingerprint survivors
    Map<Long, List<HeapObject>> byFingerprint = new LinkedHashMap<>();
    for (List<HeapObject> group : byClassAndSize.values()) {
      if (group.size() < 2) continue;
      for (HeapObject obj : group) {
        int[] nodeCount = {0};
        long fp = computeFingerprint(obj, depth, new ArrayDeque<>(), nodeCount);
        byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(obj);
      }
    }

    // Step 4+5: drop singletons, sort by wastedBytes desc, build rows
    List<Map.Entry<Long, List<HeapObject>>> survivors =
        byFingerprint.entrySet().stream()
            .filter(e -> e.getValue().size() >= 2)
            .sorted(
                (a, b) -> {
                  long wa = (long) (a.getValue().size() - 1) * a.getValue().get(0).getShallowSize();
                  long wb = (long) (b.getValue().size() - 1) * b.getValue().get(0).getShallowSize();
                  return Long.compare(wb, wa);
                })
            .toList();

    List<Map<String, Object>> rows = new ArrayList<>(survivors.size());
    Map<Integer, long[]> memberIds = new LinkedHashMap<>(survivors.size() * 2);
    int groupId = 1;

    for (Map.Entry<Long, List<HeapObject>> entry : survivors) {
      List<HeapObject> members = entry.getValue();
      HeapObject first = members.get(0);
      int copies = members.size();
      long uniqueSize = first.getShallowSize();
      long wastedBytes = (long) (copies - 1) * uniqueSize;

      // Recompute nodeCount for one representative member
      int[] nodeCount = {0};
      computeFingerprint(first, depth, new ArrayDeque<>(), nodeCount);

      HeapClass cls = first.getHeapClass();
      String className = cls != null ? ClassNameUtil.toHumanReadable(cls.getName()) : "unknown";

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(HdumpPath.DuplicateFields.ID, groupId);
      row.put(HdumpPath.DuplicateFields.ROOT_CLASS, className);
      row.put(HdumpPath.DuplicateFields.FINGERPRINT, String.format("%016x", entry.getKey()));
      row.put(HdumpPath.DuplicateFields.COPIES, copies);
      row.put(HdumpPath.DuplicateFields.UNIQUE_SIZE, uniqueSize);
      row.put(HdumpPath.DuplicateFields.WASTED_BYTES, wastedBytes);
      row.put(HdumpPath.DuplicateFields.DEPTH, depth);
      row.put(HdumpPath.DuplicateFields.NODE_COUNT, nodeCount[0]);
      rows.add(row);

      long[] ids = new long[copies];
      for (int i = 0; i < copies; i++) {
        ids[i] = members.get(i).getId();
      }
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

    // Cycle guard: if this object is already on the current path, return sentinel
    if (path.contains(id)) {
      return BACK_EDGE_SENTINEL;
    }

    nodeCount[0]++;

    if (depth == 0) {
      // Structural skeleton only: hash class name
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

    // Special case: java.lang.String — mix in the string value
    String strVal = obj.getStringValue();
    if (strVal != null) {
      h = mix(h, fnv1a(strVal));
    }

    if (cls == null) return h;

    // Sort fields by name for a deterministic traversal order
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
      // For large arrays hash only the first 10 and last 10 elements
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
}
