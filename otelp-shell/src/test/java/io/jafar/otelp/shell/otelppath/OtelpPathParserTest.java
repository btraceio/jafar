package io.jafar.otelp.shell.otelppath;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the OtelpPath recursive-descent parser. */
class OtelpPathParserTest {

  // ---- Root ----

  @Test
  void parseBareRoot() {
    OtelpPath.Query q = OtelpPathParser.parse("samples");
    assertEquals(OtelpPath.Root.SAMPLES, q.root());
    assertTrue(q.predicates().isEmpty());
    assertTrue(q.pipeline().isEmpty());
  }

  @Test
  void parseRootWithWhitespace() {
    OtelpPath.Query q = OtelpPathParser.parse("  samples  ");
    assertEquals(OtelpPath.Root.SAMPLES, q.root());
  }

  @Test
  void unknownRootThrows() {
    assertThrows(OtelpPathParseException.class, () -> OtelpPathParser.parse("events"));
  }

  @Test
  void emptyInputThrows() {
    assertThrows(OtelpPathParseException.class, () -> OtelpPathParser.parse(""));
  }

  @Test
  void nullInputThrows() {
    assertThrows(OtelpPathParseException.class, () -> OtelpPathParser.parse(null));
  }

  // ---- Predicates ----

  @Test
  void parseSimpleEqualsPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[thread='main']");
    assertEquals(1, q.predicates().size());
    OtelpPath.FieldPredicate fp = (OtelpPath.FieldPredicate) q.predicates().get(0);
    assertEquals("thread", fp.field());
    assertEquals(OtelpPath.Op.EQ, fp.op());
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDoubleEqualsPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[cpu == 1000]");
    OtelpPath.FieldPredicate fp = (OtelpPath.FieldPredicate) q.predicates().get(0);
    assertEquals(OtelpPath.Op.EQ, fp.op());
  }

  @Test
  void parseGreaterThanPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[cpu > 1000000]");
    OtelpPath.FieldPredicate fp = (OtelpPath.FieldPredicate) q.predicates().get(0);
    assertEquals(OtelpPath.Op.GT, fp.op());
    assertEquals(1000000L, ((Number) fp.literal()).longValue());
  }

  @Test
  void parseRegexPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[thread ~ 'Worker.*']");
    OtelpPath.FieldPredicate fp = (OtelpPath.FieldPredicate) q.predicates().get(0);
    assertEquals(OtelpPath.Op.REGEX, fp.op());
    assertEquals("Worker.*", fp.literal());
  }

  @Test
  void parseAndPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[thread='main' and cpu > 1000]");
    assertEquals(1, q.predicates().size());
    assertInstanceOf(OtelpPath.LogicalPredicate.class, q.predicates().get(0));
    OtelpPath.LogicalPredicate lp = (OtelpPath.LogicalPredicate) q.predicates().get(0);
    assertTrue(lp.and());
  }

  @Test
  void parseOrPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[thread='main' or thread='worker']");
    OtelpPath.LogicalPredicate lp = (OtelpPath.LogicalPredicate) q.predicates().get(0);
    assertFalse(lp.and());
  }

  @Test
  void parseNestedPathPredicate() {
    OtelpPath.Query q = OtelpPathParser.parse("samples[stackTrace/0/name ~ 'HashMap.*']");
    OtelpPath.FieldPredicate fp = (OtelpPath.FieldPredicate) q.predicates().get(0);
    assertEquals("stackTrace/0/name", fp.field());
  }

  // ---- Pipeline operators ----

  @Test
  void parseCount() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | count()");
    assertEquals(1, q.pipeline().size());
    assertInstanceOf(OtelpPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseTopWithField() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | top(5, cpu)");
    OtelpPath.TopOp op = (OtelpPath.TopOp) q.pipeline().get(0);
    assertEquals(5, op.n());
    assertEquals("cpu", op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseGroupByCount() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | groupBy(thread)");
    OtelpPath.GroupByOp op = (OtelpPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("count", op.aggFunc());
    assertNull(op.valueField());
  }

  @Test
  void parseGroupBySum() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | groupBy(thread, sum(cpu))");
    OtelpPath.GroupByOp op = (OtelpPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("sum", op.aggFunc());
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseStats() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | stats(cpu)");
    OtelpPath.StatsOp op = (OtelpPath.StatsOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseHead() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | head(20)");
    OtelpPath.HeadOp op = (OtelpPath.HeadOp) q.pipeline().get(0);
    assertEquals(20, op.n());
  }

  @Test
  void parseTail() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | tail(5)");
    OtelpPath.TailOp op = (OtelpPath.TailOp) q.pipeline().get(0);
    assertEquals(5, op.n());
  }

  @Test
  void parseStackprofileNoArg() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | stackprofile()");
    OtelpPath.StackProfileOp op = (OtelpPath.StackProfileOp) q.pipeline().get(0);
    assertNull(op.valueField());
  }

  @Test
  void parseStackprofileWithField() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | stackprofile(cpu)");
    OtelpPath.StackProfileOp op = (OtelpPath.StackProfileOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseDistinct() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | distinct(thread)");
    OtelpPath.DistinctOp op = (OtelpPath.DistinctOp) q.pipeline().get(0);
    assertEquals("thread", op.field());
  }

  @Test
  void parseSelect() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | select(cpu, thread)");
    OtelpPath.SelectOp op = (OtelpPath.SelectOp) q.pipeline().get(0);
    assertEquals(List.of("cpu", "thread"), op.fields());
  }

  @Test
  void parseSortBy() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | sortBy(cpu)");
    OtelpPath.SortByOp op = (OtelpPath.SortByOp) q.pipeline().get(0);
    assertEquals("cpu", op.field());
    assertFalse(op.ascending());
  }

  // ---- Chained pipeline ----

  @Test
  void parseChainedPipeline() {
    OtelpPath.Query q = OtelpPathParser.parse("samples | groupBy(thread, sum(cpu)) | head(10)");
    assertEquals(2, q.pipeline().size());
    assertInstanceOf(OtelpPath.GroupByOp.class, q.pipeline().get(0));
    assertInstanceOf(OtelpPath.HeadOp.class, q.pipeline().get(1));
  }

  // ---- Error cases ----

  @Test
  void unknownOperatorThrows() {
    assertThrows(
        OtelpPathParseException.class, () -> OtelpPathParser.parse("samples | flamegraph()"));
  }

  @Test
  void trailingJunkThrows() {
    assertThrows(OtelpPathParseException.class, () -> OtelpPathParser.parse("samples junk"));
  }
}
