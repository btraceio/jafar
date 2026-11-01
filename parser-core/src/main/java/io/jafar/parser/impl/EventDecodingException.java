package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Thrown when the generic untyped value path ({@link EventStream}, {@link EventStreamLazy}, {@link
 * EventStreamBaseline}) cannot decode an event's payload. The message names the event type, chunk
 * and position; the cause carries the decoder's reason.
 *
 * <p>The parse fails: the event is not delivered, not even partially, and the chunk parser does not
 * silently move on. {@code UntypedJafarParser.run()} reports it as the cause chain of its {@code
 * IOException}.
 */
public final class EventDecodingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  EventDecodingException(String message, Throwable cause) {
    super(message, cause);
  }

  static EventDecodingException of(
      ParserContext context,
      long typeId,
      MetadataClass type,
      long eventStartPos,
      long rawSize,
      Throwable cause) {
    return new EventDecodingException(
        "Cannot decode event of type "
            + (type != null ? type.getName() : "<unknown>")
            + " (id "
            + typeId
            + ") in chunk "
            + context.getChunkIndex()
            + " at position "
            + eventStartPos
            + " (size "
            + rawSize
            + ")",
        cause);
  }
}
