package io.jafar.parser.impl;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.util.Map;
import java.util.Objects;

/**
 * Lazy accessor for a constant pool entry that resolves into a Map on-demand.
 *
 * <p>Resolution uses a thread-local {@link RecordingStreamReader} slice obtained from the main
 * {@link RecordingStream}. The slice shares the same underlying memory-mapped buffer but has
 * independent position tracking, allowing thread-safe lazy resolution even when the main parsing
 * stream is being used concurrently (e.g. in the {@link io.jafar.parser.api.EventIterator}
 * producer-consumer pattern). The thread-local caching avoids allocating a new slice per accessor.
 */
public final class ConstantPoolAccessor implements ComplexType {
  private final ParserContext context;
  private final long typeId;
  private final long pointer;
  private volatile Map<String, Object> cached;

  public ConstantPoolAccessor(ParserContext context, MetadataClass type, long pointer) {
    this(context, type.getId(), pointer);
  }

  /**
   * Constructs a new ConstantPoolAccessor using a raw type ID.
   *
   * <p>This constructor is used by generated deserializers where the type ID is a compile-time
   * constant.
   *
   * @param context the parser context for this chunk
   * @param typeId the constant pool type ID
   * @param pointer the constant pool entry pointer (index)
   */
  public ConstantPoolAccessor(ParserContext context, long typeId, long pointer) {
    this.context = context;
    this.typeId = typeId;
    this.pointer = pointer;
  }

  @Override
  public Map<String, Object> getValue() {
    Map<String, Object> v = cached;
    if (v != null) return v;

    synchronized (this) {
      v = cached;
      if (v != null) return v;

      MutableConstantPools pools = (MutableConstantPools) context.getConstantPools();
      MutableConstantPool pool = pools.getConstantPool(typeId);
      if (pool == null) return null;

      long offset = pool.getOffset(pointer);
      if (offset == 0) return null;

      // Use a thread-local reader slice for lazy, allocation-free constant pool access.
      // The slice shares the memory-mapped buffer but has its own position tracking,
      // avoiding contention with the main parsing stream.
      RecordingStream mainStream = context.get(RecordingStream.class);
      RecordingStream cpStream =
          new RecordingStream(mainStream.threadLocalReaderSlice(), context, false);
      try {
        cpStream.position(offset);
        MetadataClass clz = context.getMetadataLookup().getClass(typeId);

        // For simple types, the constant pool stores the unwrapped field value directly
        // We need to read the field value and wrap it in a map with the field name as key
        if (clz.isSimpleType() && clz.getFields().size() == 1) {
          MetadataField singleField = clz.getFields().get(0);
          MetadataClass fieldType = singleField.getType();

          // Recursively unwrap nested simple types to get the actual field type
          while (fieldType.isSimpleType() && fieldType.getFields().size() == 1) {
            fieldType = fieldType.getFields().get(0).getType();
          }

          MapValueBuilder builder = new MapValueBuilder(context);
          GenericValueReader r = new GenericValueReader(builder);

          // Read the single field value directly (it's stored unwrapped in the CP)
          builder.onComplexValueStart(null, null, clz);
          r.readSingleValue(cpStream, clz, fieldType, singleField.getName());
          builder.onComplexValueEnd(null, null, clz);

          v = builder.getRoot();
          cached = v;
          return v;
        }

        // For complex types, read as full object
        MapValueBuilder builder = new MapValueBuilder(context);
        GenericValueReader r = new GenericValueReader(builder);
        builder.onComplexValueStart(null, null, clz);
        r.readValue(cpStream, clz);
        builder.onComplexValueEnd(null, null, clz);
        v = builder.getRoot();
        cached = v;
        return v;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public String toString() {
    return Objects.toString(getValue());
  }
}
