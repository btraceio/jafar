package io.jafar.demo;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.nio.file.Paths;
import java.util.concurrent.atomic.LongAdder;

public final class GenericParser {
    public static void main(String[] args) throws Exception {
        ParsingContext parsingContext = ParsingContext.create();
        LongAdder counter = new LongAdder();
        try (UntypedJafarParser p = parsingContext.newUntypedParser(Paths.get(args[0]))) {
            HandlerRegistration<?> h = p.handle((t, v) -> {
                counter.increment();
            });
            p.withParserListener(new ChunkParserListener() {
                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    System.out.println("===> got metadata");
                    return ChunkParserListener.super.onMetadata(context, metadata);
                }
            }).run();

            h.destroy(p);
        }
        long uptime = parsingContext.uptime();
        System.out.println("===> Checked " + counter.sum() + " events in " + uptime + "ns");
        System.out.println("===> Time to process one event: " + (uptime / counter.doubleValue()) + "ns");
    }
}
