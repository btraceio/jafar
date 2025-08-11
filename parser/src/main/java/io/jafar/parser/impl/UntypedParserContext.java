package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;

/**
 * Parser context for untyped JFR parsing.
 * <p>
 * This class extends ParserContext to provide a minimal implementation
 * for untyped parsing scenarios where metadata and constant pools
 * are not actively managed.
 * </p>
 */
public class UntypedParserContext extends ParserContext {
    /**
     * Constructs a new UntypedParserContext for the specified chunk.
     * 
     * @param chunkIndex the index of the chunk this context is for
     */
    public UntypedParserContext(int chunkIndex) {
        super(chunkIndex);
    }

    /**
     * Called when metadata is ready for processing.
     * <p>
     * This implementation does nothing as untyped parsing
     * doesn't require active metadata management.
     * </p>
     */
    @Override
    public void onMetadataReady() {
        // do nothing
    }

    /**
     * Called when constant pools are ready for processing.
     * <p>
     * This implementation does nothing as untyped parsing
     * doesn't require active constant pool management.
     * </p>
     */
    @Override
    public void onConstantPoolsReady() {
        // do nothing
    }
}
