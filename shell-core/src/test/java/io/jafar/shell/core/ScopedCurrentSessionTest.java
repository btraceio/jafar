package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScopedCurrentSessionTest {

  private final ScopedCurrentSession current = new ScopedCurrentSession();

  @AfterEach
  void clearScope() {
    RequestScope.clear();
  }

  @Test
  void restoredSessionIsFallbackForAnyScope() {
    current.restored(7);

    RequestScope.set("clientA");
    assertEquals(7, current.current());
    RequestScope.clear();
    assertEquals(7, current.current());
  }

  @Test
  void ownSessionTakesPrecedenceOverRestored() {
    current.restored(7);
    RequestScope.set("clientA");
    current.opened(1);

    assertEquals(1, current.current());
  }

  @Test
  void closingOwnSessionNeverFallsBackToAnotherClientsSession() {
    RequestScope.set("clientA");
    current.opened(1);
    RequestScope.set("clientB");
    current.opened(2);

    current.closed(1);

    RequestScope.set("clientA");
    assertNull(current.current());
    RequestScope.set("clientB");
    assertEquals(2, current.current());
  }

  @Test
  void closingOwnSessionFallsBackToOwnPreviousSession() {
    RequestScope.set("clientA");
    current.opened(1);
    current.opened(2);

    current.closed(2);

    assertEquals(1, current.current());
  }

  @Test
  void disconnectedClientsSessionsBecomeFallbackForReconnectingClient() {
    current.opened(9);
    RequestScope.set("gone");
    current.opened(1);
    RequestScope.set("live");
    current.opened(2);

    current.retainScopes(Set.of("live"));

    assertEquals(2, current.current(), "a connected client keeps its own session");
    RequestScope.set("reconnected");
    assertEquals(1, current.current(), "a reconnecting client falls back to the orphaned session");
    RequestScope.clear();
    assertEquals(9, current.current(), "the unscoped caller keeps its own session");
  }

  @Test
  void closingAnOrphanedSessionRemovesItAsFallback() {
    RequestScope.set("gone");
    current.opened(1);
    current.retainScopes(Set.of());

    current.closed(1);

    RequestScope.set("reconnected");
    assertNull(current.current());
  }
}
