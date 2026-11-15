package io.jafar.parser.internal_api;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.impl.EventStreamGenerated;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Map;

/** Provides {@link EventStreamGenerated} to the untyped parser in {@code parser-core}. */
public final class GeneratedUntypedEventStreamFactory implements UntypedEventStreamFactory {
  @Override
  public ChunkParserListener create(ChunkParserListener delegate, Sink sink) {
    return new EventStreamGenerated(delegate) {
      @Override
      protected void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl) {
        sink.onEventValue(type, value, ctl);
      }

      @Override
      protected boolean onDecodeError(EventDecodingException failure) {
        return sink.onDecodeError(failure);
      }
    };
  }
}
