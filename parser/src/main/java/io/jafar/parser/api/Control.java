package io.jafar.parser.api;

import io.jafar.parser.internal_api.RecordingParserContext;

public interface Control {
    interface Stream {
        long position();
    }

    Stream stream();
}
