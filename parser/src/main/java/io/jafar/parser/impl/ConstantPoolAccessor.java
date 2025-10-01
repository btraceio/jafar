package io.jafar.parser.impl;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Map;
import java.util.Objects;

/** Lazy accessor for a constant pool entry that resolves into a Map on-demand. */
final class ConstantPoolAccessor implements ComplexType {
  private final ParserContext context;
  private final long typeId;
  private final long pointer;

  private volatile Map<String, Object> cached;

  ConstantPoolAccessor(ParserContext context, MetadataClass type, long pointer) {
    this.context = context;
    this.typeId = type.getId();
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

      RecordingStream stream = context.get(RecordingStream.class);
      long pos = stream.position();
      try {
        stream.position(offset);
        MetadataClass clz = context.getMetadataLookup().getClass(typeId);
        MapValueBuilder builder = new MapValueBuilder(context);
        GenericValueReader r = new GenericValueReader(builder);
        builder.onComplexValueStart(null, null, clz);
        r.readValue(stream, clz);
        builder.onComplexValueEnd(null, null, clz);
        v = builder.getRoot();
        cached = v;
        return v;
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        stream.position(pos);
      }
    }
  }

  @Override
  public String toString() {
    return Objects.toString(getValue());
  }
}
