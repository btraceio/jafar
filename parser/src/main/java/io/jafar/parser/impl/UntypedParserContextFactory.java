package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ParserContextFactory;

public final class UntypedParserContextFactory implements ParserContextFactory {
    @Override
    public ParserContext newContext(ParserContext parent, int chunkIndex) {
        return parent != null ? parent : new UntypedParserContext(chunkIndex);
    }
}
