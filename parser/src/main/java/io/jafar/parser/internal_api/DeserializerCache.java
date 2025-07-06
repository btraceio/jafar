package io.jafar.parser.internal_api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface DeserializerCache extends ConcurrentMap<RecordingParserContext.DeserializerKey, Deserializer<?>> {
    final class Impl implements DeserializerCache {
        private final ConcurrentMap<RecordingParserContext.DeserializerKey, Deserializer<?>> delegate = new ConcurrentHashMap<>();

        @Override
        public Deserializer<?> getOrDefault(Object key, Deserializer<?> defaultValue) {
            return delegate.getOrDefault(key, defaultValue);
        }

        @Override
        public void forEach(BiConsumer<? super RecordingParserContext.DeserializerKey, ? super Deserializer<?>> action) {
            delegate.forEach(action);
        }

        @Override
        public Deserializer<?> putIfAbsent(RecordingParserContext.DeserializerKey key, Deserializer<?> value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean remove(Object key, Object value) {
            return delegate.remove(key, value);
        }

        @Override
        public boolean replace(RecordingParserContext.DeserializerKey key, Deserializer<?> oldValue, Deserializer<?> newValue) {
            return delegate.replace(key, oldValue, newValue);
        }

        @Override
        public Deserializer<?> replace(RecordingParserContext.DeserializerKey key, Deserializer<?> value) {
            return delegate.replace(key, value);
        }

        @Override
        public void replaceAll(BiFunction<? super RecordingParserContext.DeserializerKey, ? super Deserializer<?>, ? extends Deserializer<?>> function) {
            delegate.replaceAll(function);
        }

        @Override
        public Deserializer<?> computeIfAbsent(RecordingParserContext.DeserializerKey key, Function<? super RecordingParserContext.DeserializerKey, ? extends Deserializer<?>> mappingFunction) {
            return delegate.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public Deserializer<?> computeIfPresent(RecordingParserContext.DeserializerKey key, BiFunction<? super RecordingParserContext.DeserializerKey, ? super Deserializer<?>, ? extends Deserializer<?>> remappingFunction) {
            return delegate.computeIfPresent(key, remappingFunction);
        }

        @Override
        public Deserializer<?> compute(RecordingParserContext.DeserializerKey key, BiFunction<? super RecordingParserContext.DeserializerKey, ? super Deserializer<?>, ? extends Deserializer<?>> remappingFunction) {
            return delegate.compute(key, remappingFunction);
        }

        @Override
        public Deserializer<?> merge(RecordingParserContext.DeserializerKey key, Deserializer<?> value, BiFunction<? super Deserializer<?>, ? super Deserializer<?>, ? extends Deserializer<?>> remappingFunction) {
            return delegate.merge(key, value, remappingFunction);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate.containsValue(value);
        }

        @Override
        public Deserializer<?> get(Object key) {
            return delegate.get(key);
        }

        @Override
        public Deserializer<?> put(RecordingParserContext.DeserializerKey key, Deserializer<?> value) {
            return delegate.put(key, value);
        }

        @Override
        public Deserializer<?> remove(Object key) {
            return delegate.remove(key);
        }

        @Override
        public void putAll(Map<? extends RecordingParserContext.DeserializerKey, ? extends Deserializer<?>> m) {
            delegate.putAll(m);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Set<RecordingParserContext.DeserializerKey> keySet() {
            return delegate.keySet();
        }

        @Override
        public Collection<Deserializer<?>> values() {
            return delegate.values();
        }

        @Override
        public Set<Entry<RecordingParserContext.DeserializerKey, Deserializer<?>>> entrySet() {
            return delegate.entrySet();
        }

        @Override
        public boolean equals(Object o) {
            return delegate.equals(o);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        public static <K, V> Map<K, V> of() {
            return Map.of();
        }

        public static <K, V> Map<K, V> of(K k1, V v1) {
            return Map.of(k1, v1);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
            return Map.of(k1, v1, k2, v2);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
            return Map.of(k1, v1, k2, v2, k3, v3);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
        }

        public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
            return Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
        }

        @SafeVarargs
        public static <K, V> Map<K, V> ofEntries(Entry<? extends K, ? extends V>... entries) {
            return Map.ofEntries(entries);
        }

        public static <K, V> Entry<K, V> entry(K k, V v) {
            return Map.entry(k, v);
        }

        public static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> map) {
            return Map.copyOf(map);
        }
    }
}
