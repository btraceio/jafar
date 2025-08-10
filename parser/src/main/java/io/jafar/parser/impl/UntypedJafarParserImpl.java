package io.jafar.parser.impl;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class UntypedJafarParserImpl implements UntypedJafarParser {
    private final class HandlerRegistrationImpl<T> implements HandlerRegistration<T> {
        private final EventHandler handler;

        HandlerRegistrationImpl(EventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void destroy(JafarParser cookie) {
            assert cookie == UntypedJafarParserImpl.this;
            handlers.remove(handler);
        }
    }

    private final ChunkParserListener parserListener;
    private final Path path;
    private final ParsingContext context;

    private final Set<EventHandler> handlers;

    public UntypedJafarParserImpl(Path path, ParsingContext context) {
        this.path = path;
        this.context = context;
        this.handlers = new HashSet<>();
        this.parserListener = null;
    }

    private UntypedJafarParserImpl(UntypedJafarParserImpl other, ChunkParserListener listener) {
        this.path = other.path;
        this.context = other.context;

        this.handlers = new HashSet<>(other.handlers);
        this.parserListener = listener;
    }

    @Override
    public HandlerRegistration<?> handle(EventHandler handler) {
        handlers.add(handler);
        return new HandlerRegistrationImpl<>(handler);
    }

    @Override
    public void run() throws IOException {
        try (StreamingChunkParser parser = new StreamingChunkParser(((ParsingContextImpl)context).untypedContextFactory())) {
            ChunkParserListener listener = new EventStream(parserListener) {
                @Override
                protected void onEventValue(MetadataClass type, Map<String, Object> value) {
                    handlers.forEach(h -> h.handle(type, value));
                }
            };
            parser.parse(path, listener);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        handlers.clear();
    }

    @SuppressWarnings("unchecked")
    @Override
    public UntypedJafarParserImpl withParserListener(ChunkParserListener listener) {
        return new UntypedJafarParserImpl(this, listener);
    }
}
