package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClusterDetectorTest {

  @Test
  void testEmptyHeap() {
    HeapDump dump = new StubHeapDump(List.of(), List.of());
    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 15);

    assertTrue(result.rows().isEmpty());
    assertTrue(result.membership().isEmpty());
  }

  @Test
  void testSingleObjectHeap() {
    HeapObject obj = mockObject(1L, "java/lang/String", 100, 100);
    when(obj.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    GcRoot root = mockGcRoot(1L, GcRoot.Type.THREAD_OBJ);
    HeapDump dump = new StubHeapDump(List.of(obj), List.of(root));

    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 15);

    // Single object can't form a cluster (needs > 1 member)
    assertTrue(result.rows().isEmpty());
  }

  @Test
  void testTwoConnectedObjects() {
    HeapObject obj1 = mockObject(1L, "java/util/HashMap", 64, 1024);
    HeapObject obj2 = mockObject(2L, "java/util/HashMap$Node", 32, 512);

    when(obj1.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj2));
    when(obj2.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    GcRoot root = mockGcRoot(1L, GcRoot.Type.THREAD_OBJ);
    HeapDump dump = new StubHeapDump(List.of(obj1, obj2), List.of(root));

    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 15);

    assertFalse(result.rows().isEmpty());
    Map<String, Object> cluster = result.rows().get(0);
    assertEquals(2, cluster.get(HdumpPath.ClusterFields.OBJECT_COUNT));
    assertNotNull(cluster.get(HdumpPath.ClusterFields.RETAINED_SIZE));
    assertNotNull(cluster.get(HdumpPath.ClusterFields.SCORE));
    assertNotNull(cluster.get(HdumpPath.ClusterFields.DOMINANT_CLASS));
  }

  @Test
  void testMinRetainedSizeFilter() {
    HeapObject obj1 = mockObject(1L, "java/lang/String", 24, 24);
    HeapObject obj2 = mockObject(2L, "java/lang/String", 24, 24);
    when(obj1.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj2));
    when(obj2.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    HeapDump dump = new StubHeapDump(List.of(obj1, obj2), List.of());

    // Retained size = 48 bytes, filter at 1MB
    ClusterDetector.Result result = ClusterDetector.detect(dump, 1024 * 1024, 15);
    assertTrue(result.rows().isEmpty());

    // Filter at 0 bytes — need fresh dump since getObjects() streams are consumed
    HeapObject obj3 = mockObject(1L, "java/lang/String", 24, 24);
    HeapObject obj4 = mockObject(2L, "java/lang/String", 24, 24);
    when(obj3.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj4));
    when(obj4.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    HeapDump dump2 = new StubHeapDump(List.of(obj3, obj4), List.of());
    ClusterDetector.Result result2 = ClusterDetector.detect(dump2, 0, 15);
    assertFalse(result2.rows().isEmpty());
  }

  @Test
  void testClusterMembership() {
    HeapObject obj1 = mockObject(1L, "java/util/ArrayList", 40, 2000);
    HeapObject obj2 = mockObject(2L, "java/lang/Object[]", 1000, 1000);
    when(obj1.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj2));
    when(obj2.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    HeapDump dump = new StubHeapDump(List.of(obj1, obj2), List.of());

    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 15);
    assertFalse(result.membership().isEmpty());

    int clusterId = (int) result.rows().get(0).get(HdumpPath.ClusterFields.ID);
    long[] memberIds = result.membership().get(clusterId);
    assertNotNull(memberIds);
    assertEquals(2, memberIds.length);
  }

  @Test
  void testClusterFields() {
    HeapObject obj1 = mockObject(1L, "java/util/HashMap", 64, 5000);
    HeapObject obj2 = mockObject(2L, "java/util/HashMap", 64, 3000);
    when(obj1.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj2));
    when(obj2.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    HeapDump dump = new StubHeapDump(List.of(obj1, obj2), List.of());

    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 15);
    assertFalse(result.rows().isEmpty());

    Map<String, Object> row = result.rows().get(0);
    assertTrue(row.containsKey(HdumpPath.ClusterFields.ID));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.OBJECT_COUNT));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.RETAINED_SIZE));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.ROOT_PATH_COUNT));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.SCORE));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.DOMINANT_CLASS));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.ANCHOR_TYPE));
    assertTrue(row.containsKey(HdumpPath.ClusterFields.ANCHOR_OBJECT));

    assertEquals("java.util.HashMap", row.get(HdumpPath.ClusterFields.DOMINANT_CLASS));
  }

  @Test
  void testZeroIterationsSkipsLabelPropagation() {
    HeapObject obj1 = mockObject(1L, "java/lang/String", 24, 100);
    HeapObject obj2 = mockObject(2L, "java/lang/String", 24, 100);
    when(obj1.getOutboundReferences()).thenAnswer(inv -> Stream.of(obj2));
    when(obj2.getOutboundReferences()).thenAnswer(inv -> Stream.empty());

    HeapDump dump = new StubHeapDump(List.of(obj1, obj2), List.of());
    ClusterDetector.Result result = ClusterDetector.detect(dump, 0, 0);
    assertNotNull(result);
  }

  private static HeapObject mockObject(long id, String className, int shallow, long retained) {
    HeapObject obj = mock(HeapObject.class);
    HeapClass cls = mock(HeapClass.class);
    when(cls.getName()).thenReturn(className);
    when(obj.getId()).thenReturn(id);
    when(obj.getHeapClass()).thenReturn(cls);
    when(obj.getShallowSize()).thenReturn(shallow);
    when(obj.getRetainedSize()).thenReturn(retained);
    when(obj.getRetainedSizeIfAvailable()).thenReturn(retained);
    when(obj.getDescription()).thenReturn(className + "@" + Long.toHexString(id));
    return obj;
  }

  private static GcRoot mockGcRoot(long objectId, GcRoot.Type type) {
    GcRoot root = mock(GcRoot.class);
    when(root.getObjectId()).thenReturn(objectId);
    when(root.getType()).thenReturn(type);
    return root;
  }

  /** Stub HeapDump to avoid Mockito issues with Closeable on Java 25. */
  private static class StubHeapDump implements HeapDump {
    private final List<HeapObject> objects;
    private final List<GcRoot> gcRoots;

    StubHeapDump(List<HeapObject> objects, List<GcRoot> gcRoots) {
      this.objects = objects;
      this.gcRoots = gcRoots;
    }

    @Override
    public java.nio.file.Path getPath() {
      return null;
    }

    @Override
    public long getTimestamp() {
      return 0;
    }

    @Override
    public String getFormatVersion() {
      return "1.0.2";
    }

    @Override
    public int getIdSize() {
      return 8;
    }

    @Override
    public Collection<HeapClass> getClasses() {
      return List.of();
    }

    @Override
    public Optional<HeapClass> getClassById(long id) {
      return Optional.empty();
    }

    @Override
    public Optional<HeapClass> getClassByName(String name) {
      return Optional.empty();
    }

    @Override
    public Stream<HeapClass> findClasses(Predicate<HeapClass> predicate) {
      return Stream.empty();
    }

    @Override
    public Stream<HeapObject> getObjects() {
      return objects.stream();
    }

    @Override
    public Optional<HeapObject> getObjectById(long id) {
      return objects.stream().filter(o -> o.getId() == id).findFirst();
    }

    @Override
    public Stream<HeapObject> getObjectsOfClass(HeapClass cls) {
      return Stream.empty();
    }

    @Override
    public Stream<HeapObject> findObjects(Predicate<HeapObject> predicate) {
      return objects.stream().filter(predicate);
    }

    @Override
    public Collection<GcRoot> getGcRoots() {
      return gcRoots;
    }

    @Override
    public Stream<GcRoot> getGcRoots(GcRoot.Type type) {
      return gcRoots.stream().filter(r -> r.getType() == type);
    }

    @Override
    public int getClassCount() {
      return 0;
    }

    @Override
    public int getObjectCount() {
      return objects.size();
    }

    @Override
    public int getGcRootCount() {
      return gcRoots.size();
    }

    @Override
    public long getTotalHeapSize() {
      return objects.stream().mapToLong(HeapObject::getShallowSize).sum();
    }

    @Override
    public void computeDominators() {}

    @Override
    public boolean hasDominators() {
      return true;
    }

    @Override
    public List<PathStep> findPathToGcRoot(HeapObject obj) {
      return List.of();
    }

    @Override
    public void close() {}
  }
}
