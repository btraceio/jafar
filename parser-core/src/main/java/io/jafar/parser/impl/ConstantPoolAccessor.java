package io.jafar.parser.impl;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Map;
import java.util.Objects;

/**
 * Lazy accessor for a constant pool entry that resolves into a Map on-demand.
 *
 * <p>Resolution delegates to {@link MutableConstantPool#getAsMap(long)} which maintains a
 * pool-level cache. This means each unique CP entry is deserialized at most once, regardless of how
 * many events reference it — turning O(events × CP_fields) redundant deserializations into
 * O(unique_CP_entries).
 */
public final class ConstantPoolAccessor implements ComplexType {
  private final ParserContext context;
  private final long typeId;
  private final long pointer;

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
    MutableConstantPools pools = (MutableConstantPools) context.getConstantPools();
    MutableConstantPool pool = pools.getConstantPool(typeId);
    if (pool == null) return null;
    return pool.getAsMap(pointer);
  }

  @Override
  public String toString() {
    return Objects.toString(getValue());
  }
}
