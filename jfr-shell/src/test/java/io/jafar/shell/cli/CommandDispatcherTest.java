package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommandDispatcherTest {

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();

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
      out.append(s).append('\n');
    }

    String text() {
      return out.toString();
    }

    void clear() {
      out.setLength(0);
    }
  }

  ParsingContext ctx;
  SessionManager<JFRSession> sm;
  BufferIO io;
  CommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    ctx = ParsingContext.create();
    SessionManager.SessionFactory<JFRSession> factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getFilePath()).thenReturn(path);
          when(s.getType()).thenReturn("jfr");
          when(s.getAvailableTypes()).thenReturn(java.util.Set.of());
          when(s.getStatistics()).thenReturn(java.util.Map.of());
          when(s.getHandlerCount()).thenReturn(0);
          when(s.hasRun()).thenReturn(false);
          return s;
        };
    sm = new SessionManager<>(factory, ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sm, io, r -> {});
  }

  @Test
  void openSessionsInfoCloseFlow() {
    boolean handled = dispatcher.dispatch("open /tmp/one.jfr --alias one");
    assertTrue(handled);
    assertEquals(1, sm.list().size());
    assertTrue(io.text().contains("Opened session #1 (one)"));

    io.clear();
    dispatcher.dispatch("sessions");
    assertTrue(io.text().contains("*#1 one"));

    io.clear();
    dispatcher.dispatch("info");
    assertTrue(io.text().contains("Session Information:"));
    assertTrue(io.text().contains("File: /tmp/one.jfr"));

    io.clear();
    dispatcher.dispatch("close");
    assertTrue(io.text().contains("Closed session #1") || io.text().contains("Closed session 1"));
    assertTrue(sm.list().isEmpty());
  }

  @Test
  void jfrCommandsReturnFalseForNonJfrSession() throws Exception {
    // Create a dispatcher with non-JFR sessions
    Session nonJfr = Mockito.mock(Session.class);
    when(nonJfr.getFilePath()).thenReturn(Path.of("/tmp/test.hprof"));
    when(nonJfr.getType()).thenReturn("hdump");
    when(nonJfr.getAvailableTypes()).thenReturn(java.util.Set.of());
    when(nonJfr.getStatistics()).thenReturn(java.util.Map.of());

    SessionManager<Session> genericSm = new SessionManager<>((path, c) -> nonJfr, null);
    BufferIO genericIo = new BufferIO();
    CommandDispatcher genericDispatcher = new CommandDispatcher(genericSm, genericIo, r -> {});

    genericSm.open(Path.of("/tmp/test.hprof"), null);

    // JFR-specific commands should return false
    assertFalse(genericDispatcher.dispatch("events"));
    assertFalse(genericDispatcher.dispatch("events/jdk.ExecutionSample"));
    assertFalse(genericDispatcher.dispatch("metadata"));
    assertFalse(genericDispatcher.dispatch("metadata/jdk.types.Method"));
    assertFalse(genericDispatcher.dispatch("types"));
    assertFalse(genericDispatcher.dispatch("chunks"));
    assertFalse(genericDispatcher.dispatch("chunk"));
    assertFalse(genericDispatcher.dispatch("constants"));
    assertFalse(genericDispatcher.dispatch("cp"));
    assertFalse(genericDispatcher.dispatch("constants/jdk.types.Symbol"));
    assertFalse(genericDispatcher.dispatch("backend"));

    // show is always handled (returns true) — produces error when no session/evaluator
    assertTrue(genericDispatcher.dispatch("show events/jdk.ExecutionSample"));
    assertTrue(genericIo.text().contains("No session"));
  }

  @Test
  void genericCommandsWorkForNonJfrSession() throws Exception {
    Session nonJfr = Mockito.mock(Session.class);
    when(nonJfr.getFilePath()).thenReturn(Path.of("/tmp/test.hprof"));
    when(nonJfr.getType()).thenReturn("hdump");
    when(nonJfr.getAvailableTypes()).thenReturn(java.util.Set.of());
    when(nonJfr.getStatistics()).thenReturn(java.util.Map.of());

    SessionManager<Session> genericSm = new SessionManager<>((path, c) -> nonJfr, null);
    BufferIO genericIo = new BufferIO();
    CommandDispatcher genericDispatcher = new CommandDispatcher(genericSm, genericIo, r -> {});

    genericSm.open(Path.of("/tmp/test.hprof"), null);

    // Generic commands should return true
    assertTrue(genericDispatcher.dispatch("sessions"));
    assertTrue(genericDispatcher.dispatch("info"));
    assertTrue(genericDispatcher.dispatch("help"));
    assertTrue(genericDispatcher.dispatch("vars"));
    assertTrue(genericDispatcher.dispatch("echo hello"));
  }

  @Test
  void showDelegatesToModuleEvaluatorForNonJfrSession() throws Exception {
    Session nonJfr = Mockito.mock(Session.class);
    when(nonJfr.getFilePath()).thenReturn(Path.of("/tmp/test.hprof"));
    when(nonJfr.getType()).thenReturn("hdump");
    when(nonJfr.getAvailableTypes()).thenReturn(java.util.Set.of());
    when(nonJfr.getStatistics()).thenReturn(java.util.Map.of());

    SessionManager<Session> genericSm = new SessionManager<>((path, c) -> nonJfr, null);
    BufferIO genericIo = new BufferIO();
    CommandDispatcher genericDispatcher = new CommandDispatcher(genericSm, genericIo, r -> {});

    genericSm.open(Path.of("/tmp/test.hprof"), null);

    // Without module evaluator, show is handled but produces error
    assertTrue(genericDispatcher.dispatch("show objects/java.lang.String"));
    assertTrue(genericIo.text().contains("No session"));
    genericIo.clear();

    // Set a mock module evaluator
    io.jafar.shell.core.QueryEvaluator mockEval =
        Mockito.mock(io.jafar.shell.core.QueryEvaluator.class);
    try {
      when(mockEval.parse(anyString())).thenReturn("parsed");
      when(mockEval.evaluate(any(), any())).thenReturn(java.util.List.of());
    } catch (Exception e) {
      fail("Unexpected exception setting up mock: " + e.getMessage());
    }
    genericDispatcher.setModuleEvaluator(mockEval);

    // With module evaluator, show returns true
    assertTrue(genericDispatcher.dispatch("show objects/java.lang.String"));
  }
}
