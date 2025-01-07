package io.jafar.parser;

import io.jafar.parser.internal_api.RecordingParserContext;
import io.jafar.parser.internal_api.RecordingStream;

public abstract class AbstractEvent {
    private final RecordingParserContext context;

    protected AbstractEvent(RecordingStream stream) {
        this.context = stream.getContext();
    }

    public final RecordingParserContext getContext() {
        return context;
    }
}
