package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.UntypedDeserializerCache;

/**
 * Parser context for untyped JFR parsing.
 *
 * <p>This class extends ParserContext to provide a minimal implementation for untyped parsing
 * scenarios where metadata and constant pools are not actively managed.
 */
public class UntypedParserContext extends ParserContext {
  /** Global cache for generated untyped deserializers. */
  private final UntypedDeserializerCache deserializerCache;

  /** Optimization strategy for event deserialization. */
  private final UntypedStrategy strategy;

  /**
   * Constructs a new UntypedParserContext for the specified chunk.
   *
   * @param chunkIndex the index of the chunk this context is for
   */
  public UntypedParserContext(int chunkIndex) {
    this(chunkIndex, new UntypedDeserializerCache.Impl(), UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Constructs a new UntypedParserContext with the specified deserializer cache.
   *
   * @param chunkIndex the index of the chunk this context is for
   * @param deserializerCache the deserializer cache to use
   */
  public UntypedParserContext(int chunkIndex, UntypedDeserializerCache deserializerCache) {
    this(chunkIndex, deserializerCache, UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Constructs a new UntypedParserContext with the specified deserializer cache and strategy.
   *
   * @param chunkIndex the index of the chunk this context is for
   * @param deserializerCache the deserializer cache to use
   * @param strategy the optimization strategy for event deserialization
   */
  public UntypedParserContext(
      int chunkIndex, UntypedDeserializerCache deserializerCache, UntypedStrategy strategy) {
    super(chunkIndex);
    this.deserializerCache =
        deserializerCache != null ? deserializerCache : new UntypedDeserializerCache.Impl();
    this.strategy = strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;
    this.put(UntypedDeserializerCache.class, this.deserializerCache);
    this.put(UntypedStrategy.class, this.strategy);
  }

  /**
   * Gets the deserializer cache for this context.
   *
   * @return the deserializer cache
   */
  public UntypedDeserializerCache getDeserializerCache() {
    return deserializerCache;
  }

  /**
   * Gets the optimization strategy for this context.
   *
   * @return the optimization strategy
   */
  public UntypedStrategy getStrategy() {
    return strategy;
  }

  /**
   * Called when constant pools are ready for processing.
   *
   * <p>This implementation does nothing as untyped parsing doesn't require active constant pool
   * management.
   */
  @Override
  public void onConstantPoolsReady() {
    // do nothing
  }
}
