package io.jafar.parser.internal_api;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.MapValueBuilder;
import io.jafar.parser.internal_api.collections.LongLongHashMap;
import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.Map;

/**
 * Mutable implementation of ConstantPool that allows adding offsets and lazy-loading entries.
 *
 * <p>This class provides a mutable constant pool implementation that stores offsets to constant
 * pool entries and lazily deserializes them when accessed. It maintains both offset mappings and
 * cached deserialized objects for efficient access.
 */
public final class MutableConstantPool implements ConstantPool {
  /**
   * Map of constant pool entry IDs to their file offsets.
   *
   * <p><b>Thread-safety invariant:</b> written exclusively during checkpoint event processing
   * (producer thread, before any events are enqueued) and read by both the producer ({@link
   * #get(long)}) and the consumer ({@link #getAsMap(long)}) threads thereafter. The {@link
   * java.util.concurrent.ArrayBlockingQueue} hand-off between producer and consumer establishes the
   * necessary happens-before: all offset writes complete before the first event is dequeued, so the
   * consumer always sees a fully-populated map without additional synchronization.
   */
  private final LongLongHashMap offsets;

  /**
   * Map of constant pool entry IDs to their deserialized objects (typed path).
   *
   * <p>Accessed only from the chunk-parser (producer) thread; no synchronization needed.
   */
  private final LongObjectHashMap<Object> entries;

  /**
   * Map of constant pool entry IDs to their Map representations (generic/map path).
   *
   * <p><b>Design constraint:</b> accessed only from the single consumer (event-iterator) thread. No
   * synchronization is used; correctness requires that {@link #getAsMap(long)} is never called
   * concurrently from multiple threads. Callers that introduce parallel consumer threads (e.g.
   * parallel streams over events) must provide external synchronization or switch to a concurrent
   * map. Thread-local reader slices provide position isolation from the producer.
   */
  private final LongObjectHashMap<Map<String, Object>> mapEntries;

  /** The recording stream for reading constant pool data. */
  private final RecordingStream stream;

  /** The metadata class for the constant pool type. */
  private final MetadataClass clazz;

  /**
   * Constructs a new MutableConstantPool with the specified parameters.
   *
   * @param chunkStream the recording stream to read from
   * @param typeId the type ID for the constant pool
   * @param count the expected number of constant pool entries
   */
  public MutableConstantPool(RecordingStream chunkStream, long typeId, int count) {
    this.offsets = new LongLongHashMap(count);
    this.entries = new LongObjectHashMap<>(count);
    this.mapEntries = new LongObjectHashMap<>(count);
    this.stream = chunkStream;
    ParserContext context = chunkStream.getContext();
    clazz = context.getMetadataLookup().getClass(typeId);
  }

