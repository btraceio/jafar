package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.core.RequestScope;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for per-caller "current session" isolation (see {@link RequestScope}).
 *
 * <p>An SSE daemon serves several concurrent MCP client connections through one {@code
 * JafarMcpServer} instance, and thus one {@link SessionRegistry}. Without isolation, one client's
 * {@code jfr_open} silently changed which session another client's session-less tool calls (e.g.
 * {@code jfr_query} without a {@code sessionId}) resolved to. These tests simulate two concurrent
 * clients by switching {@link RequestScope} between calls.
 */
class SessionRegistryClientIsolationTest extends BaseJfrTest {

  private SessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SessionRegistry();
    RequestScope.clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    registry.shutdown(); // closeAll() is per-client now; the test cleanup is the server itself
    RequestScope.clear();
  }

  @Test
  void eachScopeGetsItsOwnCurrentSession() throws Exception {
    RequestScope.set("clientA");
    SessionRegistry.SessionInfo sessionA = registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientB");
    SessionRegistry.SessionInfo sessionB = registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientA");
    assertEquals(
        sessionA.id(),
        registry.getOrCurrent(null).id(),
        "clientA's session-less lookup must resolve to its own session, not clientB's");

    RequestScope.set("clientB");
    assertEquals(
        sessionB.id(),
        registry.getOrCurrent(null).id(),
        "clientB's session-less lookup must resolve to its own session, not clientA's");
  }

  @Test
  void openingASessionAsOneClientDoesNotChangeAnotherClientsCurrent() throws Exception {
    RequestScope.set("clientA");
    SessionRegistry.SessionInfo sessionA = registry.open(Paths.get(getComprehensiveJfr()), null);

    // clientB opens a session AFTER clientA — this must not steal clientA's "current" pointer.
    RequestScope.set("clientB");
    registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientA");
    assertEquals(sessionA.id(), registry.getOrCurrent(null).id());
  }

  @Test
  void unscopedCallerSeesNoCurrentSessionFromScopedClients() throws Exception {
    RequestScope.set("clientA");
    registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.clear();
    assertTrue(
        registry.getCurrent().isEmpty(),
        "a caller with no scope must not inherit clientA's current session");
  }

  @Test
  void closingAScopesCurrentSessionDoesNotAffectAnotherScope() throws Exception {
    RequestScope.set("clientA");
    SessionRegistry.SessionInfo sessionA = registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientB");
    SessionRegistry.SessionInfo sessionB = registry.open(Paths.get(getComprehensiveJfr()), null);
    registry.close(String.valueOf(sessionB.id()));

    RequestScope.set("clientA");
    assertEquals(
        sessionA.id(),
        registry.getOrCurrent(null).id(),
        "closing clientB's current session must not disturb clientA's current session");
  }

  @Test
  void closingOwnCurrentSessionDoesNotFallBackToAnotherScopesSession() throws Exception {
    RequestScope.set("clientA");
    SessionRegistry.SessionInfo sessionA = registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientB");
    registry.open(Paths.get(getComprehensiveJfr()), null);

    RequestScope.set("clientA");
    registry.close(String.valueOf(sessionA.id()));

    assertTrue(
        registry.getCurrent().isEmpty(),
        "clientA must not inherit clientB's session after closing its own");
  }
}
