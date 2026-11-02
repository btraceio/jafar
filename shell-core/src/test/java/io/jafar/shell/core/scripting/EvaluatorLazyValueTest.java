package io.jafar.shell.core.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The format-neutral lazy variable and the shared readers ({@code ${var}}, {@code if}) that must
 * treat it exactly as jfr-shell treats its JfrPath {@code LazyQueryValue}.
 */
class EvaluatorLazyValueTest {

  /** A session of a non-JFR type, so nothing here can lean on JfrPath. */
  static final class FakeSession implements Session {
    boolean closed;

    @Override
    public String getType() {
      return "fake";
    }

    @Override
    public Path getFilePath() {
      return Path.of("/tmp/x.fake");
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public Set<String> getAvailableTypes() {
      return Set.of();
    }

    @Override
    public Map<String, Object> getStatistics() {
      return Map.of();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** Records what it was asked, and answers with fixed rows. */
  static final class FakeEvaluator implements QueryEvaluator {
    final List<String> parsed = new ArrayList<>();
    final List<Object> resolvers = new ArrayList<>();
    volatile int evaluations;
    volatile long delayMillis;
    volatile boolean returnNull;
    // When set, an evaluation signals 'entered' and waits for 'proceed' before returning
    volatile java.util.concurrent.CountDownLatch entered;
    volatile java.util.concurrent.CountDownLatch proceed;
    List<Map<String, Object>> rows = List.of(row("a", 3), row("b", 1));

    // Ordered: ${var} with no field is the first field of the first row
    private static Map<String, Object> row(String name, int n) {
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("name", name);
      row.put("n", n);
      return row;
    }

    @Override
    public Object parse(String q) {
      if (q.startsWith("bad")) {
        throw new QueryParseException("bad query", q, 0);
      }
      parsed.add(q);
      return "P:" + q;
    }

    @Override
    public synchronized Object evaluate(Session session, Object query) throws InterruptedException {
      evaluations++;
      if (entered != null) {
        entered.countDown();
        proceed.await();
      }
      if (delayMillis > 0) {
        Thread.sleep(delayMillis); // widen the window in which a second evaluation could start
      }
      return returnNull ? null : rows;
    }

    @Override
    public Object evaluate(Session session, Object query, SessionResolver resolver)
        throws InterruptedException {
      resolvers.add(resolver);
      return evaluate(session, query);
    }

    /** As JfrPath's: only a query ending in toMap() yields a map. */
    @Override
    public boolean producesMap(Object query) {
      return String.valueOf(query).endsWith("toMap(name, n)");
    }

    @Override
    public List<String> getRootTypes() {
      return List.of("things");
    }

    @Override
    public List<String> getOperators() {
      return List.of("count");
    }

    @Override
    public String getOperatorHelp(String operator) {
      return null;
    }

    @Override
    public VariableStore.LazyValue createLazyValue(Session s, Object q, String qs) {
      throw new UnsupportedOperationException();
    }
  }

  private final FakeEvaluator evaluator = new FakeEvaluator();
  private final SessionManager<FakeSession> sessions =
      new SessionManager<>((p, c) -> new FakeSession(), null);

  private EvaluatorLazyValue lazy(SessionResolver resolver) throws Exception {
    var ref = sessions.open(Path.of("/tmp/x.fake"), null);
    return new EvaluatorLazyValue(evaluator, evaluator.parse("things"), "things", ref, resolver);
  }

  @Test
  void evaluatesOnceThroughTheEvaluatorWithTheResolver() throws Exception {
    SessionResolver resolver = id -> Optional.empty();
    EvaluatorLazyValue v = lazy(resolver);

    assertEquals("lazy[things] (not evaluated)", v.describe());
    assertEquals(0, evaluator.evaluations);
    assertEquals(evaluator.rows, v.get());
    v.get();
    assertEquals(1, evaluator.evaluations, "cached after first use");
    assertSame(resolver, evaluator.resolvers.get(0), "cross-session resolver passed through");
    assertEquals("lazy[things] (cached: 2 rows)", v.describe());

    v.invalidate();
    assertFalse(v.isCached());
    v.get();
    assertEquals(2, evaluator.evaluations);
  }

  @Test
  void copyHasItsOwnCache() throws Exception {
    EvaluatorLazyValue v = lazy(null);
    v.get();
    EvaluatorLazyValue copy = v.copy();
    assertTrue(copy.isCached());
    copy.invalidate();
    assertTrue(v.isCached(), "invalidating the copy leaves the original cached");
  }

  @Test
  void closedSessionIsReportedNotSilentlyEmpty() throws Exception {
    EvaluatorLazyValue v = lazy(null);
    v.get();
    assertTrue(v.isCached());
    sessions.closeAll();
    var e = assertThrows(IllegalStateException.class, v::get);
    assertTrue(e.getMessage().contains("closed"), e.getMessage());
    assertTrue(v.describe().endsWith("(closed)"), v.describe());
    assertFalse(v.isCached(), "rows of a closed session are dropped, not served stale");
    assertThrows(IllegalStateException.class, v::copy);
  }

  @Test
  void concurrentFirstReadsEvaluateOnceAndAllSeeTheSameRows() throws Exception {
    EvaluatorLazyValue v = lazy(null);
    evaluator.delayMillis = 50;
    int threads = 8;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    try {
      List<java.util.concurrent.Future<Object>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return v.get();
                }));
      }
      start.countDown();
      for (var r : results) {
        assertSame(evaluator.rows, r.get());
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, evaluator.evaluations);
  }

