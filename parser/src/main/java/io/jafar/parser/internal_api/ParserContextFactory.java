package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;

public interface ParserContextFactory {
    default ParserContext newContext() {
        return newContext(null, 0);
    }
    ParserContext newContext(ParserContext parent, int chunkIndex);
}
