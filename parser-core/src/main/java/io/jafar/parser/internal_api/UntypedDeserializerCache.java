package io.jafar.parser.internal_api;

import io.jafar.parser.api.Internal;
import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Cache for storing untyped event deserializers by the structure of their event type.
 *
 * <p>This interface extends ConcurrentMap to provide thread-safe storage and retrieval of generated
 * {@link UntypedEventDeserializer} instances. It is used to cache generated deserializers to avoid
 * repeated bytecode generation for the same event types.
 *
 * <p>Keys are {@link MetadataFingerprint}s of the event type and every type reachable from it, not
 * type ids: JFR type ids are scoped to a chunk, and one cache serves every chunk of a parse, so a
 * recording whose chunks give the same id to different types must not share a deserializer.
 * Structurally identical types in different chunks do share one.
 */
@Internal
public interface UntypedDeserializerCache
    extends ConcurrentMap<MetadataFingerprint, UntypedEventDeserializer> {

  /**
   * Implementation of UntypedDeserializerCache using ConcurrentHashMap.
   *
   * <p>This class provides a thread-safe implementation of the UntypedDeserializerCache interface
   * with all the standard ConcurrentMap operations.
   */
  final class Impl implements UntypedDeserializerCache {
    /** Public constructor for Impl. */
    public Impl() {}

    private final ConcurrentMap<MetadataFingerprint, UntypedEventDeserializer> delegate =
        new ConcurrentHashMap<>();

    @Override
    public UntypedEventDeserializer getOrDefault(
        Object key, UntypedEventDeserializer defaultValue) {
      return delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(
        BiConsumer<? super MetadataFingerprint, ? super UntypedEventDeserializer> action) {
      delegate.forEach(action);
    }

    @Override
    public UntypedEventDeserializer putIfAbsent(
        MetadataFingerprint key, UntypedEventDeserializer value) {
      return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
      return delegate.remove(key, value);
    }

    @Override
    public boolean replace(
        MetadataFingerprint key,
        UntypedEventDeserializer oldValue,
        UntypedEventDeserializer newValue) {
      return delegate.replace(key, oldValue, newValue);
    }

    @Override
    public UntypedEventDeserializer replace(
        MetadataFingerprint key, UntypedEventDeserializer value) {
      return delegate.replace(key, value);
    }

    @Override
    public void replaceAll(
        BiFunction<
                ? super MetadataFingerprint,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            function) {
      delegate.replaceAll(function);
    }

    @Override
    public UntypedEventDeserializer computeIfAbsent(
        MetadataFingerprint key,
        Function<? super MetadataFingerprint, ? extends UntypedEventDeserializer> mappingFunction) {
      return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public UntypedEventDeserializer computeIfPresent(
        MetadataFingerprint key,
        BiFunction<
                ? super MetadataFingerprint,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            remappingFunction) {
      return delegate.computeIfPresent(key, remappingFunction);
    }

    @Override
    public UntypedEventDeserializer compute(
        MetadataFingerprint key,
        BiFunction<
                ? super MetadataFingerprint,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            remappingFunction) {
      return delegate.compute(key, remappingFunction);
    }

    @Override
    public UntypedEventDeserializer merge(
        MetadataFingerprint key,
        UntypedEventDeserializer value,
        BiFunction<
                ? super UntypedEventDeserializer,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            remappingFunction) {
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
    public UntypedEventDeserializer get(Object key) {
      return delegate.get(key);
    }

    @Override
    public UntypedEventDeserializer put(MetadataFingerprint key, UntypedEventDeserializer value) {
      return delegate.put(key, value);
    }

    @Override
    public UntypedEventDeserializer remove(Object key) {
      return delegate.remove(key);
    }

    @Override
    public void putAll(Map<? extends MetadataFingerprint, ? extends UntypedEventDeserializer> m) {
      delegate.putAll(m);
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public Set<MetadataFingerprint> keySet() {
      return delegate.keySet();
    }

    @Override
    public Collection<UntypedEventDeserializer> values() {
      return delegate.values();
    }

    @Override
    public Set<Entry<MetadataFingerprint, UntypedEventDeserializer>> entrySet() {
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
  }
}
