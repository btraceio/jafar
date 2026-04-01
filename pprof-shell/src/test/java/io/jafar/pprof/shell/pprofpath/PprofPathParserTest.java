package io.jafar.pprof.shell.pprofpath;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the PprofPath recursive-descent parser. */
class PprofPathParserTest {

  // ---- Root ----

  @Test
  void parseBareRoot() {
    PprofPath.Query q = PprofPathParser.parse("samples");
    assertEquals(PprofPath.Root.SAMPLES, q.root());
    assertTrue(q.predicates().isEmpty());
    assertTrue(q.pipeline().isEmpty());
  }

  @Test
  void parseRootWithWhitespace() {
    PprofPath.Query q = PprofPathParser.parse("  samples  ");
    assertEquals(PprofPath.Root.SAMPLES, q.root());
  }

  @Test
  void unknownRootThrows() {
    assertThrows(PprofPathParseException.class, () -> PprofPathParser.parse("events"));
  }

  @Test
  void emptyInputThrows() {
    assertThrows(PprofPathParseException.class, () -> PprofPathParser.parse(""));
  }

  @Test
  void nullInputThrows() {
    assertThrows(PprofPathParseException.class, () -> PprofPathParser.parse(null));
  }

  // ---- Predicates ----

  @Test
  void parseSimpleEqualsPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread='main']");
    assertEquals(1, q.predicates().size());
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals("thread", fp.field());
    assertEquals(PprofPath.Op.EQ, fp.op());
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDoubleEqualsPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu == 1000]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.EQ, fp.op());
  }

  @Test
  void parseNotEqualsPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread != 'GC Thread#0']");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.NE, fp.op());
    assertEquals("GC Thread#0", fp.literal());
  }

  @Test
  void parseGreaterThanPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu > 1000000]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.GT, fp.op());
    assertEquals(1000000L, ((Number) fp.literal()).longValue());
  }

  @Test
  void parseGreaterEqualPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu >= 500]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.GE, fp.op());
  }

  @Test
  void parseLessThanPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu < 100]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.LT, fp.op());
  }

  @Test
  void parseLessEqualPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu <= 200]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.LE, fp.op());
  }

  @Test
  void parseRegexPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread ~ 'Worker.*']");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(PprofPath.Op.REGEX, fp.op());
    assertEquals("Worker.*", fp.literal());
  }

  @Test
  void parseAndPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread='main' and cpu > 1000]");
    assertEquals(1, q.predicates().size());
    assertInstanceOf(PprofPath.LogicalPredicate.class, q.predicates().get(0));
    PprofPath.LogicalPredicate lp = (PprofPath.LogicalPredicate) q.predicates().get(0);
    assertTrue(lp.and());
  }

  @Test
  void parseOrPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread='main' or thread='worker']");
    PprofPath.LogicalPredicate lp = (PprofPath.LogicalPredicate) q.predicates().get(0);
    assertFalse(lp.and());
  }

  @Test
  void parseNestedPathPredicate() {
    PprofPath.Query q = PprofPathParser.parse("samples[stackTrace/0/name ~ 'HashMap.*']");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals("stackTrace/0/name", fp.field());
  }

  @Test
  void parseDoubleQuotedString() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread=\"main\"]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals("main", fp.literal());
  }

  @Test
  void parseBareWordLiteral() {
    PprofPath.Query q = PprofPathParser.parse("samples[thread=main]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDecimalNumber() {
    PprofPath.Query q = PprofPathParser.parse("samples[cpu > 3.14]");
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) q.predicates().get(0);
    assertEquals(3.14, ((Number) fp.literal()).doubleValue(), 1e-9);
  }

  // ---- Pipeline operators ----

  @Test
  void parseCount() {
    PprofPath.Query q = PprofPathParser.parse("samples | count()");
    assertEquals(1, q.pipeline().size());
    assertInstanceOf(PprofPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseCountNoParens() {
    // count without parentheses is also accepted
    PprofPath.Query q = PprofPathParser.parse("samples | count");
    assertInstanceOf(PprofPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseTopWithN() {
    PprofPath.Query q = PprofPathParser.parse("samples | top(10)");
    PprofPath.TopOp op = (PprofPath.TopOp) q.pipeline().get(0);
    assertEquals(10, op.n());
    assertNull(op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseTopWithField() {
    PprofPath.Query q = PprofPathParser.parse("samples | top(5, cpu)");
    PprofPath.TopOp op = (PprofPath.TopOp) q.pipeline().get(0);
    assertEquals(5, op.n());
    assertEquals("cpu", op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseTopAscending() {
    PprofPath.Query q = PprofPathParser.parse("samples | top(5, cpu, asc)");
    PprofPath.TopOp op = (PprofPath.TopOp) q.pipeline().get(0);
    assertTrue(op.ascending());
  }

  @Test
  void parseTopDescending() {
    PprofPath.Query q = PprofPathParser.parse("samples | top(5, cpu, desc)");
    PprofPath.TopOp op = (PprofPath.TopOp) q.pipeline().get(0);
    assertFalse(op.ascending());
  }

  @Test
  void parseGroupByCount() {
    PprofPath.Query q = PprofPathParser.parse("samples | groupBy(thread)");
    PprofPath.GroupByOp op = (PprofPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("count", op.aggFunc());
    assertNull(op.valueField());
  }

  @Test
  void parseGroupBySum() {
    PprofPath.Query q = PprofPathParser.parse("samples | groupBy(thread, sum(cpu))");
    PprofPath.GroupByOp op = (PprofPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("sum", op.aggFunc());
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseGroupByAlias() {
    PprofPath.Query q = PprofPathParser.parse("samples | group(thread)");
    assertInstanceOf(PprofPath.GroupByOp.class, q.pipeline().get(0));
  }

  @Test
  void parseStats() {
    PprofPath.Query q = PprofPathParser.parse("samples | stats(cpu)");
    PprofPath.StatsOp op = (PprofPath.StatsOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseHead() {
    PprofPath.Query q = PprofPathParser.parse("samples | head(20)");
    PprofPath.HeadOp op = (PprofPath.HeadOp) q.pipeline().get(0);
    assertEquals(20, op.n());
  }

  @Test
  void parseTail() {
    PprofPath.Query q = PprofPathParser.parse("samples | tail(5)");
    PprofPath.TailOp op = (PprofPath.TailOp) q.pipeline().get(0);
    assertEquals(5, op.n());
  }

  @Test
  void parseFilter() {
    PprofPath.Query q = PprofPathParser.parse("samples | filter(cpu > 1000)");
    PprofPath.FilterOp op = (PprofPath.FilterOp) q.pipeline().get(0);
    assertEquals(1, op.predicates().size());
    PprofPath.FieldPredicate fp = (PprofPath.FieldPredicate) op.predicates().get(0);
    assertEquals("cpu", fp.field());
    assertEquals(PprofPath.Op.GT, fp.op());
  }

  @Test
  void parseFilterAlias() {
    PprofPath.Query q = PprofPathParser.parse("samples | where(cpu > 1000)");
    assertInstanceOf(PprofPath.FilterOp.class, q.pipeline().get(0));
  }

  @Test
  void parseSelect() {
    PprofPath.Query q = PprofPathParser.parse("samples | select(cpu, thread)");
    PprofPath.SelectOp op = (PprofPath.SelectOp) q.pipeline().get(0);
    assertEquals(List.of("cpu", "thread"), op.fields());
  }

  @Test
  void parseSortBy() {
    PprofPath.Query q = PprofPathParser.parse("samples | sortBy(cpu)");
    PprofPath.SortByOp op = (PprofPath.SortByOp) q.pipeline().get(0);
    assertEquals("cpu", op.field());
    assertFalse(op.ascending());
  }

  @Test
  void parseSortByAscending() {
    PprofPath.Query q = PprofPathParser.parse("samples | sortBy(cpu, asc)");
    PprofPath.SortByOp op = (PprofPath.SortByOp) q.pipeline().get(0);
    assertTrue(op.ascending());
  }

  @Test
  void parseSortByAlias() {
    PprofPath.Query q = PprofPathParser.parse("samples | sort(cpu)");
    assertInstanceOf(PprofPath.SortByOp.class, q.pipeline().get(0));
  }

  @Test
  void parseStackprofileNoArg() {
    PprofPath.Query q = PprofPathParser.parse("samples | stackprofile()");
    PprofPath.StackProfileOp op = (PprofPath.StackProfileOp) q.pipeline().get(0);
    assertNull(op.valueField());
  }

  @Test
  void parseStackprofileWithField() {
    PprofPath.Query q = PprofPathParser.parse("samples | stackprofile(cpu)");
    PprofPath.StackProfileOp op = (PprofPath.StackProfileOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseDistinct() {
    PprofPath.Query q = PprofPathParser.parse("samples | distinct(thread)");
    PprofPath.DistinctOp op = (PprofPath.DistinctOp) q.pipeline().get(0);
    assertEquals("thread", op.field());
  }

  @Test
  void parseDistinctAlias() {
    PprofPath.Query q = PprofPathParser.parse("samples | unique(thread)");
    assertInstanceOf(PprofPath.DistinctOp.class, q.pipeline().get(0));
  }

  // ---- Chained pipeline ----

  @Test
  void parseChainedPipeline() {
    PprofPath.Query q = PprofPathParser.parse("samples | groupBy(thread, sum(cpu)) | head(10)");
    assertEquals(2, q.pipeline().size());
    assertInstanceOf(PprofPath.GroupByOp.class, q.pipeline().get(0));
    assertInstanceOf(PprofPath.HeadOp.class, q.pipeline().get(1));
  }

  @Test
  void parseFilterAndSort() {
    PprofPath.Query q =
        PprofPathParser.parse(
            "samples[thread='main'] | groupBy(stackTrace/0/name, sum(cpu)) | head(5)");
    assertEquals(PprofPath.Root.SAMPLES, q.root());
    assertEquals(1, q.predicates().size());
    assertEquals(2, q.pipeline().size());
  }

  // ---- Error cases ----

  @Test
  void unknownOperatorThrows() {
    assertThrows(
        PprofPathParseException.class, () -> PprofPathParser.parse("samples | flamegraph()"));
  }

  @Test
  void trailingJunkThrows() {
    assertThrows(PprofPathParseException.class, () -> PprofPathParser.parse("samples junk"));
  }
}
