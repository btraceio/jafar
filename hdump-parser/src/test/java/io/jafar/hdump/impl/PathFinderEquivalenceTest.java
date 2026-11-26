package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import io.jafar.hdump.test.GraphHeapDumpWriter;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PathFinder#findShortestPath} has a fast path for {@link HeapDumpImpl} (BFS over reference
 * ids) and a generic one for any other {@link HeapDump} (BFS over field-value maps). They must
 * return the same path — same objects, same field names, same choice among equally short paths. The
 * generic path is forced by handing {@code PathFinder} a {@link Proxy} view of the same dump.
 */
class PathFinderEquivalenceTest {

  @ParameterizedTest(name = "random graph seed={0}")
  @ValueSource(longs = {1, 2, 3, 4, 5})
  void randomGraphs(long seed, @TempDir Path dir) throws Exception {
    randomGraph(seed, dir, ParserOptions.IN_MEMORY);
  }

  /** close() drops the cached path graph (it pins every object of the dump). */
  @Test
  void closeDropsThePathGraph(@TempDir Path dir) throws Exception {
    GraphHeapDumpWriter w = DominatorOracleTest.randomGraph(new Random(77), 200, 0.5, false, 2);
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    HeapDumpImpl dump = (HeapDumpImpl) HeapDumpParser.parse(file, ParserOptions.IN_MEMORY);
    dump.findPathToGcRoot(dump.getObjects().findFirst().orElseThrow());
    assertNotNull(dump.cachedPathGraph(), "built by the first query");
    dump.close();
    assertNull(dump.cachedPathGraph(), "dropped by close()");
  }

  /** INDEXED mode has no in-memory graph: the id-based BFS answers there. */
  @ParameterizedTest(name = "random graph, indexed mode, seed={0}")
  @ValueSource(longs = {1, 2, 3})
  void randomGraphsIndexed(long seed, @TempDir Path dir) throws Exception {
    randomGraph(seed, dir, ParserOptions.INDEXED);
  }

  private static void randomGraph(long seed, Path dir, ParserOptions options) throws Exception {
    Random rnd = new Random(5000 + seed);
    int n = 300 + rnd.nextInt(600);
    GraphHeapDumpWriter w = DominatorOracleTest.randomGraph(rnd, n, 0.45, false, 3);
    for (int i = 0; i < 5; i++) {
      w.addStaticRef(rnd.nextInt(n));
    }
    Path file = dir.resolve("graph.hprof");
    w.write(file, 1 << 24);
    try (HeapDump dump = HeapDumpParser.parse(file, options)) {
      List<HeapObject> all = dump.getObjects().toList();
      int compared = compareAll(dump, all, options == ParserOptions.IN_MEMORY);
      assertTrue(compared > 0, "no object had a path; the graph shape is wrong");
    }
  }

  // ---- a real heap dump, including classes that shadow a superclass field name ----

  /** Held by a static field only; reaches the shadowing objects. */
  static final Holder HELD = new Holder();

  static class Base {
    Object next; // shadowed by Sub.next (object, same type)
    Object size; // shadowed by Sub.size (int): the map entry holds Base.size's value
    int count; // shadowed by Sub.count (object): the map entry holds Base.count's value
  }

  static final class Sub extends Base {
    Object next;
    int size;
    Object count;
  }

  static final class Holder {
    final List<Object> items = new ArrayList<>();

    final List<ShadowRef> shadowRefs = new ArrayList<>();

    Holder() {
      for (int i = 0; i < 5; i++) {
        shadowRefs.add(new ShadowRef(new int[i + 1], new long[i + 1], new short[i + 1]));
      }
      Object shared = new Object();
      for (int i = 0; i < 20; i++) {
        Sub s = new Sub();
        s.next = new int[i + 1];
        ((Base) s).next = shared;
        s.count = new long[1];
        ((Base) s).size = new byte[i];
        items.add(s);
      }
    }
  }

  /** A Reference subclass that shadows {@code referent}: both are excluded by name. */
  static final class ShadowRef extends WeakReference<Object> {
    final Object referent;
    final Object other;

    ShadowRef(Object weak, Object strong, Object other) {
      super(weak);
      this.referent = strong;
      this.other = other;
    }
  }

