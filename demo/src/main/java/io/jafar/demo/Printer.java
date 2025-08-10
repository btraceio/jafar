package io.jafar.demo;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.TypedJafarParser;

import java.nio.file.Paths;

public class Printer {
    public static void main(String[] args) throws Exception {
        try (TypedJafarParser p = JafarParser.newTypedParser(Paths.get(args[0]))) {
            HandlerRegistration<JVMInfoEvent> h1 = p.handle(JVMInfoEvent.class, (event, ctl) -> {
                System.out.println("JVM Name: " + event.jvmName());
                System.out.println("JVM Version: " + event.jvmVersion());
            });

            p.run();
        }
    }
}