  /**
   * Gets a constant pool entry by its ID, lazily deserializing if necessary.
   *
   * <p>Each thread uses its own thread-local reader slice (sharing the same memory-mapped buffer
   * but with independent position tracking). Position save/restore handles re-entrancy when reading
   * a CP entry triggers resolution of another CP type (e.g., string pool lookup).
   *
   * @param id the ID of the constant pool entry
   * @return the deserialized object, or {@code null} if not found
   */
  public Object get(long id) {
    long offset = offsets.get(id);
    if (offset > 0) {
      Object o = entries.get(id);
      if (o == null) {
        // Thread-local slice: one per thread, reused across calls (no per-call allocation).
        // Save/restore position for re-entrancy (e.g., reading a CP entry triggers string
        // pool lookup which re-enters get() on a different pool sharing the same slice).
        RecordingStream cpStream = stream.threadLocalStreamSlice();
        long savedPos = cpStream.position();
        try {
          cpStream.position(offset);
          o = clazz.read(cpStream);
          if (o == null) {
            // For String constant pool entries, read the string directly.
            // Using the generic MapValueBuilder path would wrap the string in a Map,
            // breaking ParsingUtils.readUTF8() which expects stringPool.get() to return
            // a String (checked via instanceof String).
            // Note: in JFR, String is the only primitive type stored in a constant pool.
            if (clazz.isPrimitive() && "java.lang.String".equals(clazz.getName())) {
              try {
                o = cpStream.readUTF8();
              } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
              }
            } else {
              MapValueBuilder builder = new MapValueBuilder(stream.getContext());
              GenericValueReader r = new GenericValueReader(builder);
              builder.onComplexValueStart(null, null, clazz);
              try {
                r.readValue(cpStream, clazz);
              } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
              }
              builder.onComplexValueEnd(null, null, clazz);
              o = builder.getRoot();
            }
          }
          if (o != null) {
            entries.put(id, o);
          }
        } finally {
          cpStream.position(savedPos);
        }
      }
      return o;
    }
    return null;
  }

  /**
   * Gets a constant pool entry as a {@code Map<String, Object>} representation, using a pool-level
   * cache so that each entry is deserialized at most once regardless of how many events reference
   * it.
   *
   * <p>This is the primary method for generic (map-based) constant pool resolution. Unlike per-
   * accessor deserialization, the result is shared across all accessors pointing to the same entry,
   * eliminating O(events) redundant deserializations.
   *
   * @param id the ID of the constant pool entry
   * @return the map representation, or {@code null} if not found
   */
  public Map<String, Object> getAsMap(long id) {
    long offset = offsets.get(id);
    if (offset <= 0) return null;

    Map<String, Object> v = mapEntries.get(id);
    if (v != null) return v;

    RecordingStream cpStream = stream.threadLocalStreamSlice();
    long savedPos = cpStream.position();
    try {
      cpStream.position(offset);

      // For simple types (e.g. String CP), the pool stores the unwrapped field value directly.
      // Read it and wrap in a single-entry map keyed by the field name.
      if (clazz.isSimpleType() && clazz.getFields().size() == 1) {
        MetadataField singleField = clazz.getFields().get(0);
        MetadataClass fieldType = singleField.getType();
        // Unwrap nested single-field simple types (depth-guarded against malformed metadata).
        int depth = 0;
        while (fieldType.isSimpleType() && fieldType.getFields().size() == 1) {
          if (++depth > 10) break;
          fieldType = fieldType.getFields().get(0).getType();
        }
        MapValueBuilder builder = new MapValueBuilder(stream.getContext());
        GenericValueReader r = new GenericValueReader(builder);
        builder.onComplexValueStart(null, null, clazz);
        try {
          r.readSingleValue(cpStream, clazz, fieldType, singleField.getName());
        } catch (java.io.IOException ioe) {
          throw new RuntimeException(ioe);
        }
        builder.onComplexValueEnd(null, null, clazz);
        v = builder.getRoot();
      } else {
        // Complex type: full object deserialization
        MapValueBuilder builder = new MapValueBuilder(stream.getContext());
        GenericValueReader r = new GenericValueReader(builder);
        builder.onComplexValueStart(null, null, clazz);
        try {
          r.readValue(cpStream, clazz);
        } catch (java.io.IOException ioe) {
          throw new RuntimeException(ioe);
        }
        builder.onComplexValueEnd(null, null, clazz);
        v = builder.getRoot();
      }
      if (v != null) {
        mapEntries.put(id, v);
      }
      return v;
    } finally {
      cpStream.position(savedPos);
    }
  }

  @Override
  public int entryCount() {
    return offsets.size();
  }

  @Override
  public java.util.Iterator<Long> ids() {
    // Prefer a primitive long iterator if available
    return new java.util.Iterator<Long>() {
      final LongLongHashMap.LongIterator it = offsets.keyIterator();

      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public Long next() {
        return it.nextLong();
      }
    };
  }

  @Override
  public boolean isIndexed() {
    return !offsets.isEmpty();
  }

  @Override
  public void ensureIndexed() {
    // No-op: offsets are populated during parsing; nothing to do here.
  }

  /**
   * Checks if the constant pool contains an entry with the specified key.
   *
   * @param key the key to check
   * @return {@code true} if the key exists, {@code false} otherwise
   */
  public boolean containsKey(long key) {
    return offsets.containsKey(key);
  }

  /**
   * Adds an offset mapping for a constant pool entry.
   *
   * @param id the ID of the constant pool entry
   * @param offset the file offset where the entry is located
   */
  public void addOffset(long id, long offset) {
    offsets.put(id, offset);
  }

  /**
   * Returns the stream offset for the given constant pool entry id or {@code 0} if unknown.
   *
   * <p>This allows lazy materialization of constant pool values by positioning the recording stream
   * at the correct location and deserializing on-demand elsewhere.
   *
   * @param id constant pool entry id
   * @return absolute offset within the chunk stream, or {@code 0} if not present
   */
  public long getOffset(long id) {
    return offsets.get(id);
  }

  /** {@inheritDoc} */
  @Override
  public int size() {
    return entries.size();
  }

  /** {@inheritDoc} */
  @Override
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** {@inheritDoc} */
  @Override
  public MetadataClass getType() {
    return clazz;
  }
}
