package io.jafar.shell.jfrpath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JfrPathParserTest {

    @Test
    void parsesRootSegmentsAndSimpleFilter() {
        var q = JfrPathParser.parse("events/jdk.ExecutionSample[thread/name~\"main\"]");
        assertEquals(JfrPath.Root.EVENTS, q.root);
        assertEquals(1, q.segments.size());
        assertEquals("jdk.ExecutionSample", q.segments.get(0));
        assertEquals(1, q.predicates.size());
        var p = (JfrPath.FieldPredicate) q.predicates.get(0);
        assertEquals(JfrPath.Op.REGEX, p.op);
        assertEquals("main", p.literal);
        assertEquals(2, p.fieldPath.size());
        assertEquals("thread", p.fieldPath.get(0));
        assertEquals("name", p.fieldPath.get(1));
    }

    @Test
    void parsesNumericComparison() {
        var q = JfrPathParser.parse("events/jdk.FileRead[bytes>1000]");
        var p = (JfrPath.FieldPredicate) q.predicates.get(0);
        assertEquals(JfrPath.Op.GT, p.op);
        assertTrue(p.literal instanceof Number);
    }
}

