package io.jafar.parser.impl;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.DeserializerFactory;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.UntypedEventStreamFactory;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Implementation of ParsingContext that provides factory methods for creating parsers.
 *
 * <p>This class manages typed and untyped parser context factories and tracks the uptime of parsing
 * operations.
 *
 * <p>What parses made from one context share: typed parses share the {@link
 * TypedParserContextFactory}; untyped parses share one {@link UntypedDeserializerCache}, so a
 * deserializer generated for an event type is reused by every later parse that meets a type with
 * the same structural key. {@link #withParallelism} views share both. {@link #EMPTY} shares neither
 * between untyped parses.
 */
public final class ParsingContextImpl implements ParsingContext {
  /**
   * Most entries {@link #untypedDeserializers} may hold when a parse starts; over it, the cache is
   * emptied first. Keys embed chunk-scoped type ids, so a context that keeps reading recordings of
   * different producers keeps meeting new keys, and each entry pins a generated class. A recording
   * has one entry per event type it delivers and strategy (78 for {@code tck-test.jfr}), so the
   * limit only matters to a context that has seen dozens of distinct metadata layouts.
   */
  static final int MAX_UNTYPED_DESERIALIZERS = 4096;

  /**
   * Generated untyped deserializers of every parse made from this context, for all strategies (the
   * strategy is part of the key); {@code null} for {@link #EMPTY}.
   */
  private final UntypedDeserializerCache untypedDeserializers;

  /** Factory for creating typed parser contexts. */
  private final TypedParserContextFactory typedFactory;

  /** The start timestamp for tracking uptime. */
  private final long startTs;

  /** Requested chunk parsing threads; 0 selects the default. */
  private final int parallelism;

  // DeserializerFactory is classpath-static: discovered once and reused across all contexts.
  private static final DeserializerFactory DESERIALIZER_FACTORY =
      discoverDeserializerFactory(threadContextLoader());

  /** Loads the first {@link DeserializerFactory} on this class's thread-context class loader. */
  private static ClassLoader threadContextLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return cl != null ? cl : ParsingContextImpl.class.getClassLoader();
  }

  /**
   * Loads the first {@link DeserializerFactory} on the given classpath, or {@code null} when there
   * is none — or when the one that is installed cannot be used. A broken codegen jar must not take
   * every parse down with an {@link ExceptionInInitializerError}; degrading to the generic path is
   * exactly what an absent codegen jar gets, and the warning says which case this is.
   */
  static DeserializerFactory discoverDeserializerFactory(ClassLoader loader) {
    try {
      Iterator<DeserializerFactory> it =
          ServiceLoader.load(DeserializerFactory.class, loader).iterator();
      return it.hasNext() ? it.next() : null;
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      org.slf4j.LoggerFactory.getLogger(ParsingContextImpl.class)
          .warn(
              "Ignoring the installed DeserializerFactory provider; typed parsing falls back to "
                  + "the generic reader: {}",
              describe(t));
      return null;
    }
  }

  /** Loading is pointless to continue past these, and swallowing them is never right. */
  static boolean isFatal(Throwable t) {
    return t instanceof VirtualMachineError || t instanceof ThreadDeath;
  }

  static String describe(Throwable t) {
    String message = t.getMessage();
    String s =
        t.getClass().getSimpleName()
            + (message == null ? "" : ": " + message.replaceAll("\\s+", " ").trim());
    Throwable cause = t.getCause();
    if (cause != null && cause != t) {
      s +=
          " (caused by "
              + cause.getClass().getSimpleName()
              + (cause.getMessage() == null
                  ? ""
                  : ": " + cause.getMessage().replaceAll("\\s+", " ").trim())
              + ")";
    }
    return s.length() > 300 ? s.substring(0, 297) + "..." : s;
  }

  // Like DeserializerFactory: classpath-static. Absent when parser-codegen is not on the classpath.
  private static final UntypedEventStreamFactory UNTYPED_STREAM_FACTORY =
      discoverUntypedStreamFactory();

  private static UntypedEventStreamFactory discoverUntypedStreamFactory() {
    try {
      Iterator<UntypedEventStreamFactory> it =
          ServiceLoader.load(UntypedEventStreamFactory.class).iterator();
      return it.hasNext() ? it.next() : null;
    } catch (Throwable t) {
      if (isFatal(t)) {
        throw (Error) t;
      }
      // A provider that is on the classpath but cannot be loaded (a broken or mismatched
      // parser-codegen jar) must not take every parser down with an ExceptionInInitializerError,
      // and must not silently degrade either: say so, then read with the generic reader.
      org.slf4j.LoggerFactory.getLogger(ParsingContextImpl.class)
          .warn("Generated untyped reader unavailable, using the generic reader: {}", describe(t));
      return null;
    }
  }

  /**
   * The context of parsers opened without one ({@code UntypedJafarParser.open(path)}). It lives as
   * long as the JVM, so it keeps no untyped deserializers: each of its parses has its own cache.
   *
   * <p>Declared after {@link #DESERIALIZER_FACTORY} on purpose: static initializers run in textual
   * order, and declared above it this context's typed factory was built with {@code null} and a
   * typed parse through it handed every handler a {@code null} event.
   */
  public static final ParsingContext EMPTY = new ParsingContextImpl(null);

  /**
   * The generated untyped reader's factory.
   *
   * @return the factory, or {@code null} when {@code parser-codegen} is not on the classpath
   */
  static UntypedEventStreamFactory untypedStreamFactory() {
    return UNTYPED_STREAM_FACTORY;
  }

  /** Constructs a new ParsingContextImpl. */
  public ParsingContextImpl() {
    this(new UntypedDeserializerCache.Impl());
  }

  private ParsingContextImpl(UntypedDeserializerCache untypedDeserializers) {
    this.untypedDeserializers = untypedDeserializers;
    this.typedFactory = new TypedParserContextFactory(DESERIALIZER_FACTORY);
    this.startTs = System.nanoTime();
    this.parallelism = 0;
  }

  /** A view of {@code other} that shares its caches and clock, with its own parallelism. */
  private ParsingContextImpl(ParsingContextImpl other, int parallelism) {
    this.typedFactory = other.typedFactory;
    this.untypedDeserializers = other.untypedDeserializers;
    this.startTs = other.startTs;
    this.parallelism = parallelism;
  }

  @Override
  public ParsingContext withParallelism(int parallelism) {
    if (parallelism < 1) {
      throw new IllegalArgumentException("parallelism must be at least 1: " + parallelism);
    }
    return new ParsingContextImpl(this, parallelism);
  }

  @Override
  public int parallelism() {
    return parallelism;
  }

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
   * <p>Every factory this context returns hands its chunk contexts the context's one {@link
   * UntypedDeserializerCache}, whatever the strategy. The exception is {@link #EMPTY}, whose
   * factories each own a new cache.
   *
   * @param strategy the optimization strategy for event deserialization
   * @return the untyped context factory
   */
  public ParserContextFactory untypedContextFactory(UntypedStrategy strategy) {
    if (untypedDeserializers == null) {
      return new UntypedParserContextFactory(strategy);
    }
    if (untypedDeserializers.size() > MAX_UNTYPED_DESERIALIZERS) {
      // Safe while other parses run: a chunk keeps the deserializers it already resolved, and a
      // key that is asked for again is generated again.
      untypedDeserializers.clear();
    }
    return new UntypedParserContextFactory(strategy, untypedDeserializers);
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
