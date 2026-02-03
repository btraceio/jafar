package io.jafar.parser.internal_api;

import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import java.util.Map;

/**
 * Interface for generated untyped event deserializers.
 *
 * <p>Implementations of this interface are generated at runtime using bytecode generation (ASM) for
 * each JFR event type. The generated code directly reads from the {@link RecordingStream} and
 * constructs a {@link Map} representation without the overhead of ValueProcessor callbacks.
 *
 * <p>This is part of the Tier 3 optimization that eliminates GenericValueReader callback overhead
 * by generating specialized deserializers per event type.
 *
 * @see io.jafar.parser.internal_api.UntypedCodeGenerator
 */
@FunctionalInterface
@Internal
public interface UntypedEventDeserializer {
  /**
   * Deserializes an event from the recording stream into a Map representation.
   *
   * @param stream the recording stream positioned at the start of the event data
   * @param context the parser context (may be null if not needed)
   * @return a Map containing field names → values for the event
   */
  Map<String, Object> deserialize(RecordingStream stream, ParserContext context);

  /**
   * Skips over an event in the recording stream without deserializing it.
   *
   * <p>Default implementation deserializes and discards the result. Implementations may override
   * this to provide more efficient skipping.
   *
   * @param stream the recording stream positioned at the start of the event data
   */
  default void skip(RecordingStream stream) {
    // Default: deserialize and discard
    deserialize(stream, null);
  }
}
