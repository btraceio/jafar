package io.jafar.parser.api;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ParserContext {
    private final ConcurrentMap<String, SoftReference<?>> bag = new ConcurrentHashMap<>();

    public final <T> T remove(Class<T> clz) {
        return clz.cast(bag.remove(clz.getName()).get());
    }

    public final <T> T remove(String key, Class<T> clz) {
        return clz.cast(bag.remove(key).get());
    }

    public final <T> void put(Class<T> clz, T value) {
        bag.put(clz.getName(), new SoftReference<>(value));
    }

    public final <T> T get(Class<T> clz) {
        return clz.cast(bag.get(clz.getName()).get());
    }

    public final <T> void put(String key, Class<T> clz, T value) {
        bag.put(key, new SoftReference<>(value));
    }

    public final <T> T get(String key, Class<T> clz) {
        return clz.cast(bag.get(key).get());
    }

    public void clear() {
        bag.clear();
    }

    public abstract void onMetadataReady();
    public abstract void onConstantPoolsReady();
}
