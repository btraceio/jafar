package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.impl.HeapDumpImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Computes per-thread object ownership by walking the dominator tree from THREAD_OBJ GC roots.
 *
 * <p>Each object reachable in the dominator tree from a THREAD_OBJ root is attributed to that
 * thread. Objects not reachable from any thread root are attributed to {@code "shared"}.
 */
public final class ThreadOwnershipAnalyzer {

  private ThreadOwnershipAnalyzer() {}

  /** Per-thread memory statistics. */
  public record Stats(String threadName, long dominated, int dominatedCount) {}

  /** Result of the ownership computation. */
  public record Result(
      Map<Long, String> ownerNameByObjectId, Map<Long, Stats> statsByThreadObjId) {}

  /**
   * Computes thread ownership for all objects in the heap.
   *
   * @param dump the heap dump (must have full dominator tree computed)
   * @return ownership result
   */
  public static Result compute(HeapDump dump) {
    if (!(dump instanceof HeapDumpImpl impl)) {
      return new Result(Map.of(), Map.of());
    }

    // Build serial -> name and serial -> threadObjId maps from THREAD_OBJ roots
    Map<Integer, String> serialToName = new HashMap<>();
    Map<Integer, Long> serialToThreadObjId = new HashMap<>();
    dump.getGcRoots(GcRoot.Type.THREAD_OBJ)
        .forEach(
            root -> {
              int serial = root.getThreadObjSerial();
              long objId = root.getObjectId();
              String name = extractThreadName(root.getObject());
              serialToName.put(serial, name != null ? name : "thread-" + serial);
              serialToThreadObjId.put(serial, objId);
            });

    // Collect JAVA_FRAME root object IDs grouped by thread serial
    Map<Integer, List<Long>> framesBySerial = new HashMap<>();
    dump.getGcRoots(GcRoot.Type.JAVA_FRAME)
        .forEach(
            root -> {
              int serial = root.getThreadSerial();
              framesBySerial
                  .computeIfAbsent(serial, k -> new ArrayList<>())
                  .add(root.getObjectId());
            });

    Long2ObjectOpenHashMap<String> ownerByObjId = new Long2ObjectOpenHashMap<>();
    Long2ObjectOpenHashMap<Stats> statsByThreadObjId = new Long2ObjectOpenHashMap<>();

    // BFS over the dominator subtree rooted at each Thread object
    for (Map.Entry<Integer, Long> entry : serialToThreadObjId.entrySet()) {
      int serial = entry.getKey();
      long threadObjId = entry.getValue();
      String threadName = serialToName.get(serial);

      HeapObject threadObj = dump.getObjectById(threadObjId).orElse(null);
      if (threadObj == null) continue;

      long dominated = threadObj.getRetainedSize();

      Queue<HeapObject> queue = new ArrayDeque<>();
      queue.add(threadObj);
      int dominatedCount = 0;

      while (!queue.isEmpty()) {
        HeapObject obj = queue.poll();
        long id = obj.getId();
        if (ownerByObjId.containsKey(id)) continue; // primitive long overload — no boxing
        ownerByObjId.put(id, threadName);
        dominatedCount++;
        for (HeapObject child : impl.getDominatedObjects(obj)) {
          if (!ownerByObjId.containsKey(child.getId())) {
            queue.add(child);
          }
        }
      }

      // Mark JAVA_FRAME roots for this thread (ownership only, not counted in dominatedCount)
      List<Long> frameIds = framesBySerial.get(serial);
      if (frameIds != null) {
        for (long frameId : frameIds) {
          if (!ownerByObjId.containsKey(frameId)) {
            ownerByObjId.put(frameId, threadName);
          }
        }
      }

      statsByThreadObjId.put(threadObjId, new Stats(threadName, dominated, dominatedCount));
    }

    return new Result(ownerByObjId, statsByThreadObjId);
  }

  /**
   * Extracts the thread name from a {@code java.lang.Thread} heap object.
   *
   * @param threadObj the Thread heap object, or null
   * @return thread name, or null if unavailable
   */
  public static String extractThreadName(HeapObject threadObj) {
    if (threadObj == null) return null;
    Object nameField = threadObj.getFieldValue("name");
    if (nameField instanceof HeapObject nameObj) {
      return nameObj.getStringValue();
    }
    if (nameField instanceof String s) {
      return s;
    }
    return null;
  }
}
