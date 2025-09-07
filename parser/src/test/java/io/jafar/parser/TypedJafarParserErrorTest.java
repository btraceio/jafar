package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.parser.api.TypedJafarParser;
import org.junit.jupiter.api.Test;

public class TypedJafarParserErrorTest {

  interface NoAnno {}

  @Test
  void handleRejectsInvalidType() throws Exception {
    try (TypedJafarParser p = TypedJafarParser.open("/dev/null")) {
      assertThrows(RuntimeException.class, () -> p.handle(NoAnno.class, (e, c) -> {}));
    }
  }
}
