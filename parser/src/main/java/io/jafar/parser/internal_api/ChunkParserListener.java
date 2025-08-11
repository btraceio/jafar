package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataEvent;


/**
 * A callback to be provided to {@linkplain StreamingChunkParser#parse(java.nio.file.Path, ChunkParserListener)}
 */
public interface ChunkParserListener {
  /**
   * A no-operation implementation that does nothing for all callbacks.
   */
  ChunkParserListener NOOP = new ChunkParserListener() {};

  /**
   * Called when the recording starts to be processed
   *
   * @param context the current {@linkplain ParserContext} instance
   */
  default void onRecordingStart(ParserContext context) {}

  /**
   * Called for each discovered chunk
   *
   * @param context the current {@linkplain ParserContext} instance
   * @param chunkIndex the chunk index (1-based)
   * @param header     the parsed chunk header
   * @return {@literal false} if the chunk should be skipped
   */
  default boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
    return true;
  }

  /**
   * Called for the chunk metadata event
   *
   * @param context the current {@linkplain ParserContext} instance
   * @param metadata the chunk metadata event
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onMetadata(ParserContext context, MetadataEvent metadata) {
    return true;
  }

  /**
   * Called when a checkpoint event is encountered.
   *
   * @param context the current {@linkplain ParserContext} instance
   * @param checkpoint the checkpoint event
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) { return true; }

  /**
   * Called for each parsed event
   *
   * @param context       the current {@linkplain ParserContext} instance
   * @param typeId        event type id
   * @param eventStartPos the event start position in the stream
   * @param rawSize       the size of the raw event in bytes (how many bytes from eventStartPos)
   * @param payloadSize   the size of the payload in bytes (how many bytes from the current stream pos)
   * @return {@literal false} if the remainder of the chunk should be skipped
   */
  default boolean onEvent(ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    return true;
  }

  /**
   * Called when a chunk is fully processed or skipped
   *
   * @param context the current {@linkplain ParserContext} instance
   * @param chunkIndex the chunk index (1-based)
   * @param skipped    {@literal true} if the chunk was skipped
   * @return {@literal false} if the remaining chunks in the recording should be skipped
   */
  default boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
    return true;
  }

  /**
   * Called when the recording was fully processed
   *
   * @param context the current {@linkplain ParserContext} instance
  */
  default void onRecordingEnd(ParserContext context) {}
}
