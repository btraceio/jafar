package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;

public interface ParserContextFactory<T extends ParserContext> {
    default T newContext() {
        return newContext(null, 0);
    }
    T newContext(T parent, int chunkIndex);
}
