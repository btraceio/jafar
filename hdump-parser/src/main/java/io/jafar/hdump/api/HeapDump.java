package io.jafar.hdump.api;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Represents a parsed heap dump. Provides access to classes, objects, GC roots, and heap
 * statistics.
 *
 * <p>Instances are created via {@link HeapDumpParser#parse(Path)}.
 */
public interface HeapDump extends Closeable {

  /** Returns the path to the heap dump file. */
  Path getPath();

  /** Returns the timestamp when the dump was created (milliseconds since epoch). */
  long getTimestamp();

  /** Returns the HPROF format version (e.g., "1.0.2"). */
  String getFormatVersion();

  /** Returns the size of object identifiers (4 or 8 bytes). */
  int getIdSize();

  // === Class access ===

  /** Returns all classes in the heap dump. */
  Collection<HeapClass> getClasses();

  /** Returns a class by its ID. */
  Optional<HeapClass> getClassById(long id);

  /**
   * Returns a class by its name.
   *
   * <p><strong>Important:</strong> Class names must be in <em>internal format</em> (e.g., {@code
   * "java/lang/String"}), not qualified format ({@code "java.lang.String"}). This matches the
   * native format stored in HPROF files.
   *
   * <p>Use {@link io.jafar.hdump.util.ClassNameUtil#toInternal(String)} to convert from qualified
   * format if needed.
   *
   * @param name class name in internal format (slash-delimited, e.g., {@code "java/lang/String"})
   * @return the class, or empty if not found
   * @see io.jafar.hdump.util.ClassNameUtil#toInternal(String)
   */
  Optional<HeapClass> getClassByName(String name);

  /** Returns classes matching the given predicate. */
  Stream<HeapClass> findClasses(Predicate<HeapClass> predicate);

  // === Object access ===

  /** Returns all objects in the heap dump. May be expensive for large dumps. */
  Stream<HeapObject> getObjects();

  /** Returns an object by its ID. */
  Optional<HeapObject> getObjectById(long id);

  /** Returns objects of the given class (exact match, not including subclasses). */
  Stream<HeapObject> getObjectsOfClass(HeapClass cls);

  /**
   * Returns objects of the given class (exact match, not including subclasses).
   *
   * <p><strong>Important:</strong> Class name must be in <em>internal format</em> (e.g., {@code
   * "java/lang/String"}), not qualified format ({@code "java.lang.String"}).
   *
   * @param className class name in internal format (slash-delimited, e.g., {@code
   *     "java/lang/String"})
   * @return stream of objects of the specified class
   * @see #getClassByName(String)
   * @see io.jafar.hdump.util.ClassNameUtil#toInternal(String)
   */
  default Stream<HeapObject> getObjectsOfClass(String className) {
    return getClassByName(className).map(this::getObjectsOfClass).orElse(Stream.empty());
  }

  /** Returns objects matching the given predicate. */
  Stream<HeapObject> findObjects(Predicate<HeapObject> predicate);

  // === GC roots ===

  /** Returns all GC roots. */
  Collection<GcRoot> getGcRoots();

  /** Returns GC roots of the given type. */
  Stream<GcRoot> getGcRoots(GcRoot.Type type);

  // === Statistics ===

  /** Returns the total number of classes. */
  int getClassCount();

  /** Returns the total number of objects. */
  int getObjectCount();

  /** Returns the total number of GC roots. */
  int getGcRootCount();

  /** Returns the total heap size (sum of all object shallow sizes). */
  long getTotalHeapSize();

  // === Analysis ===

  /**
   * Computes dominator tree and retained sizes for all objects. This is an expensive operation but
   * enables retained size queries via {@link HeapObject#getRetainedSize()}.
   *
   * <p>This method is idempotent - calling it multiple times has no additional effect. The
   * computation is not thread-safe; callers must ensure no concurrent access during computation.
   *
   * @see #hasDominators()
   */
  void computeDominators();

  /** Returns whether dominators have been computed. */
  boolean hasDominators();

  /**
   * Computes dominator tree and retained sizes for all objects, reporting progress through the
   * given callback. Equivalent to {@link #computeDominators()} otherwise.
   *
   * @param progressCallback optional callback for progress updates (may be null)
   */
  default void computeDominators(HeapDumpParser.ProgressCallback progressCallback) {
    computeDominators();
  }

  /**
   * Ensures retained sizes are available, computing them if necessary. Implementations may use a
   * cheaper approximation than a full dominator tree. Idempotent.
   */
  default void ensureRetainedSizesComputed() {
    if (!hasDominators()) {
      computeDominators();
    }
  }

  /**
   * Computes the full (exact) dominator tree, enabling {@link #getDominatedObjects(HeapObject)}
   * queries. More expensive than {@link #computeDominators()} but yields exact retained sizes.
   *
   * <p>Optional capability — implementations without full dominator tree support throw {@link
   * UnsupportedOperationException}; check {@link #hasFullDominatorTree()} after computing.
   *
   * @param progressCallback optional callback for progress updates (may be null)
   */
  default void computeFullDominatorTree(HeapDumpParser.ProgressCallback progressCallback) {
    throw new UnsupportedOperationException("Full dominator tree computation is not supported");
  }

  /** Returns whether the full dominator tree has been computed. */
  default boolean hasFullDominatorTree() {
    return false;
  }

  /**
   * Returns the objects immediately dominated by the given object.
   *
   * <p>Requires {@link #computeFullDominatorTree(HeapDumpParser.ProgressCallback)}; returns an
   * empty list when the full dominator tree is unavailable.
   *
   * @param dominator the dominating object
   * @return list of dominated objects (empty if full tree not computed)
   */
  default List<HeapObject> getDominatedObjects(HeapObject dominator) {
    return Collections.emptyList();
  }

  /** Returns whether an index accelerating {@link #getObjectsOfClassFast(HeapClass)} exists. */
  default boolean hasClassInstancesIndex() {
    return false;
  }

  /**
   * Returns objects of the given class, using the class-instances index when available (10-60x
   * faster on large indexed dumps). Falls back to a full scan otherwise; results match {@link
   * #getObjectsOfClass(HeapClass)}.
   *
   * @param cls the heap class to get instances for
   * @return stream of heap objects belonging to this class
   */
  default Stream<HeapObject> getObjectsOfClassFast(HeapClass cls) {
    return getObjectsOfClass(cls);
  }

  /**
   * Finds the shortest path from any GC root to the given object.
   *
   * <p>Uses BFS traversal from GC roots to find the shortest reference path. Each {@link PathStep}
   * carries the object at that position and the field name of the reference from the previous step
   * ({@code null} for the GC root).
   *
   * @param obj the target object
   * @return list of path steps from GC root to target, or empty list if no path found
   */
  List<PathStep> findPathToGcRoot(HeapObject obj);
}
