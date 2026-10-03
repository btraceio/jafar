package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.RequestScope;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-client ownership in the JFR session registry. One registry serves every client of an SSE
 * daemon, so one client must not be able to close, list, reach or collide with another's sessions.
 * Clients are simulated by switching {@link RequestScope}.
 */
class SessionRegistryOwnershipTest extends BaseJfrTest {

  private SessionRegistry registry;
  private Path recording;

  @BeforeEach
  void setUp() {
    registry = new SessionRegistry();
    RequestScope.clear();
    recording = Paths.get(getComprehensiveJfr());
  }

  @AfterEach
  void tearDown() throws Exception {
    RequestScope.clear();
    registry.shutdown();
  }

  private void as(String scope) {
    RequestScope.set(scope);
  }

  @Test
  void twoClientsCanOpenSessionsUnderTheSameAlias() throws Exception {
    as("A");
    var a = registry.open(recording, "cpu");
    as("B");
    var b = registry.open(recording, "cpu");

    assertEquals(b.id(), registry.getOrCurrent("cpu").id());
    as("A");
    assertEquals(a.id(), registry.getOrCurrent("cpu").id());
  }

  @Test
  void aClientCannotReuseItsOwnAlias() throws Exception {
    as("A");
    registry.open(recording, "cpu");
    int before = registry.size(); // not 1: a registry restores whatever the shared store holds

    var e = assertThrows(IllegalArgumentException.class, () -> registry.open(recording, "cpu"));

    assertTrue(e.getMessage().contains("Alias already in use: cpu"));
    assertEquals(before, registry.size(), "the failed open must not leave a session behind");
  }

  @Test
  void anotherClientsSessionCannotBeReachedListedOrClosed() throws Exception {
    as("A");
    var a = registry.open(recording, null);

    as("B");
    assertTrue(registry.get(String.valueOf(a.id())).isEmpty(), "B must not reach A's id");
    assertTrue(registry.list().isEmpty(), "B must not see A's session in a listing");
    assertFalse(registry.close(String.valueOf(a.id())), "B must not close A's session");

    as("A");
    assertEquals(a.id(), registry.getOrCurrent(null).id(), "A's session is untouched");
  }

  @Test
  void closeAllClosesOnlyTheCallersOwnSessionsAndReportsHowMany() throws Exception {
    as("A");
    registry.open(recording, null);
    registry.open(recording, null);
    as("B");
    var b = registry.open(recording, null);

    as("A");
    assertEquals(2, registry.closeAll());

    as("B");
    assertEquals(b.id(), registry.getOrCurrent(null).id(), "B survives A's closeAll");
  }

  @Test
  void sessionsOfClientsThatDisconnectedAreReleased() throws Exception {
    as("A");
    registry.open(recording, null);
    as("B");
    registry.open(recording, "keep");
    int before = registry.size();

    registry.retainScopes(Set.of("B"));

    assertEquals(before - 1, registry.size());
    assertEquals("keep", registry.getOrCurrent("keep").alias());
  }

  @Test
  void openingASessionDoesNotHoldTheRegistryLock() throws Exception {
    CountDownLatch building = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SessionRegistry slow =
        new SessionRegistry(
            (path, context) -> {
              building.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new JFRSession(path, context);
            });

    int before = slow.size(); // a registry restores whatever the shared store holds
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> opening =
          pool.submit(
              () -> {
                RequestScope.set("A");
                try {
                  return slow.open(recording, null);
                } finally {
                  RequestScope.clear();
                }
              });
      assertTrue(building.await(10, TimeUnit.SECONDS), "the slow open never started");

      // While one client is parsing a big recording, another client's lookups must not wait.
      pool.submit(slow::list).get(3, TimeUnit.SECONDS);

      release.countDown();
      opening.get(15, TimeUnit.SECONDS);
      assertEquals(before + 1, slow.size());
    } finally {
      release.countDown();
      pool.shutdownNow();
      slow.shutdown();
    }
  }

  @Test
  void aClientsCloseAllAlsoClearsSharedSessionsRestoredAfterARestart() throws Exception {
    // A session opened and never closed stays in the persistence store, as after a crash.
    var orphan = registry.open(recording, null);
    SessionRegistry afterRestart = new SessionRegistry();
    assertTrue(
        afterRestart.get(String.valueOf(orphan.id())).isPresent(),
        "the new registry should have restored the session");

    as("A");
    var own = afterRestart.open(recording, null);
    afterRestart.closeAll();

    assertTrue(afterRestart.get(String.valueOf(own.id())).isEmpty());
    assertTrue(
        afterRestart.get(String.valueOf(orphan.id())).isEmpty(),
        "a restored session is visible in listings, so closeAll must clear it too");
    RequestScope.clear();
    registry.shutdown(); // closes the original handle as well, leaving the store clean
  }
}
