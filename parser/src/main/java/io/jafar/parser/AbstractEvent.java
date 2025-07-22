package io.jafar.parser;

import io.jafar.parser.impl.lazy.LazyParserContext;
import io.jafar.parser.internal_api.RecordingStream;

public abstract class AbstractEvent {
    private final LazyParserContext context;

    protected AbstractEvent(RecordingStream stream) {
        this.context = stream.getContext();
    }

    public final LazyParserContext getContext() {
        return context;
    }
}
