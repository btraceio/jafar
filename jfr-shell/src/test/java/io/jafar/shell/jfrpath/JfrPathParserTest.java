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
  void parsesSelectWithSingleField() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime)");
    assertEquals(JfrPath.Root.EVENTS, q.root);
    assertEquals(1, q.segments.size());
    assertEquals("jdk.ExecutionSample", q.segments.get(0));
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) op;
    assertEquals(1, selectOp.fieldPaths.size());
    assertEquals(java.util.List.of("startTime"), selectOp.fieldPaths.get(0));
  }

  @Test
  void parsesSelectWithMultipleFields() {
    var q =
        JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime, duration, stackTrace)");
    assertEquals(1, q.pipeline.size());

    var op = q.pipeline.get(0);
    assertTrue(op instanceof JfrPath.SelectOp);
    var selectOp = (JfrPath.SelectOp) op;
    assertEquals(3, selectOp.fieldPaths.size());
    assertEquals(java.util.List.of("startTime"), selectOp.fieldPaths.get(0));
    assertEquals(java.util.List.of("duration"), selectOp.fieldPaths.get(1));
    assertEquals(java.util.List.of("stackTrace"), selectOp.fieldPaths.get(2));
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
    assertEquals(2, selectOp.fieldPaths.size());
    assertEquals(java.util.List.of("eventThread", "javaThreadId"), selectOp.fieldPaths.get(0));
    assertEquals(java.util.List.of("eventThread", "name"), selectOp.fieldPaths.get(1));
  }

  @Test
  void parsesSelectAfterOtherPipelineOps() {
    var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(path) | select(key, value)");
    assertEquals(2, q.pipeline.size());

    assertTrue(q.pipeline.get(0) instanceof JfrPath.GroupByOp);
    assertTrue(q.pipeline.get(1) instanceof JfrPath.SelectOp);

    var selectOp = (JfrPath.SelectOp) q.pipeline.get(1);
    assertEquals(2, selectOp.fieldPaths.size());
    assertEquals(java.util.List.of("key"), selectOp.fieldPaths.get(0));
    assertEquals(java.util.List.of("value"), selectOp.fieldPaths.get(1));
  }
}
