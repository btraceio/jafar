package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ParserContextFactory;

/**
 * Factory for creating untyped parser contexts.
 * <p>
 * This class implements ParserContextFactory to create untyped parser contexts
 * that are suitable for parsing JFR recordings without type safety.
 * </p>
 */
public final class UntypedParserContextFactory implements ParserContextFactory {
    /**
     * Public constructor for UntypedParserContextFactory.
     * <p>
     * This factory creates untyped parser contexts for JFR parsing.
     * </p>
     */
    public UntypedParserContextFactory() {}
    
    /**
     * Creates a new parser context for the specified chunk.
     * <p>
     * If a parent context is provided, it is reused; otherwise, a new
     * UntypedParserContext is created for the specified chunk index.
     * </p>
     * 
     * @param parent the parent parser context, or {@code null} to create a new one
     * @param chunkIndex the index of the chunk to create a context for
     * @return a parser context suitable for parsing the specified chunk
     */
    @Override
    public ParserContext newContext(ParserContext parent, int chunkIndex) {
        return parent != null ? parent : new UntypedParserContext(chunkIndex);
    }
}