  @ParameterizedTest(name = "real dump with shadowed field names, indexed={0}")
  @ValueSource(booleans = {false, true})
  void realDumpWithShadowedFieldNames(boolean indexed, @TempDir Path dir) throws Exception {
    Path file = dir.resolve("self.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(file.toString(), true);
    ParserOptions options = indexed ? ParserOptions.INDEXED : ParserOptions.IN_MEMORY;
    try (HeapDump dump = HeapDumpParser.parse(file, options)) {
      List<HeapObject> refs =
          dump.getObjectsOfClass(ShadowRef.class.getName().replace('.', '/')).toList();
      assertEquals(5, refs.size());
      String sub = Sub.class.getName().replace('.', '/');
      List<HeapObject> subs = dump.getObjectsOfClass(sub).toList();
      assertEquals(20, subs.size());
      assertTrue(
          ((HeapClassImpl) subs.get(0).getHeapClass()).hasDuplicateInstanceFieldNames(),
          "fixture must exercise the shadowed-field fallback");
      List<HeapObject> targets = new ArrayList<>(subs);
      // Every object a Sub references, read from the instance data: the shadowed fields' targets
      // (Sub.next's int[], Sub.count's long[]) are missing from getFieldValues() by construction.
      List<HeapObject> owners = new ArrayList<>(subs);
      owners.addAll(refs);
      targets.addAll(refs);
      for (HeapObject s : owners) {
        for (long id : ((HeapObjectImpl) s).getOutboundReferenceIds()) {
          dump.getObjectById(id).ifPresent(targets::add);
        }
      }
      // plus a deterministic sample of the whole heap
      List<HeapObject> all =
          dump.getObjects().sorted(Comparator.comparingLong(HeapObject::getId)).toList();
      for (int i = 0; i < all.size(); i += Math.max(1, all.size() / (indexed ? 15 : 40))) {
        targets.add(all.get(i));
      }
      int compared = compareAll(dump, targets, !indexed);
      assertTrue(compared >= 40, "too few paths compared: " + compared);
    }
  }

  /**
   * Compares both paths for every target; returns how many targets had a (non-empty) path. Also
   * proves the fast path really answered: every non-root target is counted by {@code
   * PathFinder.FAST_PATH_QUERIES}, and in IN_MEMORY mode the graph is built by the first query.
   */
  private static int compareAll(HeapDump dump, List<HeapObject> targets, boolean inMemory) {
    HeapDumpImpl impl = (HeapDumpImpl) dump;
    assertNull(impl.cachedPathGraph(), "no path graph before the first query");
    HeapDump generic = genericView(dump);
    List<GcRootImpl> roots = new ArrayList<>();
    Set<Long> rootIds = new HashSet<>();
    for (GcRoot r : dump.getGcRoots()) {
      roots.add((GcRootImpl) r);
      rootIds.add(r.getObjectId());
    }
    assertFalse(roots.isEmpty());
    long before = PathFinder.FAST_PATH_QUERIES.get();
    int expectedFast = 0;
    int nonEmpty = 0;
    for (HeapObject t : targets) {
      List<String> fast = render(PathFinder.findShortestPath(dump, t, roots));
      List<String> slow = render(PathFinder.findShortestPath(generic, t, roots));
      assertEquals(slow, fast, "path to " + t);
      if (!fast.isEmpty()) nonEmpty++;
      if (!rootIds.contains(t.getId())) expectedFast++;
    }
    assertEquals(
        expectedFast, PathFinder.FAST_PATH_QUERIES.get() - before, "queries on a fast path");
    assertEquals(inMemory && expectedFast > 0, impl.cachedPathGraph() != null, "path graph cached");
    return nonEmpty;
  }

  private static List<String> render(List<PathStep> path) {
    List<String> out = new ArrayList<>();
    for (PathStep s : path) out.add(Long.toHexString(s.object().getId()) + " via " + s.fieldName());
    return out;
  }

  private static HeapDump genericView(HeapDump dump) {
    return (HeapDump)
        Proxy.newProxyInstance(
            HeapDump.class.getClassLoader(),
            new Class<?>[] {HeapDump.class},
            (proxy, method, args) -> {
              try {
                return method.invoke(dump, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
