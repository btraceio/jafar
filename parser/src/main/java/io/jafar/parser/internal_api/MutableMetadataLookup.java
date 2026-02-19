package io.jafar.parser.internal_api;

import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable implementation of MetadataLookup that allows dynamic addition of metadata.
 *
 * <p>This class provides a mutable metadata lookup implementation that maintains string tables and
 * metadata classes. It allows adding new metadata classes and binding deserializers dynamically
 * during parsing.
 */
public final class MutableMetadataLookup implements MetadataLookup {
  /**
   * Public constructor for MutableMetadataLookup.
   *
   * <p>This class provides a mutable metadata lookup implementation that maintains string tables
   * and metadata classes.
   */
  public MutableMetadataLookup() {}

  /** Array of string constants accumulated across all chunks. */
  private String[] strings;

  /** Map of class IDs to their metadata class instances. */
  private final LongObjectHashMap<MetadataClass> classes = new LongObjectHashMap<>();

  private final Map<String, MetadataClass> classesByName = new HashMap<>();

  /** {@inheritDoc} */
  @Override
  public String getString(int idx) {
    if (strings == null || idx < 0 || idx >= strings.length) {
      throw new IllegalArgumentException(
          String.format(
              "String index %d out of bounds (string table size: %d)",
              idx, strings == null ? 0 : strings.length));
    }
    return strings[idx];
  }

  /** {@inheritDoc} */
  @Override
  public MetadataClass getClass(long id) {
    return classes.get(id);
  }

  @Override
  public MetadataClass getClass(String name) {
    return classesByName.get(name);
  }

  /**
   * Adds a metadata class to the lookup, or returns an existing one if already present.
   *
   * @param id the ID of the metadata class
   * @param clazz the metadata class to add
   * @return the metadata class instance (either the new one or existing one)
   */
  public MetadataClass addClass(long id, MetadataClass clazz) {
    MetadataClass rslt = classes.get(id);
    if (rslt == null) {
      rslt = clazz;
      classes.put(id, clazz);
      classesByName.put(rslt.getName(), clazz);
    }
    return rslt;
  }

  /**
   * Sets the string table for this metadata lookup.
   *
   * @param stringTable the array of string constants to use
   */
  public void setStringtable(String[] stringTable) {
    this.strings = Arrays.copyOf(stringTable, stringTable.length);
  }

  /**
   * Binds deserializers for all metadata classes in this lookup.
   *
   * <p>This method iterates through all registered metadata classes and binds their deserializers,
   * preparing them for use.
   */
  public void bindDeserializers() {
    for (MetadataClass clazz : classes.values()) {
      clazz.bindDeserializer();
    }
  }

  /**
   * Clears all metadata from this lookup.
   *
   * <p>This method removes all string tables and metadata classes, effectively resetting the lookup
   * to an empty state.
   */
  public void clear() {
    strings = null;
    classes.clear();
    classesByName.clear();
  }
}