  /** Runs v.get() on another thread, holds it inside the evaluator, runs action, lets it finish. */
  private Object duringEvaluation(EvaluatorLazyValue v, Runnable action) throws Exception {
    evaluator.entered = new java.util.concurrent.CountDownLatch(1);
    evaluator.proceed = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.FutureTask<Object> read = new java.util.concurrent.FutureTask<>(v::get);
    new Thread(read).start();
    assertTrue(evaluator.entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
    action.run(); // must not wait for the evaluation
    evaluator.proceed.countDown();
    return read.get(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  void invalidateDuringEvaluationIsNotLost() throws Exception {
    EvaluatorLazyValue v = lazy(null);
    Object rows = duringEvaluation(v, v::invalidate);
    assertSame(evaluator.rows, rows, "the reader still gets the rows it asked for");
    assertFalse(v.isCached(), "rows computed before the invalidate must not be cached after it");
  }

  @Test
  void releaseDuringEvaluationIsNotUndone() throws Exception {
    EvaluatorLazyValue v = lazy(null);
    duringEvaluation(v, v::release);
    assertFalse(v.isCached(), "a released value must not get its rows back");
    assertEquals("lazy[things] (session gone)", v.describe());
  }

  @Test
  void nullResultCountsAsEvaluated() throws Exception {
    evaluator.returnNull = true;
    EvaluatorLazyValue v = lazy(null);
    assertEquals(null, v.get());
    assertTrue(v.isCached());
    v.get();
    assertEquals(1, evaluator.evaluations);
  }

  @Test
  void forgetSessionDropsOnlyThatSessionsRows() throws Exception {
    EvaluatorLazyValue first = lazy(null);
    EvaluatorLazyValue second = lazy(null); // a second session
    first.get();
    second.get();
    VariableStore global = new VariableStore();
    global.set("a", first);
    global.set("b", second);
    global.set("s", new VariableStore.ScalarValue(1L));

    EvaluatorLazyValue.forgetSession(global, first.sessionId());

    assertFalse(first.isCached());
    assertTrue(second.isCached());
  }

  @Test
  void substitutionAndConditionsReadAnyLazyValue() throws Exception {
    VariableStore global = new VariableStore();
    global.set("rows", lazy(null));

    VariableSubstitutor sub = new VariableSubstitutor(null, global);
    assertEquals("2", sub.substitute("${rows.size}"));
    assertEquals("a", sub.substitute("${rows}"));
    assertEquals("1", sub.substitute("${rows[1].n}"));
    assertEquals("b", sub.substitute("${rows[1].name}"));

    ConditionEvaluator cond = new ConditionEvaluator(null, global);
    assertTrue(cond.evaluate("${rows.size} > 1"));
    assertFalse(cond.evaluate("empty(rows)"));
    evaluator.rows = List.of();
    ((VariableStore.LazyValue) global.get("rows")).invalidate();
    assertTrue(cond.evaluate("empty(rows)"));
  }

  @Test
  void aToMapVariableIsReadAsItsMapAndAOneRowQueryStaysRows() throws Exception {
    // What toMap() returns: one row, holding the map. jfr-shell stores that as a MapValue, so
    // ${gc.size} is the entry count; it must be here too, while a one-row result of any other
    // query (same shape!) keeps counting rows.
    Map<String, Object> map = new java.util.LinkedHashMap<>();
    map.put("t1", 10L);
    map.put("t2", 20L);
    evaluator.rows = List.of(map);
    var ref = sessions.open(Path.of("/tmp/x.fake"), null);
    VariableStore global = new VariableStore();
    global.set(
        "gc",
        new EvaluatorLazyValue(
            evaluator, evaluator.parse("things | toMap(name, n)"), "gc", ref, null));
    global.set(
        "one",
        new EvaluatorLazyValue(evaluator, evaluator.parse("things | top(1)"), "one", ref, null));

    VariableSubstitutor sub = new VariableSubstitutor(null, global);
    assertEquals("2", sub.substitute("${gc.size}"));
    assertEquals("20", sub.substitute("${gc.t2}"));
    assertEquals("{t1=10, t2=20}", sub.substitute("${gc}"));
    assertEquals(
        sub.substitute("${gc.size} ${gc.t1}"),
        new VariableSubstitutor(null, mapStore("gc", map)).substitute("${gc.size} ${gc.t1}"),
        "same answer as the MapValue jfr-shell stores for this query");
    assertTrue(new ConditionEvaluator(null, global).evaluate("${gc.size} == 2"));

    ConditionEvaluator cond = new ConditionEvaluator(null, global);
    assertFalse(cond.evaluate("empty(gc)"), "a toMap() variable with entries is not empty");
    assertFalse(cond.evaluate("empty(one)"));

    assertEquals("1", sub.substitute("${one.size}"), "a one-row query still counts rows");
    assertEquals("10", sub.substitute("${one}"), "and ${v} is still the first field");
    assertEquals("20", sub.substitute("${one.t2}"));
  }

  @Test
  void emptyIsTrueOnlyForAMapWithoutEntries() throws Exception {
    // empty() used to answer true for every map variable, so 'if !empty(cfg)' never ran
    VariableStore global = new VariableStore();
    global.set("cfg", new VariableStore.MapValue(Map.of("k", 1L)));
    global.set("none", new VariableStore.MapValue(Map.of()));
    var ref = sessions.open(Path.of("/tmp/x.fake"), null);
    evaluator.rows = List.of(Map.of()); // what toMap() gives for no matching rows: one empty map
    global.set(
        "emptyGc",
        new EvaluatorLazyValue(
            evaluator, evaluator.parse("things | toMap(name, n)"), "emptyGc", ref, null));

    ConditionEvaluator cond = new ConditionEvaluator(null, global);
    assertFalse(cond.evaluate("empty(cfg)"));
    assertTrue(cond.evaluate("!empty(cfg)"));
    assertTrue(cond.evaluate("empty(none)"));
    assertTrue(cond.evaluate("empty(emptyGc)"), "a toMap() of no rows is an empty map");
  }

  private static VariableStore mapStore(String name, Map<String, Object> map) {
    VariableStore store = new VariableStore();
    store.set(name, new VariableStore.MapValue(map));
    return store;
  }

  @Test
  void scopesPreferTheSessionAndUnsetFallsBackToTheOtherStore() {
    VariableStore global = new VariableStore();
    VariableStore session = new VariableStore();
    VariableStore[] current = {null};
    VariableScopes scopes = new VariableScopes(global, () -> current[0]);

    assertSame(global, scopes.target(false), "no session: global");
    current[0] = session;
    assertSame(session, scopes.target(false));
    assertSame(global, scopes.target(true));

    global.set("x", new VariableStore.ScalarValue(1L));
    session.set("x", new VariableStore.ScalarValue(2L));
    assertEquals(new VariableStore.ScalarValue(2L), scopes.resolve("x"));
    assertEquals("session", scopes.scopeOf("x"));

    global.set("g", new VariableStore.ScalarValue(1L));
    assertTrue(scopes.remove("g", false), "not in the session: removed from global");
    assertFalse(global.contains("g"));
  }

  @Test
  void conditionalsSkipInactiveBranchesAndKeepNestingBalanced() {
    VariableStore global = new VariableStore();
    global.set("n", new VariableStore.ScalarValue(5L));
    VariableScopes scopes = new VariableScopes(global, () -> null);
    Conditionals c = new Conditionals();

    assertTrue(c.handle("if ${n} > 10", scopes));
    assertFalse(c.isActive());
    assertTrue(c.handle("if ${undefined} > 1", scopes)); // nested, not evaluated
    assertTrue(c.handle("endif", scopes));
    assertTrue(c.handle("elif ${n} == 5", scopes));
    assertTrue(c.isActive());
    assertTrue(c.handle("else", scopes));
    assertFalse(c.isActive());
    assertTrue(c.handle("endif", scopes));
    assertTrue(c.isActive());
    assertFalse(c.handle("iffy", scopes));

    // A failing condition is an error, not false: no branch of the block runs, else included
    ConditionalException failed =
        assertThrows(ConditionalException.class, () -> c.handle("if ${nope} > 1", scopes));
    assertTrue(
        failed.getMessage().startsWith("Condition evaluation failed: "), failed.getMessage());
    assertFalse(c.isActive());
    assertTrue(c.handle("else", scopes));
    assertFalse(c.isActive(), "the else of a failed condition does not run");
    assertTrue(c.handle("endif", scopes));
    assertTrue(c.isActive());
    ConditionalException stray =
        assertThrows(ConditionalException.class, () -> new Conditionals().handle("endif", scopes));
    assertEquals("endif without if", stray.getMessage());
  }

  @Test
  void elifIsNotEvaluatedWhenItsBranchCannotRun() throws Exception {
    VariableStore global = new VariableStore();
    global.set("rows", lazy(null));
    VariableScopes scopes = new VariableScopes(global, () -> null);
    Conditionals c = new Conditionals();

    // A taken branch: later elifs are irrelevant
    c.handle("if 1 == 1", scopes);
    c.handle("elif ${rows.size} > 0", scopes);
    c.handle("elif ${undefined} > 0", scopes);
    c.handle("endif", scopes);
    // Under a skipped block: the whole nested block is irrelevant
    c.handle("if 1 == 2", scopes);
    c.handle("if ${rows.size} > 0", scopes);
    c.handle("elif ${undefined} > 0", scopes);
    c.handle("endif", scopes);
    c.handle("endif", scopes);

    assertEquals(0, evaluator.evaluations, "no lazy query run for a branch that cannot execute");

    // Still evaluated when it can be taken
    c.handle("if 1 == 2", scopes);
    c.handle("elif ${rows.size} == 2", scopes);
    assertTrue(c.isActive());
    assertEquals(1, evaluator.evaluations);
  }

  @Test
  void keywordsAreWholeWordsInAnyCase() {
    VariableScopes scopes = new VariableScopes(new VariableStore(), () -> null);
    Conditionals c = new Conditionals();
    assertFalse(c.handle("ifx 1", scopes));
    assertFalse(c.handle("endiff", scopes));
    assertFalse(c.handle("show if", scopes));
    assertTrue(c.handle("  IF\t1 == 2", scopes));
    assertFalse(c.isActive());
    assertTrue(c.handle("If 1 == 1", scopes));
    assertEquals(2, c.state().depth());
  }

  @Test
  void mapLiteralAndMerge() {
    Map<String, Object> m = MapLiteralParser.parse("{\"a\": 1, \"b\": {\"c\": \"x\"}, \"d\": 1.5}");
    assertEquals(1L, m.get("a"));
    assertEquals(Map.of("c", "x"), m.get("b"));
    assertEquals(1.5, m.get("d"));
    assertThrows(IllegalArgumentException.class, () -> MapLiteralParser.parse("{a: 1}"));

    VariableStore store = new VariableStore();
    store.set("base", new VariableStore.MapValue(Map.of("a", 1L, "b", 2L)));
    Map<String, Object> merged = MapMerge.evaluate("merge(${base}, {\"b\": 3})", store::get);
    assertEquals(Map.of("a", 1L, "b", 3L), merged);
    assertTrue(MapMerge.isMergeCall(" merge(${a}, ${b}) "));
    assertThrows(
        IllegalArgumentException.class, () -> MapMerge.evaluate("merge(${base})", store::get));
  }
}
