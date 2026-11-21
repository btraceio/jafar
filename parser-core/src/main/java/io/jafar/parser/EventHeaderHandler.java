package io.jafar.parser;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Receives an event's header without the event being decoded. See {@link
 * UntypedJafarParser#handleHeaders(TypeFilter, EventHeaderHandler)}.
 */
@FunctionalInterface
public interface EventHeaderHandler {
  /**
   * The {@code startTime} passed for an event whose type has no {@code startTime} field of type
   * {@code long} (not an array, not a constant-pool reference).
   */
  long NO_START_TIME = Long.MIN_VALUE;

  /**
   * Handles one event's header.
   *
   * @param type the metadata class type of the event
   * @param size the event's size in bytes as the recording stores it, header included
   * @param startTime the event's {@code startTime}, the same value a decoded event carries under
   *     {@code "startTime"}: epoch nanoseconds for a {@code @Timestamp(TICKS)} field, which every
   *     JDK event has, otherwise the stored value; or {@link #NO_START_TIME} when the type has no
   *     {@code long startTime} field
   * @param ctl parser control object for flow control and metadata access
   */
  void onEvent(MetadataClass type, long size, long startTime, Control ctl);
}
