package io.jafar.demo;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;

import java.nio.file.Paths;
import java.util.concurrent.atomic.LongAdder;

public final class GenericParser {
    public static void main(String[] args) throws Exception {
        ParsingContext parsingContext = ParsingContext.create();
        LongAdder counter = new LongAdder();
        try (UntypedJafarParser p = parsingContext.newUntypedParser(Paths.get(args[0]))) {
            HandlerRegistration<?> h = p.handle(v -> {
                counter.increment();
            });
            p.run();

            h.destroy(p);
        }
        long uptime = parsingContext.uptime();
        System.out.println("===> Checked " + counter.sum() + " events in " + uptime + "ns");
        System.out.println("===> Time to process one event: " + (uptime / counter.doubleValue()) + "ns");
    }
}
