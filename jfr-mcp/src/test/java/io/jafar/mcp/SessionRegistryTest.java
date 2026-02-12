package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.mcp.session.SessionRegistry;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for SessionRegistry session management. */
class SessionRegistryTest {

  private SessionRegistry registry;
  private static final Path TEST_JFR = Paths.get(System.getProperty("user.dir") + "/parser/src/test/resources/test-jfr.jfr");

  @BeforeEach
  void setUp() {
    registry = new SessionRegistry();
  }

  @Test
  void openCreatesNewSession() throws Exception {
    SessionRegistry.SessionInfo session = registry.open(TEST_JFR, null);

    assertNotNull(session);
    assertNotNull(session.id());
    assertNotNull(session.session());
    assertEquals(TEST_JFR, session.recordingPath());
  }

  @Test
  void openWithAliasCreatesNamedSession() throws Exception {
    SessionRegistry.SessionInfo session = registry.open(TEST_JFR, "test-alias");

    assertNotNull(session);
    assertEquals(TEST_JFR, session.recordingPath());
  }

  @Test
  void getOrCurrentReturnsCurrentSession() throws Exception {
    SessionRegistry.SessionInfo session1 = registry.open(TEST_JFR, null);

    SessionRegistry.SessionInfo current = registry.getOrCurrent(null);

    assertEquals(session1.id(), current.id());
  }

  @Test
  void getOrCurrentThrowsWhenNoCurrentSession() {
    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent(null));
  }

  @Test
  void getOrCurrentFindsSessionById() throws Exception {
    SessionRegistry.SessionInfo session1 = registry.open(TEST_JFR, null);

    SessionRegistry.SessionInfo found = registry.getOrCurrent(String.valueOf(session1.id()));

    assertEquals(session1.id(), found.id());
  }

  @Test
  void getOrCurrentFindsSessionByAlias() throws Exception {
    registry.open(TEST_JFR, "my-alias");

    SessionRegistry.SessionInfo found = registry.getOrCurrent("my-alias");

    assertNotNull(found);
  }

  @Test
  void getOrCurrentThrowsForUnknownSession() {
    assertThrows(
        IllegalArgumentException.class, () -> registry.getOrCurrent("nonexistent"));
  }

  @Test
  void closeRemovesSession() throws Exception {
    SessionRegistry.SessionInfo session = registry.open(TEST_JFR, null);
    String sessionId = String.valueOf(session.id());

    registry.close(sessionId);

    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent(sessionId));
  }

  @Test
  void closeByAliasRemovesSession() throws Exception {
    registry.open(TEST_JFR, "test-alias");

    registry.close("test-alias");

    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent("test-alias"));
  }

  @Test
  void closeThrowsForUnknownSession() {
    assertThrows(IllegalArgumentException.class, () -> registry.close("nonexistent"));
  }

  @Test
  void closeAllRemovesAllSessions() throws Exception {
    registry.open(TEST_JFR, "alias1");
    registry.open(TEST_JFR, "alias2");

    registry.closeAll();

    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent(null));
  }

  @Test
  void getAllSessionsReturnsAllActiveSessions() throws Exception {
    registry.open(TEST_JFR, "session1");
    registry.open(TEST_JFR, "session2");

    var sessions = registry.list();

    assertEquals(2, sessions.size());
  }

  @Test
  void multipleSessionsCoexist() throws Exception {
    SessionRegistry.SessionInfo session1 = registry.open(TEST_JFR, "first");
    SessionRegistry.SessionInfo session2 = registry.open(TEST_JFR, "second");

    assertNotEquals(session1.id(), session2.id());

    SessionRegistry.SessionInfo found1 = registry.getOrCurrent("first");
    SessionRegistry.SessionInfo found2 = registry.getOrCurrent("second");

    assertEquals(session1.id(), found1.id());
    assertEquals(session2.id(), found2.id());
  }

  @Test
  void sessionIdIsUnique() throws Exception {
    SessionRegistry.SessionInfo session1 = registry.open(TEST_JFR, null);
    SessionRegistry.SessionInfo session2 = registry.open(TEST_JFR, null);

    assertNotEquals(session1.id(), session2.id());
  }
}
