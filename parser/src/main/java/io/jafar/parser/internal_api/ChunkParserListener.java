package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

/**
 * A callback to be provided to {@linkplain StreamingChunkParser#parse(java.nio.file.Path, ChunkParserListener)}
 */
public interface ChunkParserListener<T extends ParserContext> {
  ChunkParserListener<?> NOOP = new ChunkParserListener<>() {};

  /** Called when the recording starts to be processed */
  default void onRecordingStart(T context) {}

  /**
   * Called for each discovered chunk
   *
   * @param chunkIndex the chunk index (1-based)
   * @param header     the parsed chunk header
   * @return {@literal false} if the chunk should be skipped
   */
  default boolean onChunkStart(T context, int chunkIndex, ChunkHeader header) {
    return true;
  }

  /**
   * Called for the chunk metadata event
   *
   * @param context
   * @param metadata the chunk metadata event
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onMetadata(T context, MetadataEvent metadata) {
    return true;
  }

  default boolean onCheckpoint(T context, CheckpointEvent checkpoint) { return true; }

  /**
   * Called for each parsed event
   *
   * @param typeId        event type id
   * @param stream        {@linkplain RecordingStream} positioned at the event payload start
   * @param eventStartPos the event start position in the stream
   * @param rawSize       the size of the raw event in bytes (how many bytes from eventStartPos)
   * @param payloadSize   the size of the payload in bytes (how many bytes from the current stream pos)
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onEvent(T context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    return true;
  }

  /**
   * Called when a chunk is fully processed or skipped
   *
   * @param context
   * @param chunkIndex the chunk index (1-based)
   * @param skipped    {@literal true} if the chunk was skipped
   * @return {@literal false} if the remaining chunks in the recording should be skipped
   */
  default boolean onChunkEnd(T context, int chunkIndex, boolean skipped) {
    return true;
  }

  /** Called when the recording was fully processed */
  default void onRecordingEnd(T context) {}
}
