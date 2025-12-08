package io.jafar.parser.impl;

import java.util.Objects;
import java.util.Set;

/**
 * Cache key combining type ID and field set for safe field name array reuse.
 *
 * <p>This prevents cache collisions when different JFR files use the same type IDs for events with
 * different field structures. The key combines both the metadata type ID and the set of field names
 * to ensure correct cache hits.
 *
 * <p>This class is optimized for Java 8-17. For Java 21+, a record-based implementation provides
 * better performance and more compact bytecode.
 */
class MapValueBuilderCacheKey {
  private final long typeId;
  private final Set<String> fieldNames;
  private final int hashCode;

  /**
   * Constructs a cache key with the given type ID and field names.
   *
   * @param typeId the JFR metadata class type ID
   * @param fieldNames the set of field names for this event type
   */
  MapValueBuilderCacheKey(long typeId, Set<String> fieldNames) {
    this.typeId = typeId;
    this.fieldNames = fieldNames;
    this.hashCode = Objects.hash(typeId, fieldNames);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof MapValueBuilderCacheKey)) return false;
    MapValueBuilderCacheKey that = (MapValueBuilderCacheKey) o;
    return typeId == that.typeId && fieldNames.equals(that.fieldNames);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }
}
