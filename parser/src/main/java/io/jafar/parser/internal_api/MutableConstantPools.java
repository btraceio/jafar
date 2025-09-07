package io.jafar.parser.internal_api;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ConstantPools;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.stream.Stream;

/**
 * Mutable implementation of ConstantPools that manages multiple constant pools by type ID.
 *
 * <p>This class provides a mutable collection of constant pools, allowing dynamic addition of new
 * constant pools and management of their ready state. It maintains a mapping from type IDs to their
 * corresponding constant pool instances.
 */
public final class MutableConstantPools implements ConstantPools {
  /** Map of type IDs to their corresponding constant pool instances. */
  private final Long2ObjectMap<MutableConstantPool> poolMap = new Long2ObjectOpenHashMap<>();

  /** Flag indicating whether all constant pools are ready for use. */
  private boolean ready = false;

  /** Constructs a new MutableConstantPools instance. */
  public MutableConstantPools() {}

  /** {@inheritDoc} */
  @Override
  public MutableConstantPool getConstantPool(long typeId) {
    return poolMap.get(typeId);
  }

  /**
   * Adds a new constant pool for the specified type ID or returns an existing one.
   *
   * @param chunkStream the recording stream to read from
   * @param typeId the type ID for the constant pool
   * @param count the expected number of constant pool entries
   * @return the constant pool instance for the specified type ID
   */
  public MutableConstantPool addOrGetConstantPool(
      RecordingStream chunkStream, long typeId, int count) {
    MutableConstantPool p = poolMap.get(typeId);
    if (p == null) {
      p = new MutableConstantPool(chunkStream, typeId, count);
      poolMap.put(typeId, p);
    }
    return p;
  }

  /** {@inheritDoc} */
  @Override
  public boolean hasConstantPool(long typeId) {
    return poolMap.containsKey(typeId);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isReady() {
    return ready;
  }

  /** Marks all constant pools as ready for use. */
  public void setReady() {
    ready = true;
  }

  /** {@inheritDoc} */
  @Override
  public Stream<? extends ConstantPool> pools() {
    return poolMap.values().stream();
  }
}
