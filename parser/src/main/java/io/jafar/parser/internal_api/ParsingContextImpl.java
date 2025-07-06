package io.jafar.parser.internal_api;

import io.jafar.parser.api.ParsingContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ParsingContextImpl implements ParsingContext {
    public static final ParsingContext EMPTY = new ParsingContextImpl();

    private DeserializerCache deserializerCache = new DeserializerCache.Impl();

    public RecordingParserContext newRecordingParserContext() {
        return new RecordingParserContext(deserializerCache);
    }
}
