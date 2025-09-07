package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.TypedParserContext;
import org.junit.jupiter.api.Test;

public class ParserContextTest {

  @Test
  void softBagStoresAndClearsValues() {
    ParserContext ctx = new TypedParserContext();
    Object value = new Object();
    ctx.put(Object.class, value);
    assertSame(value, ctx.get(Object.class));

    assertSame(value, ctx.remove(Object.class));
    assertNull(ctx.get(Object.class));
  }

  @Test
  void namedBagNullSafety() {
    ParserContext ctx = new TypedParserContext();
    ctx.put("key", Object.class, new Object());
    assertNotNull(ctx.get("key", Object.class));
    assertNotNull(ctx.remove("key", Object.class));

    // Now null-safe when key is absent
    assertNull(ctx.get("missing", Object.class));
    assertNull(ctx.remove("missing", Object.class));
  }
}
