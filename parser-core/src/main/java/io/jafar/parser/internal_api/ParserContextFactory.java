package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;

/**
 * Factory interface for creating parser contexts.
 *
 * <p>This interface provides methods to create new parser contexts for different chunks in JFR
 * recordings.
 */
public interface ParserContextFactory {
  /**
   * Creates a new parser context with default values.
   *
   * @return a new parser context
   */
  default ParserContext newContext() {
    return newContext(null, 0);
  }

  /**
   * Creates a new parser context with the specified parent and chunk index.
   *
   * @param parent the parent parser context, or null if this is a root context
   * @param chunkIndex the index of the chunk this context is for
   * @return a new parser context
   */
  ParserContext newContext(ParserContext parent, int chunkIndex);

  /**
   * Called by the parser after chunk metadata has been loaded, allowing the factory to perform
   * post-metadata initialization (e.g. resolving deserializer caches) without the parser needing to
   * know about implementation details.
   *
   * @param context the current parser context for the chunk
   * @param header the chunk header
   */
  default void onChunkMetadata(ParserContext context, ChunkHeader header) {}
}
