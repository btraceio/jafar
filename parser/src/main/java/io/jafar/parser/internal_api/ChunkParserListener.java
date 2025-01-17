package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataEvent;

/**
 * A callback to be provided to {@linkplain StreamingChunkParser#parse(java.nio.file.Path, ChunkParserListener)}
 */
public interface ChunkParserListener {
  /** Called when the recording starts to be processed */
  default void onRecordingStart(RecordingParserContext context) {}

  /**
   * Called for each discovered chunk
   *
   * @param chunkIndex the chunk index (1-based)
   * @param header the parsed chunk header
   * @return {@literal false} if the chunk should be skipped
   */
  default boolean onChunkStart(int chunkIndex, ChunkHeader header, RecordingParserContext context) {
    return true;
  }

  /**
   * Called for the chunk metadata event
   *
   * @param metadata the chunk metadata event
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onMetadata(MetadataEvent metadata) {
    return true;
  }

  default boolean onCheckpoint(CheckpointEvent checkpoint) { return true; }

  /**
   * Called for each parsed event
   *
   * @param typeId event type id
   * @param stream {@linkplain RecordingStream} positioned at the event payload start
   * @param payloadSize the size of the payload in bytes
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onEvent(long typeId, RecordingStream stream, long payloadSize) {
    return true;
  }

  /**
   * Called when a chunk is fully processed or skipped
   *
   * @param chunkIndex the chunk index (1-based)
   * @param skipped {@literal true} if the chunk was skipped
   * @return {@literal false} if the remaining chunks in the recording should be skipped
   */
  default boolean onChunkEnd(int chunkIndex, boolean skipped) {
    return true;
  }

  /** Called when the recording was fully processed */
  default void onRecordingEnd(RecordingParserContext context) {}
}
