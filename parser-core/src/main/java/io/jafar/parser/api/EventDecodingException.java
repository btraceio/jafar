package io.jafar.parser.api;

/**
 * An event whose payload the untyped parser could not decode.
 *
 * <p>By default it fails the parse: {@link UntypedJafarParser#run()} throws an {@code IOException}
 * whose cause chain contains this exception. With {@link
 * UntypedJafarParser#onDecodeError(UntypedJafarParser.DecodeErrorHandler)} the caller can instead
 * skip the event and continue. Either way the event is never delivered to a handler, not even
 * partially.
 *
 * <p>The message names the event type, chunk and position; {@link #getCause()} is the decoder's
 * reason. This exception records no stack trace of its own: in skip mode one is created per bad
 * event, and the cause's stack trace already covers the frames where decoding failed.
 */
public final class EventDecodingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String typeName;
  private final long typeId;
  private final int chunkIndex;
  private final long position;
  private final long size;

  /**
   * Creates the exception.
   *
   * @param typeName the event type name, or {@code null} if the chunk metadata does not declare
   *     {@code typeId}
   * @param typeId the event type id, as used in the chunk
   * @param chunkIndex the chunk index, as reported by {@link ParserContext#getChunkIndex()}
   * @param position the event's start position within the chunk
   * @param size the event's size in bytes, including its size and type header
   * @param cause the decoder's exception
   */
  public EventDecodingException(
      String typeName, long typeId, int chunkIndex, long position, long size, Throwable cause) {
    super(
        "Cannot decode event of type "
            + (typeName != null ? typeName : "<unknown>")
            + " (id "
            + typeId
            + ") in chunk "
            + chunkIndex
            + " at position "
            + position
            + " (size "
            + size
            + ")",
        cause,
        false,
        false);
    this.typeName = typeName;
    this.typeId = typeId;
    this.chunkIndex = chunkIndex;
    this.position = position;
    this.size = size;
  }

  /**
   * @return the event type name, or {@code null} if the chunk metadata does not declare the id
   */
  public String getTypeName() {
    return typeName;
  }

  /**
   * @return the event type id, as used in the chunk
   */
  public long getTypeId() {
    return typeId;
  }

  /**
   * @return the chunk index, as reported by {@link ParserContext#getChunkIndex()}
   */
  public int getChunkIndex() {
    return chunkIndex;
  }

  /**
   * @return the event's start position within the chunk
   */
  public long getPosition() {
    return position;
  }

  /**
   * @return the event's size in bytes, including its size and type header
   */
  public long getSize() {
    return size;
  }
}
