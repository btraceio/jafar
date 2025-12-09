package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.UntypedDeserializerCache;

/**
 * Factory for creating untyped parser contexts.
 *
 * <p>This class implements ParserContextFactory to create untyped parser contexts that are suitable
 * for parsing JFR recordings without type safety. The factory maintains a shared deserializer cache
 * across all contexts to enable reuse of generated deserializers.
 */
public final class UntypedParserContextFactory implements ParserContextFactory {
  /** The deserializer cache shared across all contexts. */
  private final UntypedDeserializerCache deserializerCache = new UntypedDeserializerCache.Impl();

  /**
   * Public constructor for UntypedParserContextFactory.
   *
   * <p>This factory creates untyped parser contexts for JFR parsing with a shared deserializer
   * cache.
   */
  public UntypedParserContextFactory() {}

  /**
   * Creates a new parser context for the specified chunk.
   *
   * <p>All contexts share the same deserializer cache to enable reuse of generated deserializers
   * across chunks with identical metadata.
   *
   * @param parent the parent parser context (may be null)
   * @param chunkIndex the index of the chunk to create a context for
   * @return a parser context suitable for parsing the specified chunk
   */
  @Override
  public ParserContext newContext(ParserContext parent, int chunkIndex) {
    if (parent == null) {
      return new UntypedParserContext(chunkIndex, deserializerCache);
    }
    // Share the deserializer cache from parent
    return new UntypedParserContext(
        chunkIndex, ((UntypedParserContext) parent).getDeserializerCache());
  }
}
