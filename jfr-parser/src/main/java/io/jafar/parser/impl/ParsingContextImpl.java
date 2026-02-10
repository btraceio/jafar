package io.jafar.parser.impl;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ParserContextFactory;
import java.nio.file.Path;

/**
 * Implementation of ParsingContext that provides factory methods for creating parsers.
 *
 * <p>This class manages typed and untyped parser context factories and tracks the uptime of parsing
 * operations.
 */
public final class ParsingContextImpl implements ParsingContext {
  /** An empty parsing context instance. */
  public static final ParsingContext EMPTY = new ParsingContextImpl();

  /** Factory for creating typed parser contexts. */
  private final TypedParserContextFactory typedFactory = new TypedParserContextFactory();

  /** The start timestamp for tracking uptime. */
  private final long startTs = System.nanoTime();

  /** Constructs a new ParsingContextImpl. */
  public ParsingContextImpl() {}

  /**
   * Gets the typed context factory.
   *
   * @return the typed context factory
   */
  public ParserContextFactory typedContextFactory() {
    return typedFactory;
  }

  /**
   * Gets the untyped context factory with default SPARSE_ACCESS strategy.
   *
   * @return the untyped context factory
   */
  public ParserContextFactory untypedContextFactory() {
    return untypedContextFactory(UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Gets the untyped context factory with specified strategy.
   *
   * @param strategy the optimization strategy for event deserialization
   * @return the untyped context factory
   */
  public ParserContextFactory untypedContextFactory(UntypedStrategy strategy) {
    return new UntypedParserContextFactory(strategy);
  }

  @Override
  public TypedJafarParser newTypedParser(Path path) {
    return TypedJafarParser.open(path, this);
  }

  @Override
  public UntypedJafarParser newUntypedParser(Path path) {
    return newUntypedParser(path, UntypedStrategy.SPARSE_ACCESS);
  }

  @Override
  public UntypedJafarParser newUntypedParser(Path path, UntypedStrategy strategy) {
    return UntypedJafarParser.open(path, this, strategy);
  }

  @Override
  public long uptime() {
    return System.nanoTime() - startTs;
  }
}
