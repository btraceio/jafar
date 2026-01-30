package io.jafar.hdump.api;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;
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

  /** Returns a class by its fully qualified name. */
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

  /** Returns objects of the given class (exact match, not including subclasses). */
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
   * enables retained size queries.
   */
  void computeDominators();

  /** Returns whether dominators have been computed. */
  boolean hasDominators();

  /**
   * Finds the shortest path from any GC root to the given object.
   *
   * @param obj the target object
   * @return list of objects from GC root to target, or empty if not reachable
   */
  java.util.List<HeapObject> findPathToGcRoot(HeapObject obj);
}
