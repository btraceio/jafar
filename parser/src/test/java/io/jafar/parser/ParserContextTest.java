package io.jafar.parser;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.TypedParserContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

        // Documented to possibly NPE when key is absent
        assertThrows(NullPointerException.class, () -> ctx.get("missing", Object.class));
        assertThrows(NullPointerException.class, () -> ctx.remove("missing", Object.class));
    }
}


