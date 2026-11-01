package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.scripting.EvaluatorLazyValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShellVariablesTest {
  private final VariableStore store = new VariableStore();
  private final ShellVariables vars = new ShellVariables(store);

  @Test
  void settingKeepsDottedNameAndTextValue() throws Exception {
    vars.set("llm.backend = ollama");
    vars.set("LLM.max-rows = 20");

    assertEquals("ollama", store.get("llm.backend").get());
    // Text, not 20L or 20.0: the config reader parses it as an int.
    assertEquals("20", store.get("llm.max-rows").get());
  }

  @Test
  void unknownSettingListsTheRealOnes() {
    var e = assertThrows(IllegalArgumentException.class, () -> vars.set("llm.backed = x"));
    assertTrue(e.getMessage().contains("llm.backend"), e.getMessage());
  }

  @Test
  void rejectsBadNamesAndMissingEquals() {
    assertThrows(IllegalArgumentException.class, () -> vars.set("a.b = 1"));
    assertThrows(IllegalArgumentException.class, () -> vars.set("novalue"));
    // No session: a bare word is neither a variable nor a query anyone can run
    var e = assertThrows(IllegalArgumentException.class, () -> vars.set("x = bareword"));
    assertTrue(e.getMessage().contains("No session open"), e.getMessage());
  }

  @Test
  void plainVariablesAreTypedAndSubstituted() throws Exception {
    vars.set("n = 42");
    vars.set("s = \"hi\"");
    vars.set("copy = n");

    assertEquals(42L, store.get("n").get());
    assertEquals(42L, store.get("copy").get());
    assertEquals(List.of("hi 42"), vars.echo("${s} ${n}"));
    vars.set("llm.model = m-${n}");
    assertEquals("m-42", store.get("llm.model").get());
  }

  @Test
  void unsetAndVars() {
    vars.set("a = 1");
    assertEquals(List.of("Global variables:", "  a = 1"), vars.vars(""));
    vars.unset("a");
    assertEquals(List.of("No variables defined"), vars.vars(""));
    assertThrows(IllegalArgumentException.class, () -> vars.unset("a"));
  }

  @Test
  void mapLiteralsMergeAndFieldAccess() throws Exception {
    vars.set("cfg = {\"db\": {\"host\": \"h\", \"port\": 5432}, \"on\": true}");
    vars.set("over = merge(${cfg}, {\"on\": false, \"extra\": 1})");

    assertInstanceOf(VariableStore.MapValue.class, store.get("cfg"));
    assertEquals(List.of("h:5432 2"), vars.echo("${cfg.db.host}:${cfg.db.port} ${cfg.size}"));
    assertEquals(List.of("false 1"), vars.echo("${over.on} ${over.extra}"));
    var e = assertThrows(IllegalArgumentException.class, () -> vars.set("bad = {oops}"));
    assertTrue(e.getMessage().startsWith("Invalid map literal"), e.getMessage());
    e = assertThrows(IllegalArgumentException.class, () -> vars.set("m = merge(${cfg}, ${nope})"));
    assertTrue(e.getMessage().contains("Variable not found: nope"), e.getMessage());
    assertTrue(String.join("\n", vars.vars("--info cfg")).contains("Type: map"));
  }

  // ---- with sessions and a module evaluator ----

  /** A session of a non-JFR type: the lazy variable must not depend on JfrPath. */
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

  static final class FakeEvaluator implements QueryEvaluator {
    final List<String> parsed = new ArrayList<>();
    int evaluations;

    @Override
    public Object parse(String q) {
      if (!q.startsWith("things")) {
        throw new QueryParseException("Unknown root: " + q, q, 0);
      }
      parsed.add(q);
      return q;
    }

    @Override
    public Object evaluate(Session session, Object query) {
      evaluations++;
      if (String.valueOf(query).contains("toMap")) {
        return List.of(Map.of("k", "v", "n", 2L)); // what toMap() yields: one row, the map
      }
      Map<String, Object> a = new java.util.LinkedHashMap<>();
      a.put("name", "a");
      a.put("n", 3L);
      Map<String, Object> b = new java.util.LinkedHashMap<>();
      b.put("name", "b");
      b.put("n", 1L);
      return List.of(a, b);
    }

    @Override
    public List<String> getRootTypes() {
      return List.of("things");
    }

    @Override
    public List<String> getOperators() {
      return List.of();
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
  private final SessionManager<Session> sessions =
      new SessionManager<>((p, c) -> new FakeSession(), null);
  private final ShellVariables withSessions =
      new ShellVariables(
          store,
          new ShellVariables.QueryContext() {
            @Override
            public Optional<SessionManager.SessionRef<Session>> currentSession() {
              return sessions.getCurrent();
            }

            @Override
            public QueryEvaluator evaluatorFor(Session session) {
              return evaluator;
            }

            @Override
            public SessionResolver resolver() {
              return id -> Optional.empty();
            }
          });

  private void open() {
    try {
      sessions.open(Path.of("/tmp/x.fake"), null);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void queryIsParsedAtSetAndRunOnFirstUse() throws Exception {
    sessions.open(Path.of("/tmp/x.fake"), null);
    var out = withSessions.set("hot = things[n > 0]");

    assertEquals(List.of("Set hot = lazy[things[n > 0]] (not evaluated)"), out);
    assertEquals(List.of("things[n > 0]"), evaluator.parsed);
    assertEquals(0, evaluator.evaluations, "lazy: not run at set");
    assertEquals(
        List.of("2 rows, top a=3"), withSessions.echo("${hot.size} rows, top ${hot}=${hot[0].n}"));
    withSessions.echo("${hot.size}");
    assertEquals(1, evaluator.evaluations, "cached after first use");

    withSessions.invalidate("hot");
    withSessions.echo("${hot.size}");
    assertEquals(2, evaluator.evaluations, "invalidate re-runs the query");

    String info = String.join("\n", withSessions.vars("--info hot"));
    assertTrue(info.contains("Type: lazy query"), info);
    assertTrue(info.contains("Session: #1 (fake)"), info);
    assertTrue(info.contains("Rows: 2"), info);
  }

  @Test
  void bareWordNamingNoVariableIsAQueryAndParseErrorsAreReported() {
    open();
    withSessions.set("all = things");
    assertInstanceOf(EvaluatorLazyValue.class, sessions.getCurrent().get().variables.get("all"));

    var e = assertThrows(IllegalArgumentException.class, () -> withSessions.set("x = bogus"));
    assertTrue(e.getMessage().startsWith("Invalid query: Unknown root: bogus"), e.getMessage());
    e = assertThrows(IllegalArgumentException.class, () -> withSessions.set("x = $bogus"));
    assertEquals("Variable not found: bogus", e.getMessage());
  }

  @Test
  void sessionScopeByDefaultGlobalOnRequest() throws Exception {
    withSessions.set("before = 1"); // no session yet: global
    sessions.open(Path.of("/tmp/x.fake"), null);
    var ref = sessions.getCurrent().get();
    withSessions.set("local = 2");
    withSessions.set("--global shared = 3");
    withSessions.set("llm.max-rows = 7"); // a setting follows the same scoping

    assertTrue(store.contains("before"));
    assertTrue(store.contains("shared"));
    assertTrue(ref.variables.contains("local"));
    assertEquals("7", ref.variables.get("llm.max-rows").get());
    assertFalse(store.contains("local"));
    assertEquals(
        List.of(
            "Global variables:",
            "  before = 1",
            "  shared = 3",
            "Session variables (session #1):",
            "  llm.max-rows = \"7\"",
            "  local = 2"),
        withSessions.vars(""));
    assertEquals(
        List.of("Global variables:", "  before = 1", "  shared = 3"),
        withSessions.vars("--global"));

    // unset tries the session first, then global
    withSessions.unset("before");
    assertFalse(store.contains("before"));

    sessions.closeAll();
    assertTrue(ref.variables.isEmpty(), "closing a session clears its variables");
    assertEquals(List.of("3"), withSessions.echo("${shared}"));
  }

  @Test
  void mergeTakesALazyVariableWhoseRowsAreOneMap() throws Exception {
    open();
    withSessions.set("settings = things | toMap(name, value)");
    withSessions.set("rows = things");
    withSessions.set("all = merge(${settings}, {\"extra\": 1, \"n\": 3})");

    assertEquals(
        Map.of("k", "v", "n", 3L, "extra", 1L),
        sessions.getCurrent().get().variables.get("all").get());
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> withSessions.set("x = merge(${rows}, {})"));
    assertTrue(e.getMessage().contains("Variable is not a map: rows"), e.getMessage());
  }

  @Test
  void copyOfALazyVariableHasItsOwnCache() throws Exception {
    sessions.open(Path.of("/tmp/x.fake"), null);
    withSessions.set("--global a = things");
    withSessions.echo("${a.size}");
    withSessions.set("--global b = a");
    var a = (EvaluatorLazyValue) store.get("a");
    var b = (EvaluatorLazyValue) store.get("b");
    assertTrue(b.isCached());
    b.invalidate();
    assertTrue(a.isCached(), "invalidating the copy leaves the original cached");
    assertThrows(IllegalArgumentException.class, () -> withSessions.invalidate("missing"));
    withSessions.set("--global s = 1");
    var e = assertThrows(IllegalArgumentException.class, () -> withSessions.invalidate("s"));
    assertTrue(e.getMessage().contains("not a lazy value"), e.getMessage());

    sessions.closeAll();
    var closed =
        assertThrows(IllegalArgumentException.class, () -> withSessions.set("--global c = a"));
    assertTrue(
        closed.getMessage().startsWith("Cannot copy a: Session #1 has been closed"),
        closed.getMessage());
  }

  /** The key is stored as typed but never printed: not by 'set', 'vars' or 'vars --info'. */
  @Test
  void apiKeyIsStoredButNeverPrinted() throws Exception {
    List<String> out = new java.util.ArrayList<>(vars.set("llm.api-key = sk-proj-0123456789-TAIL"));
    out.addAll(vars.vars(""));
    out.addAll(vars.vars("--info llm.api-key"));

    assertEquals("sk-proj-0123456789-TAIL", store.get("llm.api-key").get());
    String text = String.join("\n", out);
    assertFalse(text.contains("sk-proj"), text);
    assertTrue(text.contains("Set llm.api-key = <hidden, ends in \"TAIL\">"), text);
    assertTrue(text.contains("  llm.api-key = <hidden, ends in \"TAIL\">"), text);
    assertTrue(text.contains("Value: <hidden, ends in \"TAIL\">"), text);
  }
}
