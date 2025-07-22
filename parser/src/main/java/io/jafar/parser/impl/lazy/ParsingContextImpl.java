package io.jafar.parser.impl.lazy;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.lazy.ParsingContext;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.ParserContextFactory;

public final class ParsingContextImpl implements ParsingContext {
    public static final ParsingContext EMPTY = new ParsingContextImpl();

    private final LazyParserContextFactory factory = new LazyParserContextFactory();

    public ParserContextFactory contextFactory() {
        return factory;
    }
}
