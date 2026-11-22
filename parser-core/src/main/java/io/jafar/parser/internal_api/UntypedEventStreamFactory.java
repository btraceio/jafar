package io.jafar.parser.internal_api;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Map;

/**
 * SPI for the strategy-aware, bytecode-generated untyped event reader.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}. {@code parser-core}
 * defines this interface and the generic reader; {@code parser-codegen} provides the generated one,
 * so the core never depends on the code generator. When no implementation is on the classpath the
 * untyped parser uses the generic reader.
 *
 * <p><b>Internal API.</b> Not part of the stable public API.
 */
public interface UntypedEventStreamFactory {

  /** Receives the events the created listener decodes; mirrors the generic reader's hooks. */
  interface Sink {
    /** An event was decoded. */
    void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl);

    /**
     * Decides, before an event is decoded, whether anything wants it. Called on the chunk's thread.
     *
     * @return {@code null} to skip the event without decoding it; anything else to decode it, and
     *     that object is handed back to {@link #onEventValue(MetadataClass, Map, Control, Object)}
     */
    default Object select(ParserContext context, long typeId, MetadataClass type) {
      return Boolean.TRUE;
    }

    /**
     * {@link #select(ParserContext, long, MetadataClass)} with the event's size and the parse's
     * control. Called with the chunk's {@code RecordingStream} at the start of the event's payload;
     * an implementation may read from it and must leave it at that position. The default calls
     * {@link #select(ParserContext, long, MetadataClass)}.
     *
     * @param size the event's size in bytes, header included
     */
    default Object select(
        ParserContext context, long typeId, MetadataClass type, long size, Control ctl) {
      return select(context, typeId, type);
    }

    /** An event was decoded; {@code selection} is what {@link #select} returned for it. */
    default void onEventValue(
        MetadataClass type, Map<String, Object> value, Control ctl, Object selection) {
      onEventValue(type, value, ctl);
    }

    /**
     * An event could not be decoded.
     *
     * @return {@code true} to skip it and continue, {@code false} to fail the parse
     */
    boolean onDecodeError(EventDecodingException failure);
  }

  /**
   * Creates the listener that reads events for the untyped parser. The deserializer strategy is
   * taken from the parser context ({@code UntypedStrategy}), as for the generic reader.
   *
   * @param delegate the listener to forward chunk and event callbacks to, may be {@code null}
   * @param sink where decoded events and decode errors go
   */
  ChunkParserListener create(ChunkParserListener delegate, Sink sink);
}
