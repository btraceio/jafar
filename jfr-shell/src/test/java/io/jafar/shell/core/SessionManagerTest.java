package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionManagerTest {

  private final ParsingContext ctx = ParsingContext.create();

  private SessionManager.JFRSessionFactory factory() {
    return (path, c) -> {
      JFRSession s = Mockito.mock(JFRSession.class);
      when(s.getRecordingPath()).thenReturn(path);
      return s;
    };
  }

  @Test
  void openAssignsIdsAliasesAndSwitchesCurrent() throws Exception {
    SessionManager sm = new SessionManager(ctx, factory());

    SessionManager.SessionRef s1 = sm.open(Path.of("/tmp/a.jfr"), "a1");
    SessionManager.SessionRef s2 = sm.open(Path.of("/tmp/b.jfr"), null);

    assertEquals(1, s1.id);
    assertEquals("a1", s1.alias);
    assertEquals(2, s2.id);
    assertTrue(sm.current().isPresent());
    assertEquals(2, sm.current().get().id);

    assertEquals(2, sm.list().size());
    assertEquals(1, sm.list().get(0).id);
    assertEquals(2, sm.list().get(1).id);

    assertTrue(sm.use("a1"));
    assertEquals(1, sm.current().get().id);

    assertTrue(sm.close("a1"));
    assertEquals(1, sm.list().size());
    assertEquals(2, sm.current().get().id);
  }

  @Test
  void rejectsDuplicateAlias() throws Exception {
    SessionManager sm = new SessionManager(ctx, factory());
    sm.open(Path.of("/x.jfr"), "dup");
    assertThrows(IllegalArgumentException.class, () -> sm.open(Path.of("/y.jfr"), "dup"));
  }
}
