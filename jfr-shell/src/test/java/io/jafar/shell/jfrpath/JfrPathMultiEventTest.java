package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for multi-event type parsing with pipe-delimited syntax: events/(type1|type2|...) */
class JfrPathMultiEventTest {

  @Test
  void parsesMultiEventTypeSyntax() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals("jdk.FileRead", q.eventTypes.get(0));
    assertEquals("jdk.FileWrite", q.eventTypes.get(1));
  }

  @Test
  void parsesMultiEventWithWhitespace() {
    var q = JfrPathParser.parse("events/( jdk.FileRead | jdk.FileWrite )");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals("jdk.FileRead", q.eventTypes.get(0));
    assertEquals("jdk.FileWrite", q.eventTypes.get(1));
  }

  @Test
  void parsesThreeEventTypes() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite|jdk.SocketRead)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(3, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals("jdk.FileRead", q.eventTypes.get(0));
    assertEquals("jdk.FileWrite", q.eventTypes.get(1));
    assertEquals("jdk.SocketRead", q.eventTypes.get(2));
  }

  @Test
  void parsesMultiEventWithFilters() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)[bytes>1000]");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(1, q.predicates.size());
    var pred = q.predicates.get(0);
    if (pred instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.GT, p.op);
      assertEquals(1000L, p.literal);
      assertEquals(1, p.fieldPath.size());
      assertEquals("bytes", p.fieldPath.get(0));
    } else if (pred instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.GT, ce.op);
      assertEquals(1000L, ce.literal);
    } else {
      fail("Unexpected predicate type: " + pred.getClass());
    }
  }

  @Test
  void parsesMultiEventWithAggregation() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | sum(bytes)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(1, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.SumOp);
  }

  @Test
  void parsesMultiEventWithCount() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | count()");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(1, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.CountOp);
  }

  @Test
  void parsesMultiEventWithGroupBy() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | groupBy(path)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(1, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.GroupByOp);
  }

  @Test
  void parsesSingleTypeInParentheses() {
    var q = JfrPathParser.parse("events/(jdk.FileRead)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.eventTypes.size());
    assertFalse(q.isMultiType); // Single type, so not multi-type
    assertEquals("jdk.FileRead", q.eventTypes.get(0));
  }

  @Test
  void parsesMultiEventWithSelect() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | select(path, bytes)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(1, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) q.pipeline.get(0);
    assertEquals(2, selectOp.fieldPaths.size());
  }

  @Test
  void throwsOnEmptyTypeList() {
    var ex = assertThrows(IllegalArgumentException.class, () -> JfrPathParser.parse("events/()"));
    assertTrue(ex.getMessage().contains("Empty event type list"));
  }

  @Test
  void throwsOnTrailingPipe() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> JfrPathParser.parse("events/(jdk.FileRead|)"));
    assertTrue(ex.getMessage().contains("Empty event type after pipe separator"));
  }

  @Test
  void throwsOnConsecutivePipes() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> JfrPathParser.parse("events/(jdk.FileRead||jdk.FileWrite)"));
    assertTrue(ex.getMessage().contains("Consecutive pipe separators not allowed"));
  }

  @Test
  void throwsOnUnclosedParenthesis() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite"));
    assertTrue(ex.getMessage().contains("Expected ')'"));
  }

  @Test
  void throwsOnLeadingPipe() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> JfrPathParser.parse("events/(|jdk.FileRead)"));
    assertTrue(
        ex.getMessage().contains("Empty event type")
            || ex.getMessage().contains("Expected event type name"));
  }

  @Test
  void parsesMultiEventForMetadata() {
    var q = JfrPathParser.parse("metadata/(jdk.FileRead|jdk.FileWrite)");
    assertEquals(JfrPath.Root.METADATA, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
  }

  @Test
  void parsesMultiEventForCP() {
    var q = JfrPathParser.parse("cp/(Thread|Class)");
    assertEquals(JfrPath.Root.CP, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
  }

  @Test
  void parsesMultiEventWithComplexFiltersAndPipeline() {
    var q =
        JfrPathParser.parse(
            "events/(jdk.FileRead|jdk.FileWrite)[bytes>1000][path~\"/tmp\"] | groupBy(path, agg=sum, value=bytes) | top(10)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(2, q.eventTypes.size());
    assertTrue(q.isMultiType);
    assertEquals(2, q.predicates.size());
    assertEquals(2, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.GroupByOp);
    assertTrue(q.pipeline.get(1) instanceof JfrPath.TopOp);
  }

  @Test
  void backsCompatiblityWithSegments() {
    // Verify backward compatibility: segments field should contain first type
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)");
    assertEquals(1, q.segments.size());
    assertEquals("jdk.FileRead", q.segments.get(0));
  }

  @Test
  void toStringRepresentsMultiType() {
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | count()");
    String str = q.toString();
    assertTrue(str.contains("(jdk.FileRead|jdk.FileWrite)"));
  }
}
