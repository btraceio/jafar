package io.jafar.parser.internal_api.collections;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class IntObjectArrayMapTest {

    @Test
    public void testPutAndGet() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        map.put(0, "zero");
        map.put(1, "one");
        map.put(5, "five");
        assertEquals("zero", map.get(0));
        assertEquals("one", map.get(1));
        assertEquals("five", map.get(5));
    }

    @Test
    public void testGetMissingKeyReturnsNull() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        assertNull(map.get(0));
        assertNull(map.get(10));
    }

    @Test
    public void testGetNegativeKeyReturnsNull() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        assertNull(map.get(-1));
    }

    @Test
    public void testGetBeyondCapacityReturnsNull() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>(4);
        assertNull(map.get(100));
    }

    @Test
    public void testOverwrite() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        map.put(1, "first");
        map.put(1, "second");
        assertEquals("second", map.get(1));
    }

    @Test
    public void testAutoGrow() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>(2);
        map.put(100, "hundred");
        assertEquals("hundred", map.get(100));
    }

    @Test
    public void testComputeIfAbsentNewKey() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        String result = map.computeIfAbsent(3, k -> "computed-" + k);
        assertEquals("computed-3", result);
        assertEquals("computed-3", map.get(3));
    }

    @Test
    public void testComputeIfAbsentExistingKey() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        map.put(3, "existing");
        String result = map.computeIfAbsent(3, k -> "computed");
        assertEquals("existing", result);
    }

    @Test
    public void testClear() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        map.put(0, "zero");
        map.put(1, "one");
        map.clear();
        assertNull(map.get(0));
        assertNull(map.get(1));
    }

    @Test
    public void testCopyConstructor() {
        IntObjectArrayMap<String> original = new IntObjectArrayMap<>();
        original.put(0, "zero");
        original.put(5, "five");

        IntObjectArrayMap<String> copy = new IntObjectArrayMap<>(original);
        assertEquals("zero", copy.get(0));
        assertEquals("five", copy.get(5));

        // Verify independence
        copy.put(0, "modified");
        assertEquals("zero", original.get(0));
    }

    @Test
    public void testNegativeKeyInPutThrows() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        assertThrows(IllegalArgumentException.class, () -> map.put(-1, "bad"));
    }

    @Test
    public void testNegativeKeyInComputeIfAbsentThrows() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        assertThrows(
                IllegalArgumentException.class, () -> map.computeIfAbsent(-1, k -> "bad"));
    }

    @Test
    public void testSparseKeys() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>(4);
        map.put(0, "a");
        map.put(50, "b");
        map.put(1000, "c");
        assertEquals("a", map.get(0));
        assertEquals("b", map.get(50));
        assertEquals("c", map.get(1000));
        assertNull(map.get(25));
    }

    @Test
    public void testPutNull() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        map.put(0, "zero");
        map.put(0, null);
        assertNull(map.get(0));
    }

    @Test
    public void testDefaultConstructor() {
        IntObjectArrayMap<String> map = new IntObjectArrayMap<>();
        // Should work without explicit capacity
        map.put(0, "zero");
        assertEquals("zero", map.get(0));
    }
}
