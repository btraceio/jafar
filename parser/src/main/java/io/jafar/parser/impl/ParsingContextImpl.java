package io.jafar.parser.impl;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ParserContextFactory;

import java.nio.file.Path;

public final class ParsingContextImpl implements ParsingContext {
    public static final ParsingContext EMPTY = new ParsingContextImpl();

    private final TypedParserContextFactory typedFactory = new TypedParserContextFactory();
    private final UntypedParserContextFactory untypedFactory = new UntypedParserContextFactory();

    private final long startTs = System.nanoTime();

    public ParsingContextImpl() {}

    public ParserContextFactory typedContextFactory() {
        return typedFactory;
    }

    public ParserContextFactory untypedContextFactory() {
        return untypedFactory;
    }

    @Override
    public TypedJafarParser newTypedParser(Path path) {
        return TypedJafarParser.open(path, this);
    }

    @Override
    public UntypedJafarParser newUntypedParser(Path path) {
        return UntypedJafarParser.open(path, this);
    }

    @Override
    public long uptime() {
        return System.nanoTime() - startTs;
    }
}
