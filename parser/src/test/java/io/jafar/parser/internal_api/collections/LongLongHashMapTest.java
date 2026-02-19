package io.jafar.parser.internal_api.collections;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class LongLongHashMapTest {

    @Test
    public void testPutAndGet() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(1L, 100L);
        map.put(2L, 200L);
        assertEquals(100L, map.get(1L));
        assertEquals(200L, map.get(2L));
    }

    @Test
    public void testGetMissingKeyReturnsZero() {
        LongLongHashMap map = new LongLongHashMap(4);
        assertEquals(0L, map.get(999L));
    }

    @Test
    public void testOverwrite() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(1L, 100L);
        map.put(1L, 200L);
        assertEquals(200L, map.get(1L));
        assertEquals(1, map.size());
    }

    @Test
    public void testContainsKey() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(5L, 50L);
        assertTrue(map.containsKey(5L));
        assertFalse(map.containsKey(6L));
    }

    @Test
    public void testSizeAndIsEmpty() {
        LongLongHashMap map = new LongLongHashMap(4);
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        map.put(1L, 10L);
        assertFalse(map.isEmpty());
        assertEquals(1, map.size());
        map.put(2L, 20L);
        assertEquals(2, map.size());
    }

    @Test
    public void testKeyIterator() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(10L, 1L);
        map.put(20L, 2L);
        map.put(30L, 3L);

        Set<Long> keys = new HashSet<>();
        LongLongHashMap.LongIterator it = map.keyIterator();
        while (it.hasNext()) {
            keys.add(it.nextLong());
        }
        assertEquals(Set.of(10L, 20L, 30L), keys);
    }

    @Test
    public void testKeyIteratorEmpty() {
        LongLongHashMap map = new LongLongHashMap(4);
        LongLongHashMap.LongIterator it = map.keyIterator();
        assertFalse(it.hasNext());
    }

    @Test
    public void testRehash() {
        // Force multiple rehashes by inserting many entries with small initial capacity
        LongLongHashMap map = new LongLongHashMap(2);
        for (long i = 1; i <= 200; i++) {
            map.put(i, i * 10);
        }
        assertEquals(200, map.size());
        for (long i = 1; i <= 200; i++) {
            assertEquals(i * 10, map.get(i));
        }
    }

    @Test
    public void testNegativeKeys() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(-1L, 100L);
        map.put(-100L, 200L);
        assertEquals(100L, map.get(-1L));
        assertEquals(200L, map.get(-100L));
    }

    @Test
    public void testZeroKey() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(0L, 42L);
        assertEquals(42L, map.get(0L));
        assertTrue(map.containsKey(0L));
    }

    @Test
    public void testZeroValue() {
        // 0L is a valid value (distinct from "missing")
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(1L, 0L);
        assertEquals(0L, map.get(1L));
        assertTrue(map.containsKey(1L));
    }

    @Test
    public void testLargeKeyValues() {
        LongLongHashMap map = new LongLongHashMap(4);
        map.put(Long.MAX_VALUE, Long.MAX_VALUE);
        map.put(Long.MAX_VALUE - 1, Long.MIN_VALUE);
        assertEquals(Long.MAX_VALUE, map.get(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, map.get(Long.MAX_VALUE - 1));
    }
}
