package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
import io.jafar.shell.core.scripting.EvaluatorLazyValue;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code set x = <query>} on a non-JFR session through {@code CommandDispatcher}: the path the TUI
 * ({@code jafar-shell --tui}) takes for a heap dump, pprof or OTLP file. It used to answer
 * "Variable assignment requires a JFR session".
 */
class ModuleLazyVariableTest {

  @Test
  void queryOnNonJfrSessionBecomesALazyVariableRunByTheModuleEvaluator() throws Exception {
    Session heap = mock(Session.class);
    when(heap.getType()).thenReturn("hdump");
    when(heap.getAvailableTypes()).thenReturn(Set.of());
    when(heap.getStatistics()).thenReturn(Map.of());
    SessionManager<Session> sessions = new SessionManager<>((p, c) -> heap, null);
    StringBuilder out = new StringBuilder();
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            sessions,
            new CommandDispatcher.IO() {
              @Override
              public void println(String s) {
                out.append(s).append('\n');
              }

              @Override
              public void printf(String fmt, Object... args) {
                out.append(String.format(fmt, args));
              }

              @Override
              public void error(String s) {
                out.append("ERR ").append(s).append('\n');
              }
            },
            r -> {});
    QueryEvaluator eval = mock(QueryEvaluator.class);
    when(eval.parse(anyString())).thenReturn("parsed");
    when(eval.evaluate(any(), any(), any(SessionResolver.class)))
        .thenReturn(List.of(Map.of("count", 42L)));
    dispatcher.setModuleEvaluator(eval);
    sessions.open(Path.of("/tmp/x.hprof"), null);

    assertTrue(dispatcher.dispatch("set n = objects | count"));
    assertFalse(out.toString().contains("ERR"), out.toString());
    assertInstanceOf(
        EvaluatorLazyValue.class, sessions.current().get().variables.get("n"), out.toString());

    assertTrue(dispatcher.dispatch("echo total=${n}"));
    assertTrue(out.toString().contains("total=42"), out.toString());
    assertTrue(dispatcher.dispatch("if ${n} > 40"));
    assertTrue(dispatcher.dispatch("echo big"));
    assertTrue(dispatcher.dispatch("endif"));
    assertTrue(out.toString().contains("big"), out.toString());
    verify(eval, times(1)).evaluate(any(), any(), any(SessionResolver.class));

    assertTrue(dispatcher.dispatch("invalidate n"));
    assertTrue(dispatcher.dispatch("echo again=${n}"));
    verify(eval, times(2)).evaluate(any(), any(), any(SessionResolver.class));
    assertEquals(-1, out.indexOf("requires a JFR session"));
  }
}
