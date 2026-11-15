package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.UntypedDeserializerCache;

/**
 * Factory for creating untyped parser contexts.
 *
 * <p>This class implements ParserContextFactory to create untyped parser contexts that are suitable
 * for parsing JFR recordings without type safety. Every context a factory creates carries the same
 * {@link UntypedDeserializerCache}, so the chunks of a parse reuse each other's generated
 * deserializers.
 *
 * <p>How far that cache reaches depends on where the factory came from. A factory constructed
 * directly owns a new cache: it is shared by the parses run with that factory instance and by
 * nothing else. A factory from {@code ParsingContextImpl.untypedContextFactory} carries the cache
 * of its {@code ParsingContext}, shared by every untyped parse made from that context.
 */
public final class UntypedParserContextFactory implements ParserContextFactory {
  /** The deserializer cache shared across all contexts this factory creates. */
  private final UntypedDeserializerCache deserializerCache;

  /** The optimization strategy for event deserialization. */
  private final UntypedStrategy strategy;

  /**
   * Public constructor for UntypedParserContextFactory with default SPARSE_ACCESS strategy.
   *
   * <p>This factory creates untyped parser contexts for JFR parsing with a deserializer cache of
   * its own.
   */
  public UntypedParserContextFactory() {
    this(UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Public constructor for UntypedParserContextFactory with specified strategy.
   *
   * <p>This factory creates untyped parser contexts for JFR parsing with a deserializer cache of
   * its own and specified optimization strategy.
   *
   * @param strategy the optimization strategy for event deserialization
   */
  public UntypedParserContextFactory(UntypedStrategy strategy) {
    this(strategy, new UntypedDeserializerCache.Impl());
  }

  /**
   * A factory whose contexts carry {@code deserializerCache}, which the caller may share with other
   * factories. Safe for any mix of recordings and strategies: the cache is keyed by {@code
   * UntypedCodeGenerator.cacheKey}, which covers the strategy and everything the generated code
   * depends on.
   *
   * @param strategy the optimization strategy for event deserialization
   * @param deserializerCache the cache to hand to every context
   */
  UntypedParserContextFactory(
      UntypedStrategy strategy, UntypedDeserializerCache deserializerCache) {
    this.strategy = strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;
    this.deserializerCache = deserializerCache;
  }

  /**
   * Creates a new parser context for the specified chunk.
   *
   * <p>All contexts share the same deserializer cache and strategy to enable reuse of generated
   * deserializers across chunks with identical metadata.
   *
   * @param parent the parent parser context (may be null)
   * @param chunkIndex the index of the chunk to create a context for
   * @return a parser context suitable for parsing the specified chunk
   */
  @Override
  public ParserContext newContext(ParserContext parent, int chunkIndex) {
    if (parent == null) {
      return new UntypedParserContext(chunkIndex, deserializerCache, strategy);
    }
    // Share the deserializer cache and strategy from parent
    UntypedParserContext parentCtx = (UntypedParserContext) parent;
    return new UntypedParserContext(
        chunkIndex, parentCtx.getDeserializerCache(), parentCtx.getStrategy());
  }
}
