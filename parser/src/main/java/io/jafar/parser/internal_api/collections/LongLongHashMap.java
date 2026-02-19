package io.jafar.parser.internal_api.collections;

/**
 * Open-addressing hash map from {@code long} keys to {@code long} values.
 *
 * <p>Uses Fibonacci hashing with linear probing and a sentinel key ({@link Long#MIN_VALUE}) to
 * distinguish empty slots without a separate states array. Returns {@code 0L} for missing keys.
 *
 * <p>{@link Long#MIN_VALUE} is reserved as the empty-slot sentinel and must not be used as a key.
 */
public final class LongLongHashMap {

    /** Primitive long iterator over map keys. */
    public interface LongIterator {
        boolean hasNext();

        long nextLong();
    }

    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final long PHI_MIX = 0x9E3779B97F4A7C15L;

    private long[] keys;
    private long[] values;
    private int mask;
    private int size;
    private int threshold;

    public LongLongHashMap(int expectedSize) {
        int capacity = tableSizeFor(expectedSize);
        keys = new long[capacity];
        values = new long[capacity];
        mask = capacity - 1;
        threshold = (capacity * 3) >>> 2; // 0.75 load factor
        fillEmpty(keys);
    }

    public long get(long key) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return values[idx];
            if (k == EMPTY_KEY) return 0L;
            idx = (idx + 1) & mask;
        }
    }

    public void put(long key, long value) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                keys[idx] = key;
                values[idx] = value;
                if (++size >= threshold) {
                    rehash();
                }
                return;
            }
            if (k == key) {
                values[idx] = value;
                return;
            }
            idx = (idx + 1) & mask;
        }
    }

    public boolean containsKey(long key) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return true;
            if (k == EMPTY_KEY) return false;
            idx = (idx + 1) & mask;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Returns a primitive iterator over the keys in this map. */
    public LongIterator keyIterator() {
        return new LongIterator() {
            private int idx = 0;
            private int remaining = size;

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            @Override
            public long nextLong() {
                while (keys[idx] == EMPTY_KEY) {
                    idx++;
                }
                remaining--;
                return keys[idx++];
            }
        };
    }

    private int index(long key) {
        return (int) ((key * PHI_MIX) >>> (64 - Integer.numberOfTrailingZeros(keys.length))) & mask;
    }

    private void rehash() {
        long[] oldKeys = keys;
        long[] oldValues = values;
        int newCapacity = oldKeys.length << 1;
        keys = new long[newCapacity];
        values = new long[newCapacity];
        mask = newCapacity - 1;
        threshold = (newCapacity * 3) >>> 2;
        fillEmpty(keys);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY_KEY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static void fillEmpty(long[] a) {
        java.util.Arrays.fill(a, EMPTY_KEY);
    }

    private static int tableSizeFor(int expected) {
        int n = Math.max(4, expected + (expected >>> 1)); // account for load factor
        n = Integer.highestOneBit(n - 1) << 1;
        return n;
    }
}
