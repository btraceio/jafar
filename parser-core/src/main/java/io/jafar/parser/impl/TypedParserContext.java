package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parser context implementation for typed JFR parsing.
 *
 * <p>This class extends ParserContext to provide additional functionality specific to typed
 * parsing, including deserializer caching, type filtering, and class type mapping.
 */
public final class TypedParserContext extends ParserContext {
  /** Filter for determining which event types to process. */
  private volatile TypeFilter typeFilter;

  /** Map of event type names to their target handler classes. */
  private final Map<String, Class<?>> classTargetTypeMap = new ConcurrentHashMap<>();

  /** Map of class IDs to their handler classes. */
  private LongObjectHashMap<Class<?>> classTypeMap = null;

  /**
   * Key for identifying and caching deserializers.
   *
   * <p>This class provides a composite key based on metadata class properties to ensure proper
   * deserializer caching and reuse.
   */
  public static class DeserializerKey {
    private final long id;
    private final String name;
    private final String superType;
    private final List<String> fieldNames;
    private final Class<?> targetClass;

    /**
     * Constructs a new DeserializerKey from a metadata class and target handler class.
     *
     * @param clz the metadata class to create a key for
     * @param targetClass the target handler interface class (may be null)
     */
    public DeserializerKey(MetadataClass clz, Class<?> targetClass) {
      this.id = clz.getId();
      this.name = clz.getName();
      this.superType = clz.getSuperType();
      this.fieldNames = new ArrayList<>(clz.getFields().size());
      for (MetadataField field : clz.getFields()) {
        this.fieldNames.add(field.getType().getName() + ":" + field.getName());
      }
      this.targetClass = targetClass;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DeserializerKey that = (DeserializerKey) o;
      return id == that.id
          && Objects.equals(name, that.name)
          && Objects.equals(superType, that.superType)
          && Objects.equals(fieldNames, that.fieldNames)
          && Objects.equals(targetClass, that.targetClass);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
      return Objects.hash(id, name, superType, fieldNames, targetClass);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
      return "DeserializerKey{"
          + "id="
          + id
          + ", name='"
          + name
          + '\''
          + ", superType='"
          + superType
          + '\''
          + ", fieldNames="
          + fieldNames
          + ", targetClass="
          + targetClass
          + '}';
    }
  }

  /** Global cache for deserializers. */
  private volatile DeserializerCache globalDeserializerCache;

  /** Factory reference for resolving deserializer cache. */
  private final TypedParserContextFactory factory;

  /** Constructs a new TypedParserContext with default settings. */
  public TypedParserContext() {
    this(new DeserializerCache.Impl());
  }

  /**
   * Constructs a new TypedParserContext with the specified deserializer cache.
   *
   * @param deserializerCache the deserializer cache to use (may be null for delayed resolution)
   */
  TypedParserContext(DeserializerCache deserializerCache) {
    super(0);

    this.globalDeserializerCache =
        deserializerCache != null ? deserializerCache : new DeserializerCache.Impl();
    this.factory = null;

    this.typeFilter = null;

    this.remove(TypeFilter.class);
    this.put(DeserializerCache.class, globalDeserializerCache);
  }

  /**
   * Constructs a new TypedParserContext with the specified parameters.
   *
   * @param typeFilter the type filter to use
   * @param chunkIndex the chunk index
   * @param metadataLookup the metadata lookup instance
   * @param constantPools the constant pools instance
   * @param deserializerCache the deserializer cache to use (may be null for delayed resolution)
   * @param factory the factory for resolving the deserializer cache (may be null)
   */
  TypedParserContext(
      TypeFilter typeFilter,
      int chunkIndex,
      MutableMetadataLookup metadataLookup,
      MutableConstantPools constantPools,
      DeserializerCache deserializerCache,
      TypedParserContextFactory factory) {
    super(chunkIndex, metadataLookup, constantPools);
    this.globalDeserializerCache = deserializerCache;
    this.factory = factory;

    this.typeFilter = typeFilter;

    // ConcurrentHashMap does not allow null keys, so only put if typeFilter is not null
    if (typeFilter != null) {
      this.put(TypeFilter.class, typeFilter);
    }
    if (deserializerCache != null) {
      this.put(DeserializerCache.class, globalDeserializerCache);
    }
  }

  /**
   * Gets the current type filter.
   *
   * @return the type filter, or {@code null} if none is set
   */
  TypeFilter getTypeFilter() {
    return typeFilter;
  }

  /**
   * Sets the type filter for this context.
   *
   * @param typeFilter the type filter to set
   */
  public void setTypeFilter(TypeFilter typeFilter) {
    this.typeFilter = typeFilter;
    this.put(TypeFilter.class, typeFilter);
  }

  /**
   * Adds target type mappings for event type names to handler classes.
   *
   * @param map the map of event type names to handler classes
   */
  public void addTargetTypeMap(Map<String, Class<?>> map) {
    this.classTargetTypeMap.putAll(map);
  }

  /**
   * Gets the target handler class for the specified event type name.
   *
   * @param name the event type name
   * @return the target handler class, or null if not found
   */
  public Class<?> getClassTargetType(String name) {
    return classTargetTypeMap.get(name);
  }

  /** Binds deserializers for all metadata classes in this context. */
  void bindDeserializers() {
    metadataLookup.bindDeserializers();
  }

  /**
   * Sets the class type mapping for this context.
   *
   * @param map the map of class IDs to handler classes
   */
  public void setClassTypeMap(LongObjectHashMap<Class<?>> map) {
    classTypeMap = map;
  }

  /**
   * Gets the class type mapping for this context.
   *
   * @return the map of class IDs to handler classes
   */
  public LongObjectHashMap<Class<?>> getClassTypeMap() {
    return classTypeMap;
  }

  /**
   * Gets the deserializer cache for this context.
   *
   * @return the deserializer cache
   */
  public DeserializerCache getDeserializerCache() {
    return globalDeserializerCache;
  }

  /**
   * Sets the deserializer cache for this context.
   *
   * @param cache the deserializer cache to use
   */
  void setDeserializerCache(DeserializerCache cache) {
    this.globalDeserializerCache = cache;
    this.put(DeserializerCache.class, cache);
  }

  /**
   * Gets the factory associated with this context.
   *
   * @return the factory, or null if not set
   */
  public TypedParserContextFactory getFactory() {
    return factory;
  }

  /**
   * Gets the set of target event type names for fingerprint computation.
   *
   * @return the set of event type names
   */
  public Set<String> getTargetEventTypes() {
    return classTargetTypeMap.keySet();
  }

  @Override
  public void onMetadataReady() {
    super.onMetadataReady();
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
