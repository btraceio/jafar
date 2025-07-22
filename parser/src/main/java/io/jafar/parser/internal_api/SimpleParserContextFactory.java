package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.SimpleParserContext;

public final class SimpleParserContextFactory implements ParserContextFactory {
    @Override
    public ParserContext newContext(ParserContext parent, int chunkIndex) {
        return parent != null ? parent : new SimpleParserContext();
    }
}
