package io.jafar.parser.internal_api.collections;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class LongObjectHashMapTest {

  @Test
  public void testPutAndGet() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    map.put(2L, "two");
    assertEquals("one", map.get(1L));
    assertEquals("two", map.get(2L));
  }

  @Test
  public void testGetMissingKeyReturnsNull() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    assertNull(map.get(999L));
  }

  @Test
  public void testPutNullValueThrows() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    assertThrows(NullPointerException.class, () -> map.put(1L, null));
  }

  @Test
  public void testOverwrite() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "first");
    map.put(1L, "second");
    assertEquals("second", map.get(1L));
    assertEquals(1, map.size());
  }

  @Test
  public void testContainsKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(5L, "five");
    assertTrue(map.containsKey(5L));
    assertFalse(map.containsKey(6L));
  }

  @Test
  public void testSizeAndIsEmpty() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>();
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());
    map.put(1L, "a");
    assertFalse(map.isEmpty());
    assertEquals(1, map.size());
  }

  @Test
  public void testPutIfAbsentNewKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    String result = map.putIfAbsent(1L, "one");
    assertNull(result);
    assertEquals("one", map.get(1L));
  }

  @Test
  public void testPutIfAbsentExistingKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    String result = map.putIfAbsent(1L, "other");
    assertEquals("one", result);
    assertEquals("one", map.get(1L));
  }

  @Test
  public void testPutIfAbsentNullThrows() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    assertThrows(NullPointerException.class, () -> map.putIfAbsent(1L, null));
  }

  @Test
  public void testGetOrDefault() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    assertEquals("one", map.getOrDefault(1L, "default"));
    assertEquals("default", map.getOrDefault(2L, "default"));
  }

  @Test
  public void testComputeIfAbsentNewKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    String result = map.computeIfAbsent(1L, k -> "computed-" + k);
    assertEquals("computed-1", result);
    assertEquals("computed-1", map.get(1L));
    assertEquals(1, map.size());
  }

  @Test
  public void testComputeIfAbsentExistingKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "existing");
    String result = map.computeIfAbsent(1L, k -> "computed");
    assertEquals("existing", result);
  }

  @Test
  public void testComputeIfAbsentNullFunction() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    String result = map.computeIfAbsent(1L, k -> null);
    assertNull(result);
    assertEquals(0, map.size());
  }

  @Test
  public void testValues() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    map.put(2L, "two");
    map.put(3L, "three");

    Collection<String> values = map.values();
    assertEquals(3, values.size());

    Set<String> valSet = new HashSet<>(values);
    assertEquals(Set.of("one", "two", "three"), valSet);
  }

  @Test
  public void testValuesEmpty() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    Collection<String> values = map.values();
    assertEquals(0, values.size());
    assertFalse(values.iterator().hasNext());
  }

  @Test
  public void testValuesIteratorThrowsOnExhaustion() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    Iterator<String> it = map.values().iterator();
    it.next();
    assertThrows(NoSuchElementException.class, it::next);
  }

  @Test
  public void testValuesStream() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "a");
    map.put(2L, "b");

    List<String> streamed = new ArrayList<>(map.values().stream().toList());
    streamed.sort(null);
    assertEquals(List.of("a", "b"), streamed);
  }

  @Test
  public void testClear() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(1L, "one");
    map.put(2L, "two");
    map.clear();
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
    assertNull(map.get(1L));
    assertNull(map.get(2L));
  }

  @Test
  public void testClearEmpty() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.clear(); // should not throw
    assertEquals(0, map.size());
  }

  @Test
  public void testRehash() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(2);
    for (long i = 1; i <= 200; i++) {
      map.put(i, "v" + i);
    }
    assertEquals(200, map.size());
    for (long i = 1; i <= 200; i++) {
      assertEquals("v" + i, map.get(i));
    }
  }

  @Test
  public void testDefaultConstructor() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>();
    map.put(1L, "one");
    assertEquals("one", map.get(1L));
  }

  @Test
  public void testNegativeKeys() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(-1L, "neg1");
    map.put(-100L, "neg100");
    assertEquals("neg1", map.get(-1L));
    assertEquals("neg100", map.get(-100L));
  }

  @Test
  public void testZeroKey() {
    LongObjectHashMap<String> map = new LongObjectHashMap<>(4);
    map.put(0L, "zero");
    assertEquals("zero", map.get(0L));
    assertTrue(map.containsKey(0L));
  }
}
