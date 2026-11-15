package io.jafar.parser.internal_api;

import io.jafar.parser.api.Internal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Cache for storing untyped event deserializers by the structure they were generated for.
 *
 * <p>This interface extends ConcurrentMap to provide thread-safe storage and retrieval of generated
 * {@link UntypedEventDeserializer} instances. It is used to cache generated deserializers to avoid
 * repeated bytecode generation for the same event types.
 *
 * <p>Keys are structural keys computed by {@code UntypedCodeGenerator.cacheKey} in {@code
 * parser-codegen}: the event type's name and id plus the layout of every type reachable from it.
 * They are <b>not</b> metadata class ids. One cache is shared by all chunks of a recording, and
 * chunks written by different producers assign the same id to different types (in {@code
 * tck-test.jfr}, id 125 is {@code jdk.CompilerConfiguration} in chunk 1 and {@code
 * datadog.ProfilerCounter} in chunk 2); keying by id handed one type's deserializer to the other.
 */
@Internal
public interface UntypedDeserializerCache extends ConcurrentMap<String, UntypedEventDeserializer> {

  /**
   * Implementation of UntypedDeserializerCache using ConcurrentHashMap.
   *
   * <p>This class provides a thread-safe implementation of the UntypedDeserializerCache interface
   * with all the standard ConcurrentMap operations.
   */
  final class Impl implements UntypedDeserializerCache {
    /** Public constructor for Impl. */
    public Impl() {}

    private final ConcurrentMap<String, UntypedEventDeserializer> delegate =
        new ConcurrentHashMap<>();

    @Override
    public UntypedEventDeserializer getOrDefault(
        Object key, UntypedEventDeserializer defaultValue) {
      return delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super UntypedEventDeserializer> action) {
      delegate.forEach(action);
    }

    @Override
    public UntypedEventDeserializer putIfAbsent(String key, UntypedEventDeserializer value) {
      return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
      return delegate.remove(key, value);
    }

    @Override
    public boolean replace(
        String key, UntypedEventDeserializer oldValue, UntypedEventDeserializer newValue) {
      return delegate.replace(key, oldValue, newValue);
    }

    @Override
    public UntypedEventDeserializer replace(String key, UntypedEventDeserializer value) {
      return delegate.replace(key, value);
    }

    @Override
    public void replaceAll(
        BiFunction<
                ? super String,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            function) {
      delegate.replaceAll(function);
    }

    @Override
    public UntypedEventDeserializer computeIfAbsent(
        String key, Function<? super String, ? extends UntypedEventDeserializer> mappingFunction) {
      return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public UntypedEventDeserializer computeIfPresent(
        String key,
        BiFunction<
                ? super String,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            remappingFunction) {
      return delegate.computeIfPresent(key, remappingFunction);
    }

    @Override
    public UntypedEventDeserializer compute(
        String key,
        BiFunction<
                ? super String,
                ? super UntypedEventDeserializer,
                ? extends UntypedEventDeserializer>
            remappingFunction) {
      return delegate.compute(key, remappingFunction);
    }

    @Override
    public UntypedEventDeserializer merge(
        String key,
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
    public UntypedEventDeserializer put(String key, UntypedEventDeserializer value) {
      return delegate.put(key, value);
    }

    @Override
    public UntypedEventDeserializer remove(Object key) {
      return delegate.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends UntypedEventDeserializer> m) {
      delegate.putAll(m);
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public Set<String> keySet() {
      return delegate.keySet();
    }

    @Override
    public Collection<UntypedEventDeserializer> values() {
      return delegate.values();
    }

    @Override
    public Set<Entry<String, UntypedEventDeserializer>> entrySet() {
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
