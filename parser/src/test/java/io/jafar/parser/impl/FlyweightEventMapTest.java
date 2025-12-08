package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for FlyweightEventMap. */
class FlyweightEventMapTest {

  @Test
  void testGet() {
    String[] keys = {"field1", "field2", "field3"};
    Object[] values = {1, "two", 3.0};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    assertEquals(1, map.get("field1"));
    assertEquals("two", map.get("field2"));
    assertEquals(3.0, map.get("field3"));
    assertNull(map.get("nonexistent"));
    assertNull(map.get(123)); // non-String key
  }

  @Test
  void testContainsKey() {
    String[] keys = {"field1", "field2"};
    Object[] values = {1, 2};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    assertTrue(map.containsKey("field1"));
    assertTrue(map.containsKey("field2"));
    assertFalse(map.containsKey("field3"));
    assertFalse(map.containsKey(123)); // non-String key
  }

  @Test
  void testSize() {
    String[] keys = {"a", "b", "c"};
    Object[] values = {1, 2, 3};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    assertEquals(3, map.size());
    assertFalse(map.isEmpty());

    FlyweightEventMap emptyMap = new FlyweightEventMap(new String[0], new Object[0]);
    assertEquals(0, emptyMap.size());
    assertTrue(emptyMap.isEmpty());
  }

  @Test
  void testKeySet() {
    String[] keys = {"a", "b", "c"};
    Object[] values = {1, 2, 3};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    Set<String> keySet = map.keySet();
    assertEquals(3, keySet.size());
    assertTrue(keySet.contains("a"));
    assertTrue(keySet.contains("b"));
    assertTrue(keySet.contains("c"));
    assertFalse(keySet.contains("d"));
  }

  @Test
  void testEntrySet() {
    String[] keys = {"a", "b"};
    Object[] values = {1, 2};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    Set<Map.Entry<String, Object>> entrySet = map.entrySet();
    assertEquals(2, entrySet.size());

    Map<String, Object> collected = new HashMap<>();
    for (Map.Entry<String, Object> entry : entrySet) {
      collected.put(entry.getKey(), entry.getValue());
    }

    assertEquals(1, collected.get("a"));
    assertEquals(2, collected.get("b"));
  }

  @Test
  void testEquals() {
    String[] keys1 = {"a", "b", "c"};
    Object[] values1 = {1, 2, 3};
    FlyweightEventMap map1 = new FlyweightEventMap(keys1, values1);

    String[] keys2 = {"a", "b", "c"};
    Object[] values2 = {1, 2, 3};
    FlyweightEventMap map2 = new FlyweightEventMap(keys2, values2);

    assertEquals(map1, map2);
    assertEquals(map1, map1); // reflexive

    // Test with HashMap
    Map<String, Object> hashMap = new HashMap<>();
    hashMap.put("a", 1);
    hashMap.put("b", 2);
    hashMap.put("c", 3);
    assertEquals(map1, hashMap);
    assertEquals(hashMap, map1);

    // Different values
    String[] keys3 = {"a", "b", "c"};
    Object[] values3 = {1, 2, 999};
    FlyweightEventMap map3 = new FlyweightEventMap(keys3, values3);
    assertNotEquals(map1, map3);

    // Different keys
    String[] keys4 = {"a", "b", "d"};
    Object[] values4 = {1, 2, 3};
    FlyweightEventMap map4 = new FlyweightEventMap(keys4, values4);
    assertNotEquals(map1, map4);

    // Null value handling
    String[] keys5 = {"a"};
    Object[] values5 = {null};
    FlyweightEventMap map5 = new FlyweightEventMap(keys5, values5);
    Map<String, Object> hashMapWithNull = new HashMap<>();
    hashMapWithNull.put("a", null);
    assertEquals(map5, hashMapWithNull);
  }

  @Test
  void testHashCode() {
    String[] keys1 = {"a", "b", "c"};
    Object[] values1 = {1, 2, 3};
    FlyweightEventMap map1 = new FlyweightEventMap(keys1, values1);

    String[] keys2 = {"a", "b", "c"};
    Object[] values2 = {1, 2, 3};
    FlyweightEventMap map2 = new FlyweightEventMap(keys2, values2);

    // Equal objects must have equal hash codes
    assertEquals(map1.hashCode(), map2.hashCode());

    // Should match HashMap hash code
    Map<String, Object> hashMap = new HashMap<>();
    hashMap.put("a", 1);
    hashMap.put("b", 2);
    hashMap.put("c", 3);
    assertEquals(map1.hashCode(), hashMap.hashCode());
  }

  @Test
  void testToString() {
    String[] keys = {"a", "b"};
    Object[] values = {1, 2};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    String str = map.toString();
    assertTrue(str.contains("a=1"));
    assertTrue(str.contains("b=2"));
    assertTrue(str.startsWith("{"));
    assertTrue(str.endsWith("}"));

    FlyweightEventMap emptyMap = new FlyweightEventMap(new String[0], new Object[0]);
    assertEquals("{}", emptyMap.toString());
  }

  @Test
  void testImmutability() {
    String[] keys = {"a", "b"};
    Object[] values = {1, 2};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    assertThrows(UnsupportedOperationException.class, () -> map.put("c", 3));
    assertThrows(UnsupportedOperationException.class, () -> map.remove("a"));
    assertThrows(UnsupportedOperationException.class, () -> map.clear());
    assertThrows(UnsupportedOperationException.class, () -> map.putAll(Map.of("d", 4)));
  }

  @Test
  void testBinarySearch() {
    // Keys must be sorted for binary search to work
    String[] keys = {"a", "b", "c", "d", "e"};
    Object[] values = {1, 2, 3, 4, 5};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    // Test all keys are found
    assertEquals(1, map.get("a"));
    assertEquals(2, map.get("b"));
    assertEquals(3, map.get("c"));
    assertEquals(4, map.get("d"));
    assertEquals(5, map.get("e"));

    // Test keys not in array
    assertNull(map.get("aa")); // between a and b
    assertNull(map.get("f")); // after e
    assertNull(map.get("0")); // before a
  }

  @Test
  void testMismatchedArrayLengths() {
    String[] keys = {"a", "b"};
    Object[] values = {1}; // different length

    assertThrows(IllegalArgumentException.class, () -> new FlyweightEventMap(keys, values));
  }

  @Test
  void testNullValues() {
    String[] keys = {"a", "b", "c"};
    Object[] values = {null, 2, null};
    FlyweightEventMap map = new FlyweightEventMap(keys, values);

    assertNull(map.get("a"));
    assertEquals(2, map.get("b"));
    assertNull(map.get("c"));
    assertTrue(map.containsKey("a")); // key exists even though value is null
    assertTrue(map.containsKey("c"));
  }
}
