package io.jafar.parser.api;

import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ParsingContextImpl;

/**
 * Cross-recording context.
 * Implementation specific, but allows sharing computationally intensive resources
 * between parsing sessions,
 */
public interface ParsingContext {
    static ParsingContext create() {
        return new ParsingContextImpl();
    }
}

