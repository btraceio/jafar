package io.jafar.parser.internal_api;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.MapValueBuilder;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Mutable implementation of ConstantPool that allows adding offsets and lazy-loading entries.
 *
 * <p>This class provides a mutable constant pool implementation that stores offsets to constant
 * pool entries and lazily deserializes them when accessed. It maintains both offset mappings and
 * cached deserialized objects for efficient access.
 */
public final class MutableConstantPool implements ConstantPool {
  /** Map of constant pool entry IDs to their file offsets. */
  private final Long2LongMap offsets;

  /** Map of constant pool entry IDs to their deserialized objects. */
  private final Long2ObjectMap<Object> entries;

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
    this.offsets = new Long2LongOpenHashMap(count);
    this.entries = new Long2ObjectOpenHashMap<>(count);
    this.stream = chunkStream;
    ParserContext context = chunkStream.getContext();
    clazz = context.getMetadataLookup().getClass(typeId);
  }

  /**
   * Gets a constant pool entry by its ID, lazily deserializing if necessary.
   *
   * @param id the ID of the constant pool entry
   * @return the deserialized object, or {@code null} if not found
   */
  public Object get(long id) {
    long offset = offsets.get(id);
    if (offset > 0) {
      Object o = entries.get(id);
      if (o == null) {
        long pos = stream.position();
        try {
          stream.position(offsets.get(id));
          // Prefer typed deserialization if available; otherwise fall back to generic map building
          Object typed = clazz.read(stream);
          if (typed != null) {
            o = typed;
          } else {
            GenericValueReader r = stream.getContext().get(GenericValueReader.class);
            MapValueBuilder builder = (MapValueBuilder) r.getProcessor();
            builder.onComplexValueStart(null, null, clazz);
            try {
              r.readValue(stream, clazz);
            } catch (java.io.IOException ioe) {
              throw new RuntimeException(ioe);
            }
            builder.onComplexValueEnd(null, null, clazz);
            o = builder.getRoot();
          }
          entries.put(id, o);
        } finally {
          stream.position(pos);
        }
      }
      return o;
    }
    return null;
  }

  @Override
  public int entryCount() {
    return offsets.size();
  }

  @Override
  public java.util.Iterator<Long> ids() {
    // Prefer a primitive long iterator if available
    return new java.util.Iterator<Long>() {
      final it.unimi.dsi.fastutil.longs.LongIterator it = offsets.keySet().iterator();
      @Override public boolean hasNext() { return it.hasNext(); }
      @Override public Long next() { return it.nextLong(); }
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
