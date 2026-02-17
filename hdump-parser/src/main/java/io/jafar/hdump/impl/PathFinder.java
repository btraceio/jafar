package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
   * <p>If the target is itself a GC root, returns a single-element list containing just the
   * target.
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

    // BFS from GC roots, tracking the incoming edge (parent + field name) per object
    Long2ObjectOpenHashMap<Edge> parents = new Long2ObjectOpenHashMap<>();
    LongOpenHashSet visited = new LongOpenHashSet();
    Deque<HeapObject> queue = new ArrayDeque<>();

    // Initialize with all GC root objects (no incoming edge)
    for (GcRoot root : gcRoots) {
      dump.getObjectById(root.getObjectId()).ifPresent(rootObj -> {
        if (!visited.contains(rootObj.getId())) {
          visited.add(rootObj.getId());
          queue.add(rootObj);
          parents.put(rootObj.getId(), new Edge(null, null));
        }
      });
    }

    // BFS until we find target or exhaust reachable objects
    boolean found = false;
    while (!queue.isEmpty()) {
      HeapObject current = queue.poll();

      if (current.getId() == targetId) {
        found = true;
        break;
      }

      visitOutboundEdges(current, (fieldName, ref) -> {
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

  /**
   * Visits all outbound object references of {@code obj}, calling {@code visitor} with the field
   * name and the referenced object. For array elements the name has the form {@code [i]}.
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
      for (Map.Entry<String, Object> entry : obj.getFieldValues().entrySet()) {
        if (entry.getValue() instanceof HeapObject ref) {
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
      dump.getObjectById(root.getObjectId()).ifPresent(rootObj -> {
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
      // Continue exploring
      current
          .getOutboundReferences()
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
