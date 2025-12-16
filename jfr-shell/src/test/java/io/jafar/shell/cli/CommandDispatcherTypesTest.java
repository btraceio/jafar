package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommandDispatcherTypesTest {
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

  @Test
  void listsFiltersAndRefreshesTypes() throws Exception {
    // Mock session with types
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/one.jfr"));
    when(session.getAllMetadataTypes()).thenReturn(Set.of("jdk.A", "jdk.B", "custom.C"));
    when(session.getNonPrimitiveMetadataTypes()).thenReturn(Set.of("jdk.A", "jdk.B", "custom.C"));

    SessionManager sm = new SessionManager(ParsingContext.create(), (p, c) -> session);
    BufferIO io = new BufferIO();
    CommandDispatcher disp = new CommandDispatcher(sm, io, r -> {});

    disp.dispatch("open /tmp/one.jfr --alias one");
    io.clear();
    disp.dispatch("metadata");
    assertTrue(io.text().contains("jdk.A"));
    assertTrue(io.text().contains("jdk.B"));

    io.clear();
    disp.dispatch("metadata --search jdk.*");
    String out = io.text();
    assertTrue(out.contains("jdk.A"));
    assertTrue(out.contains("jdk.B"));
    assertFalse(out.contains("custom.C"));

    io.clear();
    disp.dispatch("metadata --regex .*B$");
    assertTrue(io.text().contains("jdk.B"));
    assertFalse(io.text().contains("jdk.A"));

    io.clear();
    disp.dispatch("metadata --refresh");
    verify(session, atLeastOnce()).refreshTypes();
  }

  @Test
  void typesPrintsAllMatches() throws Exception {
    // 12 types to exceed the sample size
    java.util.Set<String> many = new java.util.LinkedHashSet<>();
    for (int i = 1; i <= 12; i++) many.add("jdk.Type" + i);

    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/one.jfr"));
    when(session.getAllMetadataTypes()).thenReturn(many);
    when(session.getNonPrimitiveMetadataTypes()).thenReturn(many);

    SessionManager sm = new SessionManager(ParsingContext.create(), (p, c) -> session);
    BufferIO io = new BufferIO();
    CommandDispatcher disp = new CommandDispatcher(sm, io, r -> {});

    disp.dispatch("open /tmp/one.jfr --alias one");
    io.clear();
    disp.dispatch("metadata");
    String out = io.text();
    // ensure last type is printed and there is no '... and N more'
    assertTrue(out.contains("jdk.Type12"));
    assertFalse(out.contains("... and "));
  }
}
