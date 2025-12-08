package io.jafar.parser.impl;

import java.util.Set;

/**
 * Cache key combining type ID and field set for safe field name array reuse.
 *
 * <p>This prevents cache collisions when different JFR files use the same type IDs for events with
 * different field structures. The key combines both the metadata type ID and the set of field names
 * to ensure correct cache hits.
 *
 * <p>This Java 21+ record-based implementation provides better performance than the class-based
 * version:
 *
 * <ul>
 *   <li>Compact canonical constructor reduces bytecode size
 *   <li>Immutable by design (no defensive copying needed)
 *   <li>JIT can optimize equals/hashCode more aggressively
 *   <li>Better inline candidates due to final fields and methods
 * </ul>
 *
 * @param typeId the JFR metadata class type ID
 * @param fieldNames the set of field names for this event type
 */
record MapValueBuilderCacheKey(long typeId, Set<String> fieldNames) {}
