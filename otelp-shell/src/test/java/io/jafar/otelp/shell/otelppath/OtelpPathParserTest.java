package io.jafar.otelp.shell.otelppath;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.sampling.path.SamplesPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the OtelpPath recursive-descent parser. */
class OtelpPathParserTest {

  // ---- Root ----

  @Test
  void parseBareRoot() {
    SamplesPath.Query q = OtelpPathParser.parse("samples");
    assertEquals(SamplesPath.Root.SAMPLES, q.root());
    assertTrue(q.predicates().isEmpty());
    assertTrue(q.pipeline().isEmpty());
  }

  @Test
  void parseRootWithWhitespace() {
    SamplesPath.Query q = OtelpPathParser.parse("  samples  ");
    assertEquals(SamplesPath.Root.SAMPLES, q.root());
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
    SamplesPath.Query q = OtelpPathParser.parse("samples[thread='main']");
    assertEquals(1, q.predicates().size());
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("thread", fp.field());
    assertEquals(SamplesPath.Op.EQ, fp.op());
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDoubleEqualsPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[cpu == 1000]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.EQ, fp.op());
  }

  @Test
  void parseGreaterThanPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[cpu > 1000000]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.GT, fp.op());
    assertEquals(1000000L, ((Number) fp.literal()).longValue());
  }

  @Test
  void parseRegexPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[thread ~ 'Worker.*']");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.REGEX, fp.op());
    assertEquals("Worker.*", fp.literal());
  }

  @Test
  void parseAndPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[thread='main' and cpu > 1000]");
    assertEquals(1, q.predicates().size());
    assertInstanceOf(SamplesPath.LogicalPredicate.class, q.predicates().get(0));
    SamplesPath.LogicalPredicate lp = (SamplesPath.LogicalPredicate) q.predicates().get(0);
    assertTrue(lp.and());
  }

  @Test
  void parseOrPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[thread='main' or thread='worker']");
    SamplesPath.LogicalPredicate lp = (SamplesPath.LogicalPredicate) q.predicates().get(0);
    assertFalse(lp.and());
  }

  @Test
  void parseNestedPathPredicate() {
    SamplesPath.Query q = OtelpPathParser.parse("samples[stackTrace/0/name ~ 'HashMap.*']");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("stackTrace/0/name", fp.field());
  }

  // ---- Pipeline operators ----

  @Test
  void parseCount() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | count()");
    assertEquals(1, q.pipeline().size());
    assertInstanceOf(SamplesPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseTopWithField() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | top(5, cpu)");
    SamplesPath.TopOp op = (SamplesPath.TopOp) q.pipeline().get(0);
    assertEquals(5, op.n());
    assertEquals("cpu", op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseGroupByCount() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | groupBy(thread)");
    SamplesPath.GroupByOp op = (SamplesPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("count", op.aggFunc());
    assertNull(op.valueField());
  }

  @Test
  void parseGroupBySum() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | groupBy(thread, sum(cpu))");
    SamplesPath.GroupByOp op = (SamplesPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("sum", op.aggFunc());
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseStats() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | stats(cpu)");
    SamplesPath.StatsOp op = (SamplesPath.StatsOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseHead() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | head(20)");
    SamplesPath.HeadOp op = (SamplesPath.HeadOp) q.pipeline().get(0);
    assertEquals(20, op.n());
  }

  @Test
  void parseTail() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | tail(5)");
    SamplesPath.TailOp op = (SamplesPath.TailOp) q.pipeline().get(0);
    assertEquals(5, op.n());
  }

  @Test
  void parseStackprofileNoArg() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | stackprofile()");
    SamplesPath.StackProfileOp op = (SamplesPath.StackProfileOp) q.pipeline().get(0);
    assertNull(op.valueField());
  }

  @Test
  void parseStackprofileWithField() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | stackprofile(cpu)");
    SamplesPath.StackProfileOp op = (SamplesPath.StackProfileOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseDistinct() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | distinct(thread)");
    SamplesPath.DistinctOp op = (SamplesPath.DistinctOp) q.pipeline().get(0);
    assertEquals("thread", op.field());
  }

  @Test
  void parseSelect() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | select(cpu, thread)");
    SamplesPath.SelectOp op = (SamplesPath.SelectOp) q.pipeline().get(0);
    assertEquals(List.of("cpu", "thread"), op.fields());
  }

  @Test
  void parseSortBy() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | sortBy(cpu)");
    SamplesPath.SortByOp op = (SamplesPath.SortByOp) q.pipeline().get(0);
    assertEquals("cpu", op.field());
    assertFalse(op.ascending());
  }

  // ---- Chained pipeline ----

  @Test
  void parseChainedPipeline() {
    SamplesPath.Query q = OtelpPathParser.parse("samples | groupBy(thread, sum(cpu)) | head(10)");
    assertEquals(2, q.pipeline().size());
    assertInstanceOf(SamplesPath.GroupByOp.class, q.pipeline().get(0));
    assertInstanceOf(SamplesPath.HeadOp.class, q.pipeline().get(1));
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
