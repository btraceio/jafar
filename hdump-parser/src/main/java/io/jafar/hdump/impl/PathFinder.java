package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Finds paths from heap objects to GC roots using breadth-first search.
 *
 * <p>This class implements reverse BFS (starting from GC roots) to find the shortest path to any
 * target object. The path represents the reference chain that keeps an object alive.
 *
 * <p><strong>Use case:</strong> Understanding why an object is not garbage collected. The returned
 * path shows the chain of references from a GC root to the target object.
 *
 * <p><strong>Performance:</strong> O(V + E) where V = reachable objects, E = edges. For large
 * heaps, computation may take several seconds but results can be cached.
 */
public final class PathFinder {

  private PathFinder() {}

  /**
   * Finds the shortest path from a GC root to the target object.
   *
   * <p>Returns a list of objects representing the path:
   *
   * <ul>
   *   <li>First element: A GC root object (Thread, static field holder, etc.)
   *   <li>Intermediate elements: Objects in the reference chain
   *   <li>Last element: The target object
   * </ul>
   *
   * <p>If the target is itself a GC root, returns a single-element list containing just the target.
   *
   * <p>Objects referenced from static fields are start points too: HPROF lists a class as a root
   * but a class is not a heap object, so its statics are the only way to reach what it holds. Such
   * a path starts at the referenced object, with {@code static <class>.<field>} as its field name.
   *
   * <p>If no path exists (unreachable object), returns an empty list.
   *
   * @param dump the heap dump
   * @param target the object to find a path to
   * @param gcRoots list of GC roots
   * @return path from GC root to target, or empty list if unreachable
   */
  /** Tracks the incoming edge for each visited object: the parent and the field name used. */
  private record Edge(HeapObject parent, String fieldName) {}

  public static List<PathStep> findShortestPath(
      HeapDump dump, HeapObject target, List<GcRootImpl> gcRoots) {
    if (target == null || gcRoots == null || gcRoots.isEmpty()) {
      return List.of();
    }

    long targetId = target.getId();

    // Check if target is itself a GC root
    for (GcRoot root : gcRoots) {
      if (root.getObjectId() == targetId) {
        return List.of(new PathStep(target, null));
      }
    }

    if (dump instanceof HeapDumpImpl impl) {
      HeapGraph graph = impl.pathGraph();
      return graph != null
          ? findShortestPathOnGraph(impl, graph, target, gcRoots)
          : findShortestPathById(impl, target, gcRoots);
    }

    // BFS from GC roots, tracking the incoming edge (parent + field name) per object
    Long2ObjectOpenHashMap<Edge> parents = new Long2ObjectOpenHashMap<>();
    LongOpenHashSet visited = new LongOpenHashSet();
    Deque<HeapObject> queue = new ArrayDeque<>();

    // Initialize with all GC root objects (no incoming edge)
    for (GcRoot root : gcRoots) {
      dump.getObjectById(root.getObjectId())
          .ifPresent(
              rootObj -> {
                if (!visited.contains(rootObj.getId())) {
                  visited.add(rootObj.getId());
                  queue.add(rootObj);
                  parents.put(rootObj.getId(), new Edge(null, null));
                }
              });
    }

    // Static fields hold objects without any object referencing them. Seeded after the GC roots, so
    // of two equally short paths the one from a real root wins.
    for (HeapClass cls : dump.getClasses()) {
      for (HeapField field : cls.getStaticFields()) {
        if (!field.isObjectRef() || !(field.getStaticValue() instanceof Long refId)) continue;
        dump.getObjectById(refId)
            .ifPresent(
                ref -> {
                  if (visited.add(ref.getId())) {
                    queue.add(ref);
                    parents.put(
                        ref.getId(),
                        new Edge(null, "static " + cls.getName() + "." + field.getName()));
                  }
                });
      }
    }

    // BFS until we find target or exhaust reachable objects
    boolean found = false;
    while (!queue.isEmpty()) {
      HeapObject current = queue.poll();

      if (current.getId() == targetId) {
        found = true;
        break;
      }

      visitOutboundEdges(
          current,
          (fieldName, ref) -> {
            if (!visited.contains(ref.getId())) {
              visited.add(ref.getId());
              parents.put(ref.getId(), new Edge(current, fieldName));
              queue.add(ref);
            }
          });
    }

    if (!found) {
      return List.of();
    }

    // Reconstruct path by following parent pointers backwards
    List<PathStep> path = new ArrayList<>();
    HeapObject current = target;
    while (current != null) {
      Edge edge = parents.get(current.getId());
      path.add(new PathStep(current, edge != null ? edge.fieldName() : null));
      current = edge != null ? edge.parent() : null;
    }

    // Reverse to get path from root to target
    Collections.reverse(path);
    return path;
  }

