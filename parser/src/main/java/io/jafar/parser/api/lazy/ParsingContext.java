package io.jafar.parser.api.lazy;

import io.jafar.parser.impl.lazy.ParsingContextImpl;

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

