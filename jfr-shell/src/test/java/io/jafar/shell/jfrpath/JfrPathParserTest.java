package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JfrPathParserTest {

  @Test
  void parsesRootSegmentsAndSimpleFilter() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample[thread/name~\"main\"]");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.segments.size());
    assertEquals("jdk.ExecutionSample", q.segments.get(0));
    assertEquals(1, q.predicates.size());
    var pred = q.predicates.get(0);
    if (pred instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.REGEX, p.op);
      assertEquals("main", p.literal);
      assertEquals(2, p.fieldPath.size());
      assertEquals("thread", p.fieldPath.get(0));
      assertEquals("name", p.fieldPath.get(1));
    } else if (pred instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.REGEX, ce.op);
      assertEquals("main", ce.literal);
      assertTrue(ce.lhs instanceof JfrPath.PathRef);
      var pr = (JfrPath.PathRef) ce.lhs;
      assertEquals(java.util.List.of("thread", "name"), pr.path);
    } else {
      fail("Unexpected predicate type: " + pred.getClass());
    }
  }

  @Test
  void parsesNumericComparison() {
    var q = JfrPathParser.parse("events/jdk.FileRead[bytes>1000]");
    var pred2 = q.predicates.get(0);
    if (pred2 instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.GT, p.op);
      assertTrue(p.literal instanceof Number);
    } else if (pred2 instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.GT, ce.op);
      assertTrue(ce.literal instanceof Number);
      assertTrue(ce.lhs instanceof JfrPath.PathRef);
    } else {
      fail("Unexpected predicate type: " + pred2.getClass());
    }
  }

  @Test
  void parsesRegexFilterWithEqualsTilde() {
    var q = JfrPathParser.parse("events/jdk.GarbageCollection[name=~\".*Young.*\"]");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.segments.size());
    assertEquals("jdk.GarbageCollection", q.segments.get(0));
    assertEquals(1, q.predicates.size());
    var pred = q.predicates.get(0);
    if (pred instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.REGEX, p.op);
      assertEquals(".*Young.*", p.literal);
      assertEquals(1, p.fieldPath.size());
      assertEquals("name", p.fieldPath.get(0));
    } else if (pred instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.REGEX, ce.op);
      assertEquals(".*Young.*", ce.literal);
      assertTrue(ce.lhs instanceof JfrPath.PathRef);
      var pr = (JfrPath.PathRef) ce.lhs;
      assertEquals(java.util.List.of("name"), pr.path);
    } else {
      fail("Unexpected predicate type: " + pred.getClass());
    }
  }

  @Test
  void parsesMultipleChainedFilters() {
    var q =
        JfrPathParser.parse("events/jdk.GarbageCollection[name=~\".*Young.*\"][duration>50000000]");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.segments.size());
    assertEquals("jdk.GarbageCollection", q.segments.get(0));
    assertEquals(2, q.predicates.size());

    // First predicate: regex filter
    var pred1 = q.predicates.get(0);
    if (pred1 instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.REGEX, p.op);
      assertEquals(".*Young.*", p.literal);
      assertEquals(1, p.fieldPath.size());
      assertEquals("name", p.fieldPath.get(0));
    } else if (pred1 instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.REGEX, ce.op);
      assertEquals(".*Young.*", ce.literal);
    }

    // Second predicate: numeric comparison
    var pred2 = q.predicates.get(1);
    if (pred2 instanceof JfrPath.FieldPredicate p) {
      assertEquals(JfrPath.Op.GT, p.op);
      assertTrue(p.literal instanceof Number);
      assertEquals(1, p.fieldPath.size());
      assertEquals("duration", p.fieldPath.get(0));
    } else if (pred2 instanceof JfrPath.ExprPredicate ep) {
      assertTrue(ep.expr instanceof JfrPath.CompExpr);
      var ce = (JfrPath.CompExpr) ep.expr;
      assertEquals(JfrPath.Op.GT, ce.op);
      assertTrue(ce.literal instanceof Number);
    }
  }

  @Test
  void parsesSelectWithSingleField() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.segments.size());
    assertEquals("jdk.ExecutionSample", q.segments.get(0));
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) op;
    assertEquals(1, selectOp.fieldPaths().size());
    assertEquals(java.util.List.of("startTime"), selectOp.fieldPaths().get(0));
  }

  @Test
  void parsesSelectWithMultipleFields() {
    var q =
        JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime, duration, stackTrace)");
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) op;
    assertEquals(3, selectOp.fieldPaths().size());
    assertEquals(java.util.List.of("startTime"), selectOp.fieldPaths().get(0));
    assertEquals(java.util.List.of("duration"), selectOp.fieldPaths().get(1));
    assertEquals(java.util.List.of("stackTrace"), selectOp.fieldPaths().get(2));
  }

  @Test
  void parsesSelectWithNestedPaths() {
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | select(eventThread/javaThreadId, eventThread/name)");
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) op;
    assertEquals(2, selectOp.fieldPaths().size());
    assertEquals(java.util.List.of("eventThread", "javaThreadId"), selectOp.fieldPaths().get(0));
    assertEquals(java.util.List.of("eventThread", "name"), selectOp.fieldPaths().get(1));
  }

  @Test
  void parsesSelectAfterOtherPipelineOps() {
    var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(path) | select(key, value)");
    assertEquals(2, q.pipeline.size());

    assertTrue(q.pipeline.get(0) instanceof JfrPath.GroupByOp);
    assertTrue(q.pipeline.get(1) instanceof JfrPath.SelectOp);

    var selectOp = (JfrPath.SelectOp) q.pipeline.get(1);
    assertEquals(2, selectOp.fieldPaths().size());
    assertEquals(java.util.List.of("key"), selectOp.fieldPaths().get(0));
    assertEquals(java.util.List.of("value"), selectOp.fieldPaths().get(1));
  }

  @Test
  void parsesToMapWithSimpleFields() {
    var q =
        JfrPathParser.parse("events/jdk.ActiveSetting | select(name, value) | toMap(name, value)");
    assertEquals(2, q.pipeline.size());

    var op = q.pipeline.get(1);
    assertTrue(op instanceof JfrPath.ToMapOp);
    var toMapOp = (JfrPath.ToMapOp) op;
    assertEquals(java.util.List.of("name"), toMapOp.keyField);
    assertEquals(java.util.List.of("value"), toMapOp.valueField);
  }

  @Test
  void parsesToMapWithNestedFields() {
    var q = JfrPathParser.parse("events/X | toMap(thread/name, duration)");
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.ToMapOp);
    var toMapOp = (JfrPath.ToMapOp) op;
    assertEquals(java.util.List.of("thread", "name"), toMapOp.keyField);
    assertEquals(java.util.List.of("duration"), toMapOp.valueField);
  }

  @Test
  void toMapRequiresTwoArguments() {
    assertThrows(IllegalArgumentException.class, () -> JfrPathParser.parse("events/X | toMap()"));
    assertThrows(
        IllegalArgumentException.class, () -> JfrPathParser.parse("events/X | toMap(name)"));
  }

  @Test
  void toMapToStringFormat() {
    var q = JfrPathParser.parse("events/X | toMap(name, value)");
    var op = (JfrPath.ToMapOp) q.pipeline.get(0);
    assertEquals("toMap(name, value)", op.toString());
  }

  @Test
  void parsesTimerangeDefault() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | timerange()");
    assertEquals(1, q.pipeline.size());
    assertTrue(q.pipeline.get(0) instanceof JfrPath.TimeRangeOp);
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertNull(op.format);
  }

  @Test
  void parsesTimerangeWithPath() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | timerange(startTime)");
    assertEquals(1, q.pipeline.size());
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertNull(op.format);
  }

  @Test
  void parsesTimerangeWithFormat() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | timerange(format=\"yyyy-MM-dd\")");
    assertEquals(1, q.pipeline.size());
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertEquals("yyyy-MM-dd", op.format);
  }

  @Test
  void parsesTimerangeWithPathAndFormat() {
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | timerange(startTime, format=\"HH:mm:ss\")");
    assertEquals(1, q.pipeline.size());
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertEquals("HH:mm:ss", op.format);
  }

  @Test
  void timerangeToStringFormat() {
    var q = JfrPathParser.parse("events/X | timerange(startTime, format=\"yyyy-MM-dd\")");
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals("timerange(startTime, format=yyyy-MM-dd)", op.toString());
  }

  @Test
  void parsesTimerangeWithDuration() {
    var q = JfrPathParser.parse("events/jdk.FileRead | timerange(startTime, duration=duration)");
    assertEquals(1, q.pipeline.size());
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertEquals(java.util.List.of("duration"), op.durationPath);
    assertNull(op.format);
  }

  @Test
  void parsesTimerangeWithDurationAndFormat() {
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | timerange(startTime, duration=duration, format=\"HH:mm:ss\")");
    assertEquals(1, q.pipeline.size());
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals(java.util.List.of("startTime"), op.valuePath);
    assertEquals(java.util.List.of("duration"), op.durationPath);
    assertEquals("HH:mm:ss", op.format);
  }

  @Test
  void timerangeWithDurationToString() {
    var q = JfrPathParser.parse("events/X | timerange(startTime, duration=dur)");
    var op = (JfrPath.TimeRangeOp) q.pipeline.get(0);
    assertEquals("timerange(startTime, duration=dur)", op.toString());
  }
}
