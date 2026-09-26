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
  void retainScopesDropsDisconnectedClientsOnly() {
    current.opened(9);
    RequestScope.set("gone");
    current.opened(1);
    RequestScope.set("live");
    current.opened(2);

    current.retainScopes(Set.of("live"));

    assertEquals(2, current.current());
    RequestScope.set("gone");
    assertNull(current.current());
    RequestScope.clear();
    assertEquals(9, current.current());
  }
}
