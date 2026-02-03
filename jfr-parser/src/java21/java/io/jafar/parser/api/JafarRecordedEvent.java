package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of a parsed JFR event (Java 21+ record-based implementation).
 *
 * <p>This record encapsulates all data from an event at the time it was read, allowing it to be
 * stored and processed outside the parsing callback context.
 *
 * <p>The event data is accessible through:
 *
 * <ul>
 *   <li>{@link #type()} - the event type metadata (name, fields, annotations)
 *   <li>{@link #value()} - the event field values as a map
 *   <li>{@link #streamPosition()} - the byte position in the recording stream
 *   <li>{@link #chunkInfo()} - chunk metadata (timing, duration, conversions)
 * </ul>
 */
public record JafarRecordedEvent(
    MetadataClass type,
    Map<String, Object> value,
    long streamPosition,
    Control.ChunkInfo chunkInfo) {
  /** Compact constructor that ensures immutability of the value map. */
  public JafarRecordedEvent {
    value = Collections.unmodifiableMap(value);
  }

  /**
   * Returns the event type name (e.g., "jdk.CPULoad", "jdk.ThreadStart").
   *
   * @return the event type name
   */
  public String typeName() {
    return type.getName();
  }

  /**
   * Returns the event type ID.
   *
   * @return the event type ID
   */
  public long typeId() {
    return type.getId();
  }
}
