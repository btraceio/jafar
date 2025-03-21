package io.jafar.demo;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParsingContext;

public class Printer {
    public static void main(String[] args) throws Exception {
        ParsingContext parsingContext = ParsingContext.create();
        try (JafarParser p = JafarParser.open(args[0], parsingContext)) {
            HandlerRegistration<JVMInfoEvent> h1 = p.handle(JVMInfoEvent.class, (event, ctl) -> {
                System.out.println("JVM Name: " + event.jvmName());
                System.out.println("JVM Version: " + event.jvmVersion());
            });

            p.run();
        }
    }
}
