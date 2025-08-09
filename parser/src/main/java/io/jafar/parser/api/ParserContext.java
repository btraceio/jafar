package io.jafar.parser.api;

import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.utils.CachedStringParser;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ParserContext {
    private final ConcurrentMap<String, SoftReference<?>> bag = new ConcurrentHashMap<>();
    protected final MutableMetadataLookup metadataLookup;
    protected final MutableConstantPools constantPools;

    private final int chunkIndex;

    public final byte[] byteBuffer = new byte[4096];
    public final char[] charBuffer = new char[4096];

    public final CachedStringParser.ByteArrayParser utf8Parser = CachedStringParser.byteParser();
    public final CachedStringParser.CharArrayParser charParser = CachedStringParser.charParser();

    public ParserContext(int chunkIndex) {
        this.chunkIndex = chunkIndex;
        this.metadataLookup = new MutableMetadataLookup();
        this.constantPools = new MutableConstantPools();
    }

    protected ParserContext(int chunkIndex, MutableMetadataLookup metadataLookup, MutableConstantPools constantPools) {
        this.chunkIndex = chunkIndex;
        this.metadataLookup = metadataLookup;
        this.constantPools = constantPools;
    }

    public final <T> T remove(Class<T> clz) {
        SoftReference<?> removed = bag.remove(clz.getName());
        return removed != null ? clz.cast(removed.get()) : null;
    }

    public final <T> T remove(String key, Class<T> clz) {
        return clz.cast(bag.remove(key).get());
    }

    public final <T> void put(Class<T> clz, T value) {
        bag.put(clz.getName(), new SoftReference<>(value));
    }

    public final <T> T get(Class<T> clz) {
        SoftReference<?> ref = bag.get(clz.getName());
        return ref != null ? clz.cast(ref.get()) : null;
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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public abstract void onMetadataReady();
    public abstract void onConstantPoolsReady();

    public MetadataLookup getMetadataLookup() {
        return metadataLookup;
    }

    public ConstantPools getConstantPools() {
        return constantPools;
    }
}
