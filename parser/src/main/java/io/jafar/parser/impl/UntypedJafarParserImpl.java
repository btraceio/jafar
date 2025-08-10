package io.jafar.parser.impl;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.StreamingChunkParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class UntypedJafarParserImpl implements UntypedJafarParser {
    private final class HandlerRegistrationImpl<T> implements HandlerRegistration<T> {
        private final Consumer<Map<String, Object>> handler;

        HandlerRegistrationImpl(Consumer<Map<String, Object>> handler) {
            this.handler = handler;
        }

        @Override
        public void destroy(JafarParser cookie) {
            assert cookie == UntypedJafarParserImpl.this;
            handlers.remove(handler);
        }
    }

    private final Path path;
    private final ParsingContext context;

    private final Set<Consumer<Map<String, Object>>> handlers = new HashSet<>();

    public UntypedJafarParserImpl(Path path, ParsingContext context) {
        this.path = path;
        this.context = context;
    }

    @Override
    public HandlerRegistration<?> handle(Consumer<Map<String, Object>> handler) {
        handlers.add(handler);
        return new HandlerRegistrationImpl<>(handler);
    }

    @Override
    public void run() throws IOException {
        try (StreamingChunkParser parser = new StreamingChunkParser(((ParsingContextImpl)context).untypedContextFactory())) {
            parser.parse(path, new EventStream() {
                @Override
                protected void onEventValue(Map<String, Object> value) {
                    handlers.forEach(h -> h.accept(value));
                }
            });
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
}
