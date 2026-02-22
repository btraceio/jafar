package io.jafar.parser.api;

import java.util.stream.Stream;

/** Access to constant pools keyed by type id. */
public interface ConstantPools {
  /**
   * Gets the constant pool for a specific type ID.
   *
   * @param typeId the type identifier for which to retrieve the constant pool
   * @return the constant pool associated with the given type ID
   */
  ConstantPool getConstantPool(long typeId);

  /**
   * Checks if a constant pool exists for the given type ID.
   *
   * @param typeId the type identifier to check
   * @return true if a constant pool exists for the given type ID, false otherwise
   */
  boolean hasConstantPool(long typeId);

  /**
   * True once all constant pools for the current chunk are available.
   *
   * @return true if all constant pools are ready, false otherwise
   */
  boolean isReady();

  /** Marks all constant pools as ready for the current chunk. */
  void setReady();

  /**
   * Returns a stream of all available constant pools.
   *
   * @return a stream containing all constant pools
   */
  Stream<? extends ConstantPool> pools();
}
