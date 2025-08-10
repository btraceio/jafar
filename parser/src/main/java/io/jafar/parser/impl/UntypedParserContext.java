package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;

public class UntypedParserContext extends ParserContext {
    public UntypedParserContext(int chunkIndex) {
        super(chunkIndex);
    }

    @Override
    public void onMetadataReady() {
        // do nothing
    }

    @Override
    public void onConstantPoolsReady() {
        // do nothing
    }
}
