package io.jafar.parser.internal_api;

import io.jafar.parser.impl.TypedParserContext;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Cache for storing deserializers by their metadata class keys.
 *
 * <p>This interface extends ConcurrentMap to provide thread-safe storage and retrieval of
 * deserializer instances. It is used to cache generated deserializers to avoid repeated bytecode
 * generation for the same metadata classes.
 */
public interface DeserializerCache
    extends ConcurrentMap<TypedParserContext.DeserializerKey, Deserializer<?>> {

  /**
   * Implementation of DeserializerCache using ConcurrentHashMap.
   *
   * <p>This class provides a thread-safe implementation of the DeserializerCache interface with all
   * the standard ConcurrentMap operations.
   */
  final class Impl implements DeserializerCache {
    /**
     * Public constructor for Impl.
     *
     * <p>This implementation provides a thread-safe cache for deserializers.
     */
    public Impl() {}

    private final ConcurrentMap<TypedParserContext.DeserializerKey, Deserializer<?>> delegate =
        new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> getOrDefault(Object key, Deserializer<?> defaultValue) {
      return delegate.getOrDefault(key, defaultValue);
    }

    /** {@inheritDoc} */
    @Override
    public void forEach(
        BiConsumer<? super TypedParserContext.DeserializerKey, ? super Deserializer<?>> action) {
      delegate.forEach(action);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> putIfAbsent(
        TypedParserContext.DeserializerKey key, Deserializer<?> value) {
      return delegate.putIfAbsent(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public boolean remove(Object key, Object value) {
      return delegate.remove(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public boolean replace(
        TypedParserContext.DeserializerKey key,
        Deserializer<?> oldValue,
        Deserializer<?> newValue) {
      return delegate.replace(key, oldValue, newValue);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> replace(TypedParserContext.DeserializerKey key, Deserializer<?> value) {
      return delegate.replace(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public void replaceAll(
        BiFunction<
                ? super TypedParserContext.DeserializerKey,
                ? super Deserializer<?>,
                ? extends Deserializer<?>>
            function) {
      delegate.replaceAll(function);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> computeIfAbsent(
        TypedParserContext.DeserializerKey key,
        Function<? super TypedParserContext.DeserializerKey, ? extends Deserializer<?>>
            mappingFunction) {
      return delegate.computeIfAbsent(key, mappingFunction);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> computeIfPresent(
        TypedParserContext.DeserializerKey key,
        BiFunction<
                ? super TypedParserContext.DeserializerKey,
                ? super Deserializer<?>,
                ? extends Deserializer<?>>
            remappingFunction) {
      return delegate.computeIfPresent(key, remappingFunction);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> compute(
        TypedParserContext.DeserializerKey key,
        BiFunction<
                ? super TypedParserContext.DeserializerKey,
                ? super Deserializer<?>,
                ? extends Deserializer<?>>
            remappingFunction) {
      return delegate.compute(key, remappingFunction);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> merge(
        TypedParserContext.DeserializerKey key,
        Deserializer<?> value,
        BiFunction<? super Deserializer<?>, ? super Deserializer<?>, ? extends Deserializer<?>>
            remappingFunction) {
      return delegate.merge(key, value, remappingFunction);
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
      return delegate.size();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    /** {@inheritDoc} */
    @Override
    public boolean containsValue(Object value) {
      return delegate.containsValue(value);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> get(Object key) {
      return delegate.get(key);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> put(TypedParserContext.DeserializerKey key, Deserializer<?> value) {
      return delegate.put(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public Deserializer<?> remove(Object key) {
      return delegate.remove(key);
    }

    /** {@inheritDoc} */
    @Override
    public void putAll(
        Map<? extends TypedParserContext.DeserializerKey, ? extends Deserializer<?>> m) {
      delegate.putAll(m);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
      delegate.clear();
    }

    /** {@inheritDoc} */
    @Override
    public Set<TypedParserContext.DeserializerKey> keySet() {
      return delegate.keySet();
    }

    /** {@inheritDoc} */
    @Override
    public Collection<Deserializer<?>> values() {
      return delegate.values();
    }

    /** {@inheritDoc} */
    @Override
    public Set<Entry<TypedParserContext.DeserializerKey, Deserializer<?>>> entrySet() {
      return delegate.entrySet();
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
      return delegate.equals(o);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
      return delegate.hashCode();
    }

    /**
     * Creates an empty immutable map.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @return an empty immutable map
     */
    public static <K, V> Map<K, V> of() {
      return java.util.Collections.emptyMap();
    }

    /**
     * Creates an immutable map containing a single mapping.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the mapping key
     * @param v1 the mapping value
     * @return an immutable map containing the specified mapping
     */
    public static <K, V> Map<K, V> of(K k1, V v1) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(1);
      m.put(k1, v1);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing two mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(2);
      m.put(k1, v1);
      m.put(k2, v2);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing three mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(3);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing four mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(4);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing five mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(5);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing six mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @param k6 the sixth mapping key
     * @param v6 the sixth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(
        K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(6);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      m.put(k6, v6);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing seven mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @param k6 the sixth mapping key
     * @param v6 the sixth mapping value
     * @param k7 the seventh mapping key
     * @param v7 the seventh mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(
        K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(7);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      m.put(k6, v6);
      m.put(k7, v7);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing eight mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @param k6 the sixth mapping key
     * @param v6 the sixth mapping value
     * @param k7 the seventh mapping key
     * @param v7 the seventh mapping value
     * @param k8 the eighth mapping key
     * @param v8 the eighth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(
        K k1,
        V v1,
        K k2,
        V v2,
        K k3,
        V v3,
        K k4,
        V v4,
        K k5,
        V v5,
        K k6,
        V v6,
        K k7,
        V v7,
        K k8,
        V v8) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(8);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      m.put(k6, v6);
      m.put(k7, v7);
      m.put(k8, v8);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing nine mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @param k6 the sixth mapping key
     * @param v6 the sixth mapping value
     * @param k7 the seventh mapping key
     * @param v7 the seventh mapping value
     * @param k8 the eighth mapping key
     * @param v8 the eighth mapping value
     * @param k9 the ninth mapping key
     * @param v9 the ninth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(
        K k1,
        V v1,
        K k2,
        V v2,
        K k3,
        V v3,
        K k4,
        V v4,
        K k5,
        V v5,
        K k6,
        V v6,
        K k7,
        V v7,
        K k8,
        V v8,
        K k9,
        V v9) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(9);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      m.put(k6, v6);
      m.put(k7, v7);
      m.put(k8, v8);
      m.put(k9, v9);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map containing ten mappings.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param k1 the first mapping key
     * @param v1 the first mapping value
     * @param k2 the second mapping key
     * @param v2 the second mapping value
     * @param k3 the third mapping key
     * @param v3 the third mapping value
     * @param k4 the fourth mapping key
     * @param v4 the fourth mapping value
     * @param k5 the fifth mapping key
     * @param v5 the fifth mapping value
     * @param k6 the sixth mapping key
     * @param v6 the sixth mapping value
     * @param k7 the seventh mapping key
     * @param v7 the seventh mapping value
     * @param k8 the eighth mapping key
     * @param v8 the eighth mapping value
     * @param k9 the ninth mapping key
     * @param v9 the ninth mapping value
     * @param k10 the tenth mapping key
     * @param v10 the tenth mapping value
     * @return an immutable map containing the specified mappings
     */
    public static <K, V> Map<K, V> of(
        K k1,
        V v1,
        K k2,
        V v2,
        K k3,
        V v3,
        K k4,
        V v4,
        K k5,
        V v5,
        K k6,
        V v6,
        K k7,
        V v7,
        K k8,
        V v8,
        K k9,
        V v9,
        K k10,
        V v10) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(10);
      m.put(k1, v1);
      m.put(k2, v2);
      m.put(k3, v3);
      m.put(k4, v4);
      m.put(k5, v5);
      m.put(k6, v6);
      m.put(k7, v7);
      m.put(k8, v8);
      m.put(k9, v9);
      m.put(k10, v10);
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map from an array of map entries.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param entries the map entries
     * @return an immutable map containing the specified entries
     */
    @SafeVarargs
    public static <K, V> Map<K, V> ofEntries(Entry<? extends K, ? extends V>... entries) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(entries.length);
      for (Entry<? extends K, ? extends V> e : entries) {
        m.put(e.getKey(), e.getValue());
      }
      return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Creates an immutable map entry.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @param k the key
     * @param v the value
     * @return an immutable map entry
     */
    public static <K, V> Entry<K, V> entry(K k, V v) {
      return new java.util.AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    /**
     * Creates an immutable map containing the mappings of the given map.
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     * @param map the map whose mappings are to be placed in this map
     * @return an immutable map containing the mappings of the given map
     */
    public static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> map) {
      java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>(map);
      return java.util.Collections.unmodifiableMap(m);
    }
  }
}