  /** Parent value of a BFS start object (GC root or static-field target). Ids are never 0. */
  private static final long START = 0L;

  /** Queries answered by a fast path (graph or id BFS); lets tests prove the dispatch. */
  static final java.util.concurrent.atomic.AtomicLong FAST_PATH_QUERIES =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * The same BFS as the generic path — same start order, same neighbour order, same tie breaking —
   * but over object ids and the cached strong-reference id arrays: no field-value maps are built
   * (they are cached per object, so the generic BFS keeps a decoded map for every object it
   * visits), no boxing, no per-element strings. Field names are resolved only for the objects on
   * the path found.
   *
   * <p>Equivalence with {@link #visitOutboundEdges}: for an instance, the strong reference ids are
   * the non-null object fields in {@link HeapClassImpl#getAllInstanceFields()} order, without
   * {@code referent}/{@code discovered} on {@code Reference} subclasses — the field-value map in
   * the same order, as long as no two fields share a name. Classes with a shadowed field name use
   * {@link #visitOutboundEdges} itself. For an object array, the ids are the non-null elements in
   * index order. Ids that name no object are skipped, as the generic path never sees them.
   */
  private static List<PathStep> findShortestPathById(
      HeapDumpImpl dump, HeapObject target, List<GcRootImpl> gcRoots) {
    long targetId = target.getId();
    FAST_PATH_QUERIES.incrementAndGet();
    Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    Long2ObjectOpenHashMap<String> startNames = new Long2ObjectOpenHashMap<>();
    LongArrayList queue = new LongArrayList();

    for (GcRoot root : gcRoots) {
      long id = root.getObjectId();
      if (dump.getObjectByIdInternal(id) != null && !parent.containsKey(id)) {
        parent.put(id, START);
        queue.add(id);
      }
    }
    for (HeapClass cls : dump.getClasses()) {
      for (HeapField field : cls.getStaticFields()) {
        if (!field.isObjectRef() || !(field.getStaticValue() instanceof Long refId)) continue;
        long id = refId;
        if (dump.getObjectByIdInternal(id) != null && !parent.containsKey(id)) {
          parent.put(id, START);
          startNames.put(id, "static " + cls.getName() + "." + field.getName());
          queue.add(id);
        }
      }
    }

    boolean found = false;
    for (int head = 0; head < queue.size(); head++) {
      long cur = queue.getLong(head);
      if (cur == targetId) {
        found = true;
        break;
      }
      HeapObjectImpl obj = dump.getObjectByIdInternal(cur);
      if (obj == null) {
        throw new IllegalStateException(
            "object 0x" + Long.toHexString(cur) + " could not be loaded from the index");
      }
      HeapClass cls = obj.getHeapClass();
      if (!obj.isArray() && cls instanceof HeapClassImpl c && c.hasDuplicateInstanceFieldNames()) {
        visitOutboundEdges(
            obj,
            (fieldName, ref) -> {
              long id = ref.getId();
              if (!parent.containsKey(id)) {
                parent.put(id, cur);
                queue.add(id);
              }
            });
        continue;
      }
      for (long id : obj.getStrongOutboundReferenceIds()) {
        if (!parent.containsKey(id) && dump.getObjectByIdInternal(id) != null) {
          parent.put(id, cur);
          queue.add(id);
        }
      }
    }
    if (!found) {
      return List.of();
    }

    LongArrayList ids = new LongArrayList();
    for (long id = targetId; id != START; id = parent.get(id)) {
      ids.add(id);
    }
    List<PathStep> path = new ArrayList<>(ids.size());
    HeapObject prev = null;
    for (int i = ids.size() - 1; i >= 0; i--) {
      long id = ids.getLong(i);
      HeapObject obj = i == 0 ? target : dump.getObjectByIdInternal(id);
      String fieldName = prev == null ? startNames.get(id) : firstFieldReferencing(prev, id);
      path.add(new PathStep(obj, fieldName));
      prev = obj;
    }
    return path;
  }

  /**
   * The BFS tree of the whole graph from the BFS start objects (GC roots, then static-field
   * targets): the parent of every reached node, and the start objects' names. A BFS that stops at a
   * target assigns every parent it assigns exactly as this full BFS does (a node's parent is fixed
   * when the node is discovered, and the order of discovery does not depend on the target), so the
   * path to any target is the parent chain in this tree.
   */
  static final class PathTree {
    static final int UNREACHED = -2;
    static final int START = -1;

    final int[] parent; // node -> parent node, START, or UNREACHED
    final Int2ObjectOpenHashMap<String> startNames; // static-field start node -> name
    private final long[] rootIds; // the GC root ids this tree was built from, in order

    private PathTree(int[] parent, Int2ObjectOpenHashMap<String> startNames, long[] rootIds) {
      this.parent = parent;
      this.startNames = startNames;
      this.rootIds = rootIds;
    }

    boolean builtFrom(List<GcRootImpl> gcRoots) {
      if (gcRoots.size() != rootIds.length) return false;
      for (int i = 0; i < rootIds.length; i++) {
        if (gcRoots.get(i).getObjectId() != rootIds[i]) return false;
      }
      return true;
    }
  }

  /** Full BFS over the dump's int graph; see {@link PathTree}. */
  static PathTree buildPathTree(HeapDumpImpl dump, HeapGraph g, List<GcRootImpl> gcRoots) {
    final int n = g.size();
    final int unvisited = PathTree.UNREACHED;
    final int start = PathTree.START;
    int[] parent = new int[n];
    Arrays.fill(parent, unvisited);
    int[] queue = new int[n];
    int tail = 0;
    Int2ObjectOpenHashMap<String> startNames = new Int2ObjectOpenHashMap<>();
    long[] rootIds = new long[gcRoots.size()];

    for (int i = 0; i < rootIds.length; i++) {
      long id = gcRoots.get(i).getObjectId();
      rootIds[i] = id;
      int v = g.nodeOf(id);
      if (v >= 0 && parent[v] == unvisited) {
        parent[v] = start;
        queue[tail++] = v;
      }
    }
    for (HeapClass cls : dump.getClasses()) {
      for (HeapField field : cls.getStaticFields()) {
        if (!field.isObjectRef() || !(field.getStaticValue() instanceof Long refId)) continue;
        int v = g.nodeOf(refId);
        if (v >= 0 && parent[v] == unvisited) {
          parent[v] = start;
          startNames.put(v, "static " + cls.getName() + "." + field.getName());
          queue[tail++] = v;
        }
      }
    }

    final int[] ss = g.succStart;
    final int[] su = g.succ;
    final java.util.BitSet shadowed = g.shadowedFieldNodes;
    for (int head = 0; head < tail; head++) {
      int cur = queue[head];
      if (shadowed.get(cur)) {
        int[] t = {tail};
        visitOutboundEdges(
            g.nodes[cur],
            (fieldName, ref) -> {
              int v = g.nodeOf(ref.getId());
              if (v >= 0 && parent[v] == unvisited) {
                parent[v] = cur;
                queue[t[0]++] = v;
              }
            });
        tail = t[0];
        continue;
      }
      for (int e = ss[cur], end = ss[cur + 1]; e < end; e++) {
        int v = su[e];
        if (parent[v] == unvisited) {
          parent[v] = cur;
          queue[tail++] = v;
        }
      }
    }
    return new PathTree(parent, startNames, rootIds);
  }

  /**
   * {@link #findShortestPathById} on the dump's cached int graph, answered from the dump's cached
   * {@link PathTree}: the same start order, neighbour order and tie breaking (graph rows are the
   * strong reference ids that name an object, in order). After the first query a query costs the
   * length of its path.
   */
  private static List<PathStep> findShortestPathOnGraph(
      HeapDumpImpl dump, HeapGraph g, HeapObject target, List<GcRootImpl> gcRoots) {
    FAST_PATH_QUERIES.incrementAndGet();
    int targetNode = g.nodeOf(target.getId());
    if (targetNode < 0) {
      return List.of();
    }
    PathTree tree = dump.pathTree(g, gcRoots);
    int[] parent = tree.parent;
    if (parent[targetNode] == PathTree.UNREACHED) {
      return List.of();
    }
    IntArrayList nodes = new IntArrayList();
    for (int v = targetNode; v != PathTree.START; v = parent[v]) {
      nodes.add(v);
    }
    List<PathStep> path = new ArrayList<>(nodes.size());
    HeapObject prev = null;
    for (int i = nodes.size() - 1; i >= 0; i--) {
      int v = nodes.getInt(i);
      HeapObject obj = i == 0 ? target : g.nodes[v];
      String fieldName =
          prev == null ? tree.startNames.get(v) : firstFieldReferencing(prev, g.nodes[v].getId());
      path.add(new PathStep(obj, fieldName));
      prev = obj;
    }
    return path;
  }

  /**
   * The name {@link #visitOutboundEdges} gives the first reference from {@code from} to {@code id}.
   */
  private static String firstFieldReferencing(HeapObject from, long id) {
    if (from instanceof HeapObjectImpl impl && impl.isArray()) {
      // the first element holding id; the same "[i]" name visitOutboundEdges gives, without
      // resolving every element of a possibly large array
      int index = impl.indexOfArrayElement(id);
      return index >= 0 ? "[" + index + "]" : null;
    }
    String[] name = {null};
    visitOutboundEdges(
        from,
        (fieldName, ref) -> {
          if (name[0] == null && ref.getId() == id) {
            name[0] = fieldName;
          }
        });
    return name[0];
  }

  /**
   * Visits all outbound object references of {@code obj}, calling {@code visitor} with the field
   * name and the referenced object. For array elements the name has the form {@code [i]}.
   *
   * <p>The {@code referent} and {@code discovered} fields of {@code java.lang.ref.Reference}
   * subclasses are skipped because they do not represent strong reachability edges.
   */
  private static void visitOutboundEdges(HeapObject obj, EdgeVisitor visitor) {
    if (obj.isArray()) {
      Object[] elements = obj.getArrayElements();
      if (elements == null) return;
      for (int i = 0; i < elements.length; i++) {
        if (elements[i] instanceof HeapObject ref) {
          visitor.visit("[" + i + "]", ref);
        }
      }
    } else {
      HeapClass cls = obj.getHeapClass();
      boolean isReference = cls instanceof HeapClassImpl impl && impl.isReferenceSubclass();
      for (Map.Entry<String, Object> entry : obj.getFieldValues().entrySet()) {
        if (entry.getValue() instanceof HeapObject ref) {
          if (isReference
              && ("referent".equals(entry.getKey()) || "discovered".equals(entry.getKey())))
            continue;
          visitor.visit(entry.getKey(), ref);
        }
      }
    }
  }

  @FunctionalInterface
  private interface EdgeVisitor {
    void visit(String fieldName, HeapObject ref);
  }

  /**
   * Finds all paths from GC roots to the target object up to a maximum depth.
   *
   * <p>This method explores multiple paths and can be expensive for highly connected objects. Use
   * {@link #findShortestPath} for most use cases.
   *
   * @param dump the heap dump
   * @param target the object to find paths to
   * @param gcRoots list of GC roots
   * @param maxDepth maximum path length to explore (prevents infinite loops)
   * @return list of paths, each path is a list of objects from root to target
   */
  public static List<List<HeapObject>> findAllPaths(
      HeapDump dump, HeapObject target, List<GcRootImpl> gcRoots, int maxDepth) {
    if (target == null || gcRoots == null || gcRoots.isEmpty() || maxDepth <= 0) {
      return List.of();
    }

    List<List<HeapObject>> allPaths = new ArrayList<>();
    long targetId = target.getId();

    // Check if target is itself a GC root
    for (GcRoot root : gcRoots) {
      if (root.getObjectId() == targetId) {
        allPaths.add(List.of(target));
        return allPaths;
      }
    }

    // DFS with path tracking from each GC root
    for (GcRoot root : gcRoots) {
      dump.getObjectById(root.getObjectId())
          .ifPresent(
              rootObj -> {
                List<HeapObject> currentPath = new ArrayList<>();
                LongOpenHashSet visited = new LongOpenHashSet();
                findPathsDFS(rootObj, target, currentPath, visited, allPaths, maxDepth);
              });
    }

    return allPaths;
  }

  private static void findPathsDFS(
      HeapObject current,
      HeapObject target,
      List<HeapObject> currentPath,
      LongOpenHashSet visited,
      List<List<HeapObject>> allPaths,
      int maxDepth) {

    if (currentPath.size() >= maxDepth) {
      return; // Max depth reached
    }

    currentPath.add(current);
    visited.add(current.getId());

    if (current.getId() == target.getId()) {
      // Found a path - make a copy and add to results
      allPaths.add(new ArrayList<>(currentPath));
    } else {
      // Continue exploring (strong references only)
      current
          .getStrongOutboundReferences()
          .forEach(
              ref -> {
                if (!visited.contains(ref.getId())) {
                  findPathsDFS(ref, target, currentPath, visited, allPaths, maxDepth);
                }
              });
    }

    // Backtrack
    currentPath.remove(currentPath.size() - 1);
    visited.remove(current.getId());
  }
}
