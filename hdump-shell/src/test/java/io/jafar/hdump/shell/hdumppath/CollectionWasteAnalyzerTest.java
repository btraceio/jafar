package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionWasteAnalyzerTest {

  private static final int REF_SIZE = 8;

  @Test
  void testHashMapWithWaste() {
    HeapObject table = mockArray(1024);
    HeapObject obj = mockObject("java/util/HashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("size")).thenReturn(3);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(1024, row.get("capacity"));
    assertEquals(3, row.get("size"));
    assertEquals(3.0 / 1024, (double) row.get("loadFactor"), 0.0001);
    assertEquals((1024L - 3) * REF_SIZE, row.get("wastedBytes"));
    assertEquals("overCapacity", row.get("wasteType"));
  }

  @Test
  void testLinkedHashMapDelegatesToHashMap() {
    HeapObject table = mockArray(64);
    HeapObject obj = mockObject("java/util/LinkedHashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("size")).thenReturn(60);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(64, row.get("capacity"));
    assertEquals(60, row.get("size"));
    assertEquals("overCapacity", row.get("wasteType"));
  }

  @Test
  void testHashSetDelegatesToMap() {
    HeapObject innerTable = mockArray(16);
    HeapObject innerMap = mockObject("java/util/HashMap");
    when(innerMap.getFieldValue("table")).thenReturn(innerTable);
    when(innerMap.getFieldValue("size")).thenReturn(5);

    HeapObject obj = mockObject("java/util/HashSet");
    when(obj.getFieldValue("map")).thenReturn(innerMap);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(16, row.get("capacity"));
    assertEquals(5, row.get("size"));
    assertEquals((16L - 5) * REF_SIZE, row.get("wastedBytes"));
  }

  @Test
  void testLinkedHashSetDelegatesToMap() {
    HeapObject innerTable = mockArray(32);
    HeapObject innerMap = mockObject("java/util/LinkedHashMap");
    when(innerMap.getFieldValue("table")).thenReturn(innerTable);
    when(innerMap.getFieldValue("size")).thenReturn(10);

    HeapObject obj = mockObject("java/util/LinkedHashSet");
    when(obj.getFieldValue("map")).thenReturn(innerMap);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(32, row.get("capacity"));
    assertEquals(10, row.get("size"));
  }

  @Test
  void testHashSetNullMap() {
    HeapObject obj = mockObject("java/util/HashSet");
    when(obj.getFieldValue("map")).thenReturn(null);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertNull(row.get("capacity"));
    assertNull(row.get("wastedBytes"));
  }

  @Test
  void testArrayListWithWaste() {
    HeapObject elementData = mockArray(100);
    HeapObject obj = mockObject("java/util/ArrayList");
    when(obj.getFieldValue("elementData")).thenReturn(elementData);
    when(obj.getFieldValue("size")).thenReturn(2);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(100, row.get("capacity"));
    assertEquals(2, row.get("size"));
    assertEquals((100L - 2) * REF_SIZE, row.get("wastedBytes"));
    assertEquals("overCapacity", row.get("wasteType"));
  }

  @Test
  void testConcurrentHashMap() {
    HeapObject table = mockArray(256);
    HeapObject obj = mockObject("java/util/concurrent/ConcurrentHashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("baseCount")).thenReturn(100L);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(256, row.get("capacity"));
    assertEquals(100, row.get("size"));
    assertEquals((256L - 100) * REF_SIZE, row.get("wastedBytes"));
  }

  @Test
  void testArrayDeque() {
    HeapObject elements = mockArray(16);
    HeapObject obj = mockObject("java/util/ArrayDeque");
    when(obj.getFieldValue("elements")).thenReturn(elements);
    when(obj.getFieldValue("head")).thenReturn(3);
    when(obj.getFieldValue("tail")).thenReturn(7);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(16, row.get("capacity"));
    assertEquals(4, row.get("size")); // (7 - 3 + 16) % 16 = 4
    assertEquals((16L - 4) * REF_SIZE, row.get("wastedBytes"));
  }

  @Test
  void testArrayDequeWraparound() {
    HeapObject elements = mockArray(16);
    HeapObject obj = mockObject("java/util/ArrayDeque");
    when(obj.getFieldValue("elements")).thenReturn(elements);
    when(obj.getFieldValue("head")).thenReturn(12);
    when(obj.getFieldValue("tail")).thenReturn(4);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(8, row.get("size")); // (4 - 12 + 16) % 16 = 8
  }

  @Test
  void testEmptyCollection() {
    HeapObject table = mockArray(16);
    HeapObject obj = mockObject("java/util/HashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("size")).thenReturn(0);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(16, row.get("capacity"));
    assertEquals(0, row.get("size"));
    assertEquals(0.0, row.get("loadFactor"));
    assertEquals("emptyDefault", row.get("wasteType"));
  }

  @Test
  void testNullBackingArray() {
    HeapObject obj = mockObject("java/util/HashMap");
    when(obj.getFieldValue("table")).thenReturn(null);
    when(obj.getFieldValue("size")).thenReturn(0);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(0, row.get("capacity"));
    assertEquals(0, row.get("size"));
    assertEquals(0L, row.get("wastedBytes"));
    assertEquals("emptyDefault", row.get("wasteType"));
  }

  @Test
  void testFullCollection() {
    HeapObject table = mockArray(16);
    HeapObject obj = mockObject("java/util/HashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("size")).thenReturn(16);

    Map<String, Object> row = new LinkedHashMap<>();
    assertTrue(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));

    assertEquals(16, row.get("capacity"));
    assertEquals(16, row.get("size"));
    assertEquals(1.0, row.get("loadFactor"));
    assertEquals(0L, row.get("wastedBytes"));
    assertEquals("normal", row.get("wasteType"));
  }

  @Test
  void testUnknownCollectionReturnsFalse() {
    HeapObject obj = mockObject("java/lang/String");

    Map<String, Object> row = new LinkedHashMap<>();
    assertFalse(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));
    assertFalse(row.containsKey("capacity"));
  }

  @Test
  void testNullClassReturnsFalse() {
    HeapObject obj = mock(HeapObject.class);
    when(obj.getHeapClass()).thenReturn(null);

    Map<String, Object> row = new LinkedHashMap<>();
    assertFalse(CollectionWasteAnalyzer.analyze(obj, row, REF_SIZE));
  }

  @Test
  void testAddNullWasteColumns() {
    Map<String, Object> row = new LinkedHashMap<>();
    CollectionWasteAnalyzer.addNullWasteColumns(row);

    assertTrue(row.containsKey("capacity"));
    assertTrue(row.containsKey("size"));
    assertTrue(row.containsKey("loadFactor"));
    assertTrue(row.containsKey("wastedBytes"));
    assertTrue(row.containsKey("wasteType"));
    assertNull(row.get("capacity"));
    assertNull(row.get("wastedBytes"));
  }

  @Test
  void testRefSize4Bytes() {
    HeapObject table = mockArray(1024);
    HeapObject obj = mockObject("java/util/HashMap");
    when(obj.getFieldValue("table")).thenReturn(table);
    when(obj.getFieldValue("size")).thenReturn(3);

    Map<String, Object> row = new LinkedHashMap<>();
    CollectionWasteAnalyzer.analyze(obj, row, 4);

    assertEquals((1024L - 3) * 4, row.get("wastedBytes"));
  }

  private static HeapObject mockObject(String className) {
    HeapObject obj = mock(HeapObject.class);
    HeapClass cls = mock(HeapClass.class);
    when(cls.getName()).thenReturn(className);
    when(obj.getHeapClass()).thenReturn(cls);
    return obj;
  }

  private static HeapObject mockArray(int length) {
    HeapObject arr = mock(HeapObject.class);
    when(arr.isArray()).thenReturn(true);
    when(arr.getArrayLength()).thenReturn(length);
    return arr;
  }
}
