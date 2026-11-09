package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SessionManager#register(Session, String)} — the counterpart of {@code open} for
 * externally-created sessions (live JFR sessions attached to a running JVM). Pins the {@code
 * open}-mirroring contract: id allocation, alias conflicts, current-pointer semantics, and the
 * close-on-lost-race path.
 */
class SessionManagerRegisterTest {

  private static Session stubSession() {
    return new Session() {
      @Override
      public String getType() {
        return "stub";
      }

      @Override
      public Path getFilePath() {
        return Path.of("/stub");
      }

      @Override
      public boolean isClosed() {
        return false;
      }

      @Override
      public java.util.Set<String> getAvailableTypes() {
        return java.util.Set.of();
      }

      @Override
      public java.util.Map<String, Object> getStatistics() {
        return java.util.Map.of();
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void registerAssignsSequentialIdsAndBecomesCurrent() throws Exception {
    SessionManager<Session> sm = new SessionManager<>((path, c) -> stubSession(), null);
    sm.open(Path.of("/x.jfr"), null);
    SessionManager.SessionRef<Session> live = sm.register(stubSession(), "live");

    assertEquals(2, live.id);
    assertEquals("live", live.alias);
    assertTrue(sm.current().isPresent());
    assertEquals(live.id, sm.current().get().id);
    assertEquals(2, sm.list().size());
  }

  @Test
  void registerRejectsDuplicateAliasAndKeepsManagerIntact() throws Exception {
    SessionManager<Session> sm = new SessionManager<>((path, c) -> stubSession(), null);
    sm.open(Path.of("/x.jfr"), "dup");
    Session before = stubSession();

    assertThrows(IllegalArgumentException.class, () -> sm.register(before, "dup"));
    assertEquals(1, sm.list().size());
    // The pre-existing alias still resolves to the original session (id 1)
    assertTrue(sm.get("dup").isPresent());
    assertEquals(1, sm.get("dup").get().id);
    // The rejected session was not adopted: manager state unchanged, current still id 1
    assertEquals(1, sm.current().get().id);
  }

  @Test
  void registerWithoutAliasStillBecomesCurrent() throws Exception {
    SessionManager<Session> sm = new SessionManager<>((path, c) -> stubSession(), null);
    sm.open(Path.of("/x.jfr"), "alias");
    SessionManager.SessionRef<Session> ref = sm.register(stubSession(), null);

    assertTrue(sm.current().isPresent());
    assertEquals(ref.id, sm.current().get().id);
    assertTrue(sm.use("alias"));
    assertEquals(1, sm.current().get().id);
  }

  @Test
  void closeRegisteredSessionRemovesItAndRepointsCurrent() throws Exception {
    SessionManager<Session> sm = new SessionManager<>((path, c) -> stubSession(), null);
    SessionManager.SessionRef<Session> file = sm.open(Path.of("/x.jfr"), "file");
    sm.register(stubSession(), "live");

    assertTrue(sm.close("live"));
    assertEquals(1, sm.list().size());
    assertEquals(file.id, sm.current().get().id);
  }
}
