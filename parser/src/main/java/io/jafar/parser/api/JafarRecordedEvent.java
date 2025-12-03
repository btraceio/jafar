package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a parsed JFR event.
 *
 * <p>This class encapsulates all data from an event at the time it was read, allowing it to be
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
public final class JafarRecordedEvent {
  private final MetadataClass type;
  private final Map<String, Object> value;
  private final long streamPosition;
  private final Control.ChunkInfo chunkInfo;

  /**
   * Creates a new immutable event snapshot.
   *
   * @param type the metadata class type of the event
   * @param value the event data as a map of field names to values
   * @param streamPosition the byte position in the recording stream when this event was read
   * @param chunkInfo metadata about the chunk containing this event
   */
  public JafarRecordedEvent(
      MetadataClass type,
      Map<String, Object> value,
      long streamPosition,
      Control.ChunkInfo chunkInfo) {
    this.type = type;
    this.value = Collections.unmodifiableMap(value);
    this.streamPosition = streamPosition;
    this.chunkInfo = chunkInfo;
  }

  /**
   * Returns the event type metadata.
   *
   * @return the event type metadata
   */
  public MetadataClass type() {
    return type;
  }

  /**
   * Returns the event field values as an immutable map.
   *
   * @return the event field values
   */
  public Map<String, Object> value() {
    return value;
  }

  /**
   * Returns the byte position in the recording stream.
   *
   * @return the stream position
   */
  public long streamPosition() {
    return streamPosition;
  }

  /**
   * Returns the chunk metadata.
   *
   * @return the chunk metadata
   */
  public Control.ChunkInfo chunkInfo() {
    return chunkInfo;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    JafarRecordedEvent that = (JafarRecordedEvent) o;
    return streamPosition == that.streamPosition
        && Objects.equals(type, that.type)
        && Objects.equals(value, that.value)
        && Objects.equals(chunkInfo, that.chunkInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, value, streamPosition, chunkInfo);
  }

  @Override
  public String toString() {
    return "JafarRecordedEvent{"
        + "type="
        + (type != null ? type.getName() : "null")
        + ", streamPosition="
        + streamPosition
        + ", valueSize="
        + (value != null ? value.size() : 0)
        + '}';
  }
}
