package io.jafar.parser.internal_api;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
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
      protected Object select(ParserContext context, long typeId, MetadataClass type) {
        return sink.select(context, typeId, type);
      }

      @Override
      protected void onEventValue(
          MetadataClass type, Map<String, Object> value, Control ctl, Object selection) {
        sink.onEventValue(type, value, ctl, selection);
      }

      @Override
      protected boolean onDecodeError(EventDecodingException failure) {
        return sink.onDecodeError(failure);
      }
    };
  }
}
