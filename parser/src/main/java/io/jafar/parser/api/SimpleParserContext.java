package io.jafar.parser.api;

public class SimpleParserContext extends ParserContext {
    public SimpleParserContext(int chunkIndex) {
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
