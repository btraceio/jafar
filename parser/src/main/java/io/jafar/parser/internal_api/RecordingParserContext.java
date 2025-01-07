package io.jafar.parser.internal_api;

import io.jafar.parser.MutableConstantPools;
import io.jafar.parser.MutableMetadataLookup;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.utils.CachedStringParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RecordingParserContext {
    private final MutableMetadataLookup metadataLookup;
    private final MutableConstantPools constantPools;

    private final int chunkIndex;
    private volatile TypeFilter typeFilter;

    private final Map<String, Class<?>> classTargetTypeMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WeakReference<?>> bag = new ConcurrentHashMap<>();

    private Long2ObjectMap<Class<?>> classTypeMap = null;

    public static class DeserializerKey {
        private final long id;
        private final String name;
        private final String superType;
        private final List<String> fieldNames;

        public DeserializerKey(MetadataClass clz) {
            this.id = clz.getId();
            this.name = clz.getName();
            this.superType = clz.getSuperType();
            this.fieldNames = new ArrayList<>(clz.getFields().size());
            for (MetadataField field : clz.getFields()) {
                this.fieldNames.add(field.getType().getName() + ":" + field.getName());
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DeserializerKey that = (DeserializerKey) o;
            return id == that.id && Objects.equals(name, that.name) && Objects.equals(superType, that.superType) && Objects.equals(fieldNames, that.fieldNames);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, superType, fieldNames);
        }

        @Override
        public String toString() {
            return "DeserializerKey{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", superType='" + superType + '\'' +
                    ", fieldNames=" + fieldNames +
                    '}';
        }
    }

    private final ConcurrentMap<DeserializerKey, Deserializer<?>> globalDeserializerCache;

    public final CachedStringParser.ByteArrayParser utf8Parser = CachedStringParser.byteParser();
    public final CachedStringParser.CharArrayParser charParser = CachedStringParser.charParser();
    public final byte[] byteBuffer = new byte[4096];
    public final char[] charBuffer = new char[4096];

    public RecordingParserContext() {
        this(new ConcurrentHashMap<>());
    }

    public RecordingParserContext(ConcurrentMap<DeserializerKey, Deserializer<?>> deserializerCache) {
        this.metadataLookup = new MutableMetadataLookup();
        this.constantPools = new MutableConstantPools(metadataLookup);
        this.globalDeserializerCache = deserializerCache != null ? deserializerCache : new ConcurrentHashMap<>();

        this.typeFilter = null;
        this.chunkIndex = 0;
    }

    public RecordingParserContext(TypeFilter typeFilter, int chunkIndex, MutableMetadataLookup metadataLookup, MutableConstantPools constantPools, ConcurrentMap<DeserializerKey, Deserializer<?>> deserializerCache) {
        this.metadataLookup = metadataLookup;
        this.constantPools = constantPools;
        this.globalDeserializerCache = deserializerCache;

        this.typeFilter = typeFilter;
        this.chunkIndex = chunkIndex;
    }

    public void clear() {
        classTargetTypeMap.clear();
        bag.clear();
    }

    public MetadataLookup getMetadataLookup() {
        return metadataLookup;
    }

    public ConstantPools getConstantPools() {
        return constantPools;
    }

    public TypeFilter getTypeFilter() {
        return typeFilter;
    }

    public void setTypeFilter(TypeFilter typeFilter) {
        this.typeFilter = typeFilter;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public <T> void put(String key, Class<T> clz, T value) {
        bag.put(key, new WeakReference<>(value));
    }

    public <T> T get(String key, Class<T> clz) {
        return clz.cast(bag.get(key).get());
    }

    public void addTargetTypeMap(Map<String, Class<?>> map) {
        this.classTargetTypeMap.putAll(map);
    }

    public Class<?> getClassTargetType(String name) {
        return classTargetTypeMap.get(name);
    }

    public void bindDeserializers() {
        metadataLookup.bindDeserializers();
    }

    public void setClassTypeMap(Long2ObjectMap<Class<?>> map) {
        classTypeMap = map;
    }

    public Long2ObjectMap<Class<?>> getClassTypeMap() {
        return classTypeMap;
    }

    public ConcurrentMap<DeserializerKey, Deserializer<?>> getDeserializerCache() {
        return globalDeserializerCache;
    }
}
