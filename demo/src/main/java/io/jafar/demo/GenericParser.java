package io.jafar.demo;

import io.jafar.parser.internal_api.SimpleParserContextFactory;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.api.stateful.EventStream;

import java.nio.file.Paths;
import java.util.Map;

public final class GenericParser {
    public static void main(String[] args) throws Exception {
        try (StreamingChunkParser parser = new StreamingChunkParser(new SimpleParserContextFactory())) {
            parser.parse(Paths.get(args[0]), new EventStream() {
                @Override
                protected void onEventValue(Map<String, Object> value) {
                    System.out.println("===> " + value);
                }
            });
        }
    }
}
