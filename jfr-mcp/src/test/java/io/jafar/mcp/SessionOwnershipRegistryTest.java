package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.RequestScope;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-client ownership in the registries behind the MCP tools, exercised on the shared {@link
 * SamplingSessionRegistry} (pprof and OTLP use it unchanged). One registry serves every client of
 * an SSE daemon, so one client must not be able to close, list, reach or collide with another's
 * sessions. Clients are simulated by switching {@link RequestScope}.
 */
class SessionOwnershipRegistryTest {

  @TempDir Path tempDir;

  private PprofSessionRegistry registry;
  private Path profile;

  @BeforeEach
  void setUp() throws Exception {
    registry = new PprofSessionRegistry();
    RequestScope.clear();
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    long fn = b.addFunction(b.addString("main"), b.addString("Main.java"));
    long loc = b.addLocation(fn, 1);
    b.addSample(List.of(loc), List.of(1_000_000L), List.of());
    profile = b.write(tempDir);
  }

  @AfterEach
  void tearDown() {
    RequestScope.clear();
    registry.shutdown();
  }

  private void as(String scope) {
    RequestScope.set(scope);
  }

  @Test
  void twoClientsCanOpenSessionsUnderTheSameAlias() throws Exception {
    as("A");
    var a = registry.open(profile, "cpu");
    as("B");
    var b = registry.open(profile, "cpu");

    assertEquals(b.id(), registry.getOrCurrent("cpu").id());
    as("A");
    assertEquals(a.id(), registry.getOrCurrent("cpu").id());
  }

  @Test
  void aClientCannotReuseItsOwnAlias() throws Exception {
    as("A");
    registry.open(profile, "cpu");

    var e = assertThrows(IllegalArgumentException.class, () -> registry.open(profile, "cpu"));

    assertTrue(e.getMessage().contains("Alias already in use: cpu"));
    assertEquals(1, registry.size(), "the failed open must not leave a session behind");
  }

  @Test
  void anotherClientsSessionCannotBeReachedOrListed() throws Exception {
    as("A");
    var a = registry.open(profile, null);

    as("B");
    assertTrue(registry.get(String.valueOf(a.id())).isEmpty(), "B must not reach A's id");
    assertTrue(registry.list().isEmpty(), "B must not see A's session in a listing");
    assertFalse(registry.close(String.valueOf(a.id())), "B must not be able to close A's session");

    as("A");
    assertEquals(1, registry.list().size());
    assertEquals(a.id(), registry.getOrCurrent(null).id(), "A's session is untouched");
  }

  @Test
  void closeAllClosesOnlyTheCallersOwnSessions() throws Exception {
    as("A");
    registry.open(profile, null);
    registry.open(profile, null);
    as("B");
    var b = registry.open(profile, null);

    as("A");
    assertEquals(2, registry.closeAll());

    as("B");
    assertEquals(b.id(), registry.getOrCurrent(null).id(), "B's session survives A's closeAll");
    assertEquals(1, registry.list().size());
  }

  @Test
  void anUnscopedCallerIsTheServerAndSeesAndClosesEverything() throws Exception {
    as("A");
    registry.open(profile, null);
    as("B");
    registry.open(profile, null);

    RequestScope.clear();
    assertEquals(2, registry.list().size());
    assertEquals(2, registry.closeAll());
  }

  @Test
  void sessionsOfClientsThatDisconnectedAreReleased() throws Exception {
    as("A");
    registry.open(profile, null);
    as("B");
    registry.open(profile, "keep");

    registry.retainScopes(Set.of("B"));

    assertEquals(1, registry.size(), "A's session is closed when A is no longer connected");
    assertEquals("keep", registry.getOrCurrent("keep").alias());
  }

  @Test
  void openingASessionDoesNotHoldTheRegistryLock() throws Exception {
    CountDownLatch building = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SamplingSessionRegistry<PprofSession> slow =
        new SamplingSessionRegistry<>() {
          @Override
          protected PprofSession openSession(Path path) throws IOException {
            building.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return PprofSession.open(path);
          }

          @Override
          protected String formatName() {
            return "pprof";
          }
        };

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> opening =
          pool.submit(
              () -> {
                RequestScope.set("A");
                try {
                  return slow.open(profile, null);
                } finally {
                  RequestScope.clear();
                }
              });
      assertTrue(building.await(10, TimeUnit.SECONDS), "the slow open never started");

      // While a client is parsing a big file, another client's lookups must not wait for it.
      Future<?> listing = Executors.newSingleThreadExecutor().submit(slow::list);
      listing.get(3, TimeUnit.SECONDS);

      release.countDown();
      opening.get(10, TimeUnit.SECONDS);
      assertEquals(1, slow.size());
    } finally {
      release.countDown();
      pool.shutdownNow();
      slow.shutdown();
    }
  }

  @Test
  void twoOpensRacingForOneAliasLeaveExactlyOneSession() throws Exception {
    CountDownLatch bothBuilding = new CountDownLatch(2);
    SamplingSessionRegistry<PprofSession> racing =
        new SamplingSessionRegistry<>() {
          @Override
          protected PprofSession openSession(Path path) throws IOException {
            bothBuilding.countDown();
            try {
              bothBuilding.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return PprofSession.open(path);
          }

          @Override
          protected String formatName() {
            return "pprof";
          }
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> results =
          List.of(pool.submit(() -> openAs(racing, "A")), pool.submit(() -> openAs(racing, "A")));
      int failed = 0;
      for (Future<Object> f : results) {
        if (f.get(15, TimeUnit.SECONDS) instanceof IllegalArgumentException) {
          failed++;
        }
      }
      assertEquals(1, failed, "exactly one of the racing opens must lose");
      assertEquals(1, racing.size(), "the loser's session must be closed, not left registered");
    } finally {
      pool.shutdownNow();
      racing.shutdown();
    }
  }

  private Object openAs(SamplingSessionRegistry<PprofSession> r, String scope) {
    RequestScope.set(scope);
    try {
      return r.open(profile, "same");
    } catch (Exception e) {
      return e;
    } finally {
      RequestScope.clear();
    }
  }
}
