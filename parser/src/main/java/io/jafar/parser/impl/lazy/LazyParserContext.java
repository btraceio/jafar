package io.jafar.parser.impl.lazy;

import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class LazyParserContext extends ParserContext {
    private volatile TypeFilter typeFilter;

    private final Map<String, Class<?>> classTargetTypeMap = new ConcurrentHashMap<>();

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

    private final DeserializerCache globalDeserializerCache;

    public LazyParserContext() {
        this(new DeserializerCache.Impl());
    }

    LazyParserContext(DeserializerCache deserializerCache) {
        super(0);

        this.globalDeserializerCache = deserializerCache != null ? deserializerCache : new DeserializerCache.Impl();

        this.typeFilter = null;

        this.remove(TypeFilter.class);
        this.put(DeserializerCache.class, globalDeserializerCache);
    }

    LazyParserContext(TypeFilter typeFilter, int chunkIndex, MutableMetadataLookup metadataLookup, MutableConstantPools constantPools, DeserializerCache deserializerCache) {
        super(chunkIndex, metadataLookup, constantPools);
        this.globalDeserializerCache = deserializerCache;

        this.typeFilter = typeFilter;

        this.put(TypeFilter.class, typeFilter);
        this.put(DeserializerCache.class, globalDeserializerCache);
    }

    TypeFilter getTypeFilter() {
        return typeFilter;
    }

    public void setTypeFilter(TypeFilter typeFilter) {
        this.typeFilter = typeFilter;
        this.put(TypeFilter.class, typeFilter);
    }

    public void addTargetTypeMap(Map<String, Class<?>> map) {
        this.classTargetTypeMap.putAll(map);
    }

    public Class<?> getClassTargetType(String name) {
        return classTargetTypeMap.get(name);
    }

    void bindDeserializers() {
        metadataLookup.bindDeserializers();
    }

    public void setClassTypeMap(Long2ObjectMap<Class<?>> map) {
        classTypeMap = map;
    }

    public Long2ObjectMap<Class<?>> getClassTypeMap() {
        return classTypeMap;
    }

    public DeserializerCache getDeserializerCache() {
        return globalDeserializerCache;
    }

    @Override
    public void onMetadataReady() {
        bindDeserializers();
    }

    @Override
    public void onConstantPoolsReady() {
        constantPools.setReady();
    }

    @Override
    public MutableMetadataLookup getMetadataLookup() {
        return (MutableMetadataLookup) super.getMetadataLookup();
    }
}
