package io.jafar.pprof.shell.pprofpath;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.sampling.path.SamplesPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the PprofPath recursive-descent parser. */
class PprofPathParserTest {

  // ---- Root ----

  @Test
  void parseBareRoot() {
    SamplesPath.Query q = PprofPathParser.parse("samples");
    assertEquals(SamplesPath.Root.SAMPLES, q.root());
    assertTrue(q.predicates().isEmpty());
    assertTrue(q.pipeline().isEmpty());
  }

  @Test
  void parseRootWithWhitespace() {
    SamplesPath.Query q = PprofPathParser.parse("  samples  ");
    assertEquals(SamplesPath.Root.SAMPLES, q.root());
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
    SamplesPath.Query q = PprofPathParser.parse("samples[thread='main']");
    assertEquals(1, q.predicates().size());
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("thread", fp.field());
    assertEquals(SamplesPath.Op.EQ, fp.op());
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDoubleEqualsPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu == 1000]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.EQ, fp.op());
  }

  @Test
  void parseNotEqualsPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread != 'GC Thread#0']");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.NE, fp.op());
    assertEquals("GC Thread#0", fp.literal());
  }

  @Test
  void parseGreaterThanPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu > 1000000]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.GT, fp.op());
    assertEquals(1000000L, ((Number) fp.literal()).longValue());
  }

  @Test
  void parseGreaterEqualPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu >= 500]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.GE, fp.op());
  }

  @Test
  void parseLessThanPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu < 100]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.LT, fp.op());
  }

  @Test
  void parseLessEqualPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu <= 200]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.LE, fp.op());
  }

  @Test
  void parseRegexPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread ~ 'Worker.*']");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(SamplesPath.Op.REGEX, fp.op());
    assertEquals("Worker.*", fp.literal());
  }

  @Test
  void parseAndPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread='main' and cpu > 1000]");
    assertEquals(1, q.predicates().size());
    assertInstanceOf(SamplesPath.LogicalPredicate.class, q.predicates().get(0));
    SamplesPath.LogicalPredicate lp = (SamplesPath.LogicalPredicate) q.predicates().get(0);
    assertTrue(lp.and());
  }

  @Test
  void parseOrPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread='main' or thread='worker']");
    SamplesPath.LogicalPredicate lp = (SamplesPath.LogicalPredicate) q.predicates().get(0);
    assertFalse(lp.and());
  }

  @Test
  void parseNestedPathPredicate() {
    SamplesPath.Query q = PprofPathParser.parse("samples[stackTrace/0/name ~ 'HashMap.*']");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("stackTrace/0/name", fp.field());
  }

  @Test
  void parseDoubleQuotedString() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread=\"main\"]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("main", fp.literal());
  }

  @Test
  void parseBareWordLiteral() {
    SamplesPath.Query q = PprofPathParser.parse("samples[thread=main]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals("main", fp.literal());
  }

  @Test
  void parseDecimalNumber() {
    SamplesPath.Query q = PprofPathParser.parse("samples[cpu > 3.14]");
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) q.predicates().get(0);
    assertEquals(3.14, ((Number) fp.literal()).doubleValue(), 1e-9);
  }

  // ---- Pipeline operators ----

  @Test
  void parseCount() {
    SamplesPath.Query q = PprofPathParser.parse("samples | count()");
    assertEquals(1, q.pipeline().size());
    assertInstanceOf(SamplesPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseCountNoParens() {
    // count without parentheses is also accepted
    SamplesPath.Query q = PprofPathParser.parse("samples | count");
    assertInstanceOf(SamplesPath.CountOp.class, q.pipeline().get(0));
  }

  @Test
  void parseTopWithN() {
    SamplesPath.Query q = PprofPathParser.parse("samples | top(10)");
    SamplesPath.TopOp op = (SamplesPath.TopOp) q.pipeline().get(0);
    assertEquals(10, op.n());
    assertNull(op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseTopWithField() {
    SamplesPath.Query q = PprofPathParser.parse("samples | top(5, cpu)");
    SamplesPath.TopOp op = (SamplesPath.TopOp) q.pipeline().get(0);
    assertEquals(5, op.n());
    assertEquals("cpu", op.byField());
    assertFalse(op.ascending());
  }

  @Test
  void parseTopAscending() {
    SamplesPath.Query q = PprofPathParser.parse("samples | top(5, cpu, asc)");
    SamplesPath.TopOp op = (SamplesPath.TopOp) q.pipeline().get(0);
    assertTrue(op.ascending());
  }

  @Test
  void parseTopDescending() {
    SamplesPath.Query q = PprofPathParser.parse("samples | top(5, cpu, desc)");
    SamplesPath.TopOp op = (SamplesPath.TopOp) q.pipeline().get(0);
    assertFalse(op.ascending());
  }

  @Test
  void parseGroupByCount() {
    SamplesPath.Query q = PprofPathParser.parse("samples | groupBy(thread)");
    SamplesPath.GroupByOp op = (SamplesPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("count", op.aggFunc());
    assertNull(op.valueField());
  }

  @Test
  void parseGroupBySum() {
    SamplesPath.Query q = PprofPathParser.parse("samples | groupBy(thread, sum(cpu))");
    SamplesPath.GroupByOp op = (SamplesPath.GroupByOp) q.pipeline().get(0);
    assertEquals("thread", op.keyField());
    assertEquals("sum", op.aggFunc());
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseGroupByAlias() {
    SamplesPath.Query q = PprofPathParser.parse("samples | group(thread)");
    assertInstanceOf(SamplesPath.GroupByOp.class, q.pipeline().get(0));
  }

  @Test
  void parseStats() {
    SamplesPath.Query q = PprofPathParser.parse("samples | stats(cpu)");
    SamplesPath.StatsOp op = (SamplesPath.StatsOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseHead() {
    SamplesPath.Query q = PprofPathParser.parse("samples | head(20)");
    SamplesPath.HeadOp op = (SamplesPath.HeadOp) q.pipeline().get(0);
    assertEquals(20, op.n());
  }

  @Test
  void parseTail() {
    SamplesPath.Query q = PprofPathParser.parse("samples | tail(5)");
    SamplesPath.TailOp op = (SamplesPath.TailOp) q.pipeline().get(0);
    assertEquals(5, op.n());
  }

  @Test
  void parseFilter() {
    SamplesPath.Query q = PprofPathParser.parse("samples | filter(cpu > 1000)");
    SamplesPath.FilterOp op = (SamplesPath.FilterOp) q.pipeline().get(0);
    assertEquals(1, op.predicates().size());
    SamplesPath.FieldPredicate fp = (SamplesPath.FieldPredicate) op.predicates().get(0);
    assertEquals("cpu", fp.field());
    assertEquals(SamplesPath.Op.GT, fp.op());
  }

  @Test
  void parseFilterAlias() {
    SamplesPath.Query q = PprofPathParser.parse("samples | where(cpu > 1000)");
    assertInstanceOf(SamplesPath.FilterOp.class, q.pipeline().get(0));
  }

  @Test
  void parseSelect() {
    SamplesPath.Query q = PprofPathParser.parse("samples | select(cpu, thread)");
    SamplesPath.SelectOp op = (SamplesPath.SelectOp) q.pipeline().get(0);
    assertEquals(List.of("cpu", "thread"), op.fields());
  }

  @Test
  void parseSortBy() {
    SamplesPath.Query q = PprofPathParser.parse("samples | sortBy(cpu)");
    SamplesPath.SortByOp op = (SamplesPath.SortByOp) q.pipeline().get(0);
    assertEquals("cpu", op.field());
    assertFalse(op.ascending());
  }

  @Test
  void parseSortByAscending() {
    SamplesPath.Query q = PprofPathParser.parse("samples | sortBy(cpu, asc)");
    SamplesPath.SortByOp op = (SamplesPath.SortByOp) q.pipeline().get(0);
    assertTrue(op.ascending());
  }

  @Test
  void parseSortByAlias() {
    SamplesPath.Query q = PprofPathParser.parse("samples | sort(cpu)");
    assertInstanceOf(SamplesPath.SortByOp.class, q.pipeline().get(0));
  }

  @Test
  void parseStackprofileNoArg() {
    SamplesPath.Query q = PprofPathParser.parse("samples | stackprofile()");
    SamplesPath.StackProfileOp op = (SamplesPath.StackProfileOp) q.pipeline().get(0);
    assertNull(op.valueField());
  }

  @Test
  void parseStackprofileWithField() {
    SamplesPath.Query q = PprofPathParser.parse("samples | stackprofile(cpu)");
    SamplesPath.StackProfileOp op = (SamplesPath.StackProfileOp) q.pipeline().get(0);
    assertEquals("cpu", op.valueField());
  }

  @Test
  void parseDistinct() {
    SamplesPath.Query q = PprofPathParser.parse("samples | distinct(thread)");
    SamplesPath.DistinctOp op = (SamplesPath.DistinctOp) q.pipeline().get(0);
    assertEquals("thread", op.field());
  }

  @Test
  void parseDistinctAlias() {
    SamplesPath.Query q = PprofPathParser.parse("samples | unique(thread)");
    assertInstanceOf(SamplesPath.DistinctOp.class, q.pipeline().get(0));
  }

  // ---- Chained pipeline ----

  @Test
  void parseChainedPipeline() {
    SamplesPath.Query q = PprofPathParser.parse("samples | groupBy(thread, sum(cpu)) | head(10)");
    assertEquals(2, q.pipeline().size());
    assertInstanceOf(SamplesPath.GroupByOp.class, q.pipeline().get(0));
    assertInstanceOf(SamplesPath.HeadOp.class, q.pipeline().get(1));
  }

  @Test
  void parseFilterAndSort() {
    SamplesPath.Query q =
        PprofPathParser.parse(
            "samples[thread='main'] | groupBy(stackTrace/0/name, sum(cpu)) | head(5)");
    assertEquals(SamplesPath.Root.SAMPLES, q.root());
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
