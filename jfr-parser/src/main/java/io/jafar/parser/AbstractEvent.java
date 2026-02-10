package io.jafar.parser;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.RecordingStream;

/**
 * Abstract base class for JFR events.
 *
 * <p>This class provides common functionality for all JFR events, including access to the parser
 * context.
 */
public abstract class AbstractEvent {
  private final ParserContext context;

  /**
   * Constructs a new AbstractEvent with the given recording stream.
   *
   * @param stream the recording stream from which this event was parsed
   */
  protected AbstractEvent(RecordingStream stream) {
    this.context = stream.getContext();
  }

  /**
   * Gets the parser context associated with this event.
   *
   * @return the parser context for this event
   */
  public final ParserContext getContext() {
    return context;
  }
}
