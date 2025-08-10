package io.jafar.parser;

import io.jafar.parser.api.TypedJafarParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class TypedJafarParserErrorTest {

    interface NoAnno {}

    @Test
    void handleRejectsInvalidType() throws Exception {
        try (TypedJafarParser p = TypedJafarParser.open("/dev/null")) {
            assertThrows(RuntimeException.class, () -> p.handle(NoAnno.class, (e, c) -> {}));
        }
    }
}


