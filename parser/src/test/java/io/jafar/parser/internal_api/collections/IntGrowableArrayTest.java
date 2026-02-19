package io.jafar.parser.internal_api.collections;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class IntGrowableArrayTest {

    @Test
    public void testAddAndSize() {
        IntGrowableArray arr = new IntGrowableArray(4);
        assertEquals(0, arr.size());
        arr.add(10);
        arr.add(20);
        arr.add(30);
        assertEquals(3, arr.size());
    }

    @Test
    public void testToIntArray() {
        IntGrowableArray arr = new IntGrowableArray(4);
        arr.add(1);
        arr.add(2);
        arr.add(3);
        assertArrayEquals(new int[] {1, 2, 3}, arr.toIntArray());
    }

    @Test
    public void testToIntArrayIsDefensiveCopy() {
        IntGrowableArray arr = new IntGrowableArray(4);
        arr.add(1);
        int[] copy = arr.toIntArray();
        copy[0] = 99;
        assertArrayEquals(new int[] {1}, arr.toIntArray());
    }

    @Test
    public void testSet() {
        IntGrowableArray arr = new IntGrowableArray(4);
        arr.add(0);
        arr.add(0);
        arr.add(0);
        arr.set(1, 42);
        assertArrayEquals(new int[] {0, 42, 0}, arr.toIntArray());
    }

    @Test
    public void testGrowBeyondInitialCapacity() {
        IntGrowableArray arr = new IntGrowableArray(2);
        for (int i = 0; i < 100; i++) {
            arr.add(i);
        }
        assertEquals(100, arr.size());
        int[] result = arr.toIntArray();
        for (int i = 0; i < 100; i++) {
            assertEquals(i, result[i]);
        }
    }

    @Test
    public void testZeroInitialCapacity() {
        IntGrowableArray arr = new IntGrowableArray(0);
        arr.add(5);
        assertEquals(1, arr.size());
        assertArrayEquals(new int[] {5}, arr.toIntArray());
    }

    @Test
    public void testEmptyToIntArray() {
        IntGrowableArray arr = new IntGrowableArray(4);
        assertArrayEquals(new int[0], arr.toIntArray());
    }
}
