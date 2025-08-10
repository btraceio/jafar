package io.jafar.parser;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UntypedJafarParserTest {

    @Test
    void testUntypedParsingCountsEvents() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        ParsingContext ctx = ParsingContext.create();
        try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
            AtomicInteger count = new AtomicInteger();
            HandlerRegistration<?> reg = p.handle((Map<String, Object> evt) -> count.incrementAndGet());
            p.run();
            reg.destroy(p);
            assertTrue(count.get() > 0, "Expected at least one event");
        }
    }

    @Test
    void testUntypedHandlerExceptionStopsParsing() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        ParsingContext ctx = ParsingContext.create();
        try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
            p.handle((Map<String, Object> evt) -> { throw new RuntimeException("boom"); });
            assertThrows(java.io.IOException.class, p::run);
        }
    }
}


