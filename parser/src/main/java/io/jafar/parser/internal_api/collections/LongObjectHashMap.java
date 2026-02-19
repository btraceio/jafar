package io.jafar.parser.internal_api.collections;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.LongFunction;

/**
 * Open-addressing hash map from {@code long} keys to object values.
 *
 * <p>Uses Fibonacci hashing with linear probing and a sentinel key ({@link Long#MIN_VALUE}).
 * {@code null} values are not supported — any slot whose value is {@code null} is treated as empty.
 *
 * <p>{@link Long#MIN_VALUE} is reserved as the empty-slot sentinel and must not be used as a key.
 */
public final class LongObjectHashMap<V> {

    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final long PHI_MIX = 0x9E3779B97F4A7C15L;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int threshold;

    public LongObjectHashMap() {
        this(16);
    }

    public LongObjectHashMap(int expectedSize) {
        int capacity = tableSizeFor(expectedSize);
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        threshold = (capacity * 3) >>> 2;
        Arrays.fill(keys, EMPTY_KEY);
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return (V) values[idx];
            if (k == EMPTY_KEY) return null;
            idx = (idx + 1) & mask;
        }
    }

    public void put(long key, V value) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        if (value == null) throw new NullPointerException("null values not supported");
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

    @SuppressWarnings("unchecked")
    public V putIfAbsent(long key, V value) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        if (value == null) throw new NullPointerException("null values not supported");
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return (V) values[idx];
            if (k == EMPTY_KEY) {
                keys[idx] = key;
                values[idx] = value;
                if (++size >= threshold) {
                    rehash();
                }
                return null;
            }
            idx = (idx + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    public V getOrDefault(long key, V defaultValue) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return (V) values[idx];
            if (k == EMPTY_KEY) return defaultValue;
            idx = (idx + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    public V computeIfAbsent(long key, LongFunction<V> mappingFunction) {
        assert key != EMPTY_KEY : "Long.MIN_VALUE is reserved as sentinel key";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == key) return (V) values[idx];
            if (k == EMPTY_KEY) {
                V val = mappingFunction.apply(key);
                if (val == null) return null;
                keys[idx] = key;
                values[idx] = val;
                if (++size >= threshold) {
                    rehash();
                }
                return val;
            }
            idx = (idx + 1) & mask;
        }
    }

    /** Returns a live {@link Collection} view of the values in this map. */
    public Collection<V> values() {
        return new AbstractCollection<V>() {
            @Override
            public Iterator<V> iterator() {
                return new Iterator<V>() {
                    private int idx = 0;
                    private int remaining = size;

                    @Override
                    public boolean hasNext() {
                        return remaining > 0;
                    }

                    @SuppressWarnings("unchecked")
                    @Override
                    public V next() {
                        if (remaining <= 0) throw new NoSuchElementException();
                        while (values[idx] == null) {
                            idx++;
                        }
                        remaining--;
                        return (V) values[idx++];
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    public void clear() {
        if (size > 0) {
            Arrays.fill(keys, EMPTY_KEY);
            Arrays.fill(values, null);
            size = 0;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private int index(long key) {
        return (int) ((key * PHI_MIX) >>> (64 - Integer.numberOfTrailingZeros(keys.length))) & mask;
    }

    @SuppressWarnings("unchecked")
    private void rehash() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        int newCapacity = oldKeys.length << 1;
        keys = new long[newCapacity];
        values = new Object[newCapacity];
        mask = newCapacity - 1;
        threshold = (newCapacity * 3) >>> 2;
        Arrays.fill(keys, EMPTY_KEY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY_KEY) {
                put(oldKeys[i], (V) oldValues[i]);
            }
        }
    }

    private static int tableSizeFor(int expected) {
        int n = Math.max(4, expected + (expected >>> 1));
        n = Integer.highestOneBit(n - 1) << 1;
        return n;
    }
}
