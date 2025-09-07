package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ParserContextFactory;

/**
 * Factory for creating untyped parser contexts.
 *
 * <p>This class implements ParserContextFactory to create untyped parser contexts that are suitable
 * for parsing JFR recordings without type safety.
 */
public final class UntypedParserContextFactory implements ParserContextFactory {
  /**
   * Public constructor for UntypedParserContextFactory.
   *
   * <p>This factory creates untyped parser contexts for JFR parsing.
   */
  public UntypedParserContextFactory() {}

  /**
   * Creates a new parser context for the specified chunk.
   *
   * <p>The parent context is ignored. The untyped parser will always create a fresh context.
   *
   * @param parent the parent parser context (ignored)
   * @param chunkIndex the index of the chunk to create a context for
   * @return a parser context suitable for parsing the specified chunk
   */
  @Override
  public ParserContext newContext(ParserContext parent, int chunkIndex) {
    return new UntypedParserContext(chunkIndex);
  }
}
