package io.jafar.parser.impl;

import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import java.util.Map;

/**
 * Implementation of constant pool storage for JFR data.
 *
 * <p>This class provides efficient storage and retrieval of constant pool entries using a fast
 * long-to-object mapping. It supports both mutable and immutable modes.
 */
public final class ConstantPool {
  /** An empty, immutable constant pool instance. */
  static final ConstantPool EMPTY = new ConstantPool(true);

  /** Whether this constant pool is immutable. */
  private final boolean immutable;

  /** The underlying map storing constant pool entries. */
  private final LongObjectHashMap<Map<String, Object>> cpool = new LongObjectHashMap<>();

  /**
   * Constructs a new ConstantPool with the specified mutability.
   *
   * @param immutable true if the constant pool should be immutable, false otherwise
   */
  ConstantPool(boolean immutable) {
    this.immutable = immutable;
  }

  /** Constructs a new mutable ConstantPool. */
  ConstantPool() {
    this(false);
  }

  /**
   * Adds a constant pool entry with the specified ID and value.
   *
   * @param id the identifier for the constant pool entry
   * @param value the value to store
   * @throws UnsupportedOperationException if this constant pool is immutable
   */
  void add(long id, Map<String, Object> value) {
    if (immutable) {
      throw new UnsupportedOperationException();
    }
    cpool.put(id, value);
  }

  /**
   * Retrieves a constant pool entry by its ID.
   *
   * @param id the identifier of the entry to retrieve
   * @return the constant pool entry, or null if not found
   */
  public Map<String, Object> get(long id) {
    return cpool.get(id);
  }
}
