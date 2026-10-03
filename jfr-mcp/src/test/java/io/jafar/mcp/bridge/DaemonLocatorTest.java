package io.jafar.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonLocatorTest {

  @TempDir Path state;

  private FakeSseDaemon daemon;

  @AfterEach
  void stop() throws Exception {
    if (daemon != null) {
      daemon.close();
    }
    for (FakeSseDaemon d : extraDaemons) {
      d.close();
    }
  }

  private void publish(FakeSseDaemon d, String token, String tokenPerms) throws IOException {
    Files.writeString(state.resolve("mcp-sse.port"), String.valueOf(d.port()));
    if (token != null) {
      Path tokenFile = state.resolve("mcp-sse.token");
      Files.writeString(tokenFile, token);
      Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString(tokenPerms));
    }
  }

  private final java.io.ByteArrayOutputStream diagBytes = new java.io.ByteArrayOutputStream();
  private final java.util.List<FakeSseDaemon> extraDaemons = new ArrayList<>();

  private String diag() {
    return diagBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private DaemonLocator locator(DaemonLocator.Launcher launcher) {
    return locator(launcher, null);
  }

  private DaemonLocator locator(DaemonLocator.Launcher launcher, String ownVersion) {
    return new DaemonLocator(
        state,
        launcher,
        Duration.ofSeconds(5),
        Duration.ofMillis(25),
        ownVersion,
        new java.io.PrintStream(diagBytes, true, java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Launcher that brings up a fresh fake daemon and publishes it, as the real one would. */
  private DaemonLocator.Launcher launching(FakeSseDaemon fresh, AtomicInteger launches) {
    return () -> {
      try {
        launches.incrementAndGet();
        extraDaemons.add(fresh.start(true));
        publish(fresh, FakeSseDaemon.TOKEN, "rw-------");
      } catch (Exception e) {
        throw new IOException(e);
      }
    };
  }

  @Test
  void attachesToARunningDaemonWithoutLaunchingAnother() throws Exception {
    daemon = new FakeSseDaemon().start(true);
    publish(daemon, FakeSseDaemon.TOKEN, "rw-------");
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint = locator(launches::incrementAndGet).ensureRunning();

    assertEquals(0, launches.get());
    assertEquals(daemon.port(), endpoint.base().getPort());
    assertEquals(FakeSseDaemon.TOKEN, endpoint.token());
  }

  @Test
  void launchesTheDaemonWhenNoneIsRunningAndWaitsForIt() throws Exception {
    daemon = new FakeSseDaemon().start(true);
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(
                () -> {
                  launches.incrementAndGet();
                  publish(daemon, FakeSseDaemon.TOKEN, "rw-------");
                })
            .ensureRunning();

    assertEquals(1, launches.get());
    assertEquals(daemon.port(), endpoint.base().getPort());
  }

  @Test
  void manyBridgesStartingAtOnceLaunchExactlyOneDaemon() throws Exception {
    daemon = new FakeSseDaemon().start(true);
    AtomicInteger launches = new AtomicInteger();
    DaemonLocator.Launcher launcher =
        () -> {
          launches.incrementAndGet();
          try {
            Thread.sleep(150); // a daemon takes a moment to come up
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          publish(daemon, FakeSseDaemon.TOKEN, "rw-------");
        };

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<StdioSseBridge.DaemonEndpoint>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(pool.submit(() -> locator(launcher).ensureRunning()));
      }
      for (Future<StdioSseBridge.DaemonEndpoint> f : results) {
        assertEquals(daemon.port(), f.get().base().getPort());
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, launches.get());
  }

  @Test
  void aStalePortFileIsNotMistakenForARunningDaemon() throws Exception {
    int deadPort;
    try (ServerSocket s = new ServerSocket(0)) {
      deadPort = s.getLocalPort();
    }
    Files.writeString(state.resolve("mcp-sse.port"), String.valueOf(deadPort));
    daemon = new FakeSseDaemon().start(true);
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(
                () -> {
                  launches.incrementAndGet();
                  publish(daemon, FakeSseDaemon.TOKEN, "rw-------");
                })
            .ensureRunning();

    assertEquals(1, launches.get());
    assertEquals(daemon.port(), endpoint.base().getPort());
  }

  @Test
  void refusesATokenFileOthersCanRead() throws Exception {
    daemon = new FakeSseDaemon().start(true);
    publish(daemon, FakeSseDaemon.TOKEN, "rw-r--r--");

    IOException e = assertThrows(IOException.class, () -> locator(() -> {}).ensureRunning());

    assertTrue(e.getMessage().contains("mcp-sse.token"), e.getMessage());
    assertTrue(e.getMessage().contains("readable"), e.getMessage());
  }

  @Test
  void attachesWithoutATokenWhenTheDaemonRunsWithoutAuth() throws Exception {
    daemon = new FakeSseDaemon().start(false);
    publish(daemon, null, null);

    StdioSseBridge.DaemonEndpoint endpoint = locator(() -> {}).ensureRunning();

    assertNull(endpoint.token());
  }

  @Test
  void aRejectedTokenIsReportedAsSuch() throws Exception {
    daemon = new FakeSseDaemon().start(true);
    publish(daemon, "not-the-daemons-token", "rw-------");

    IOException e = assertThrows(IOException.class, () -> locator(() -> {}).ensureRunning());

    assertTrue(e.getMessage().contains("401"), e.getMessage());
  }

  @Test
  void aDaemonThatNeverComesUpIsReportedWithWhereToLook() {
    DaemonLocator impatient =
        new DaemonLocator(
            state, () -> {}, Duration.ofMillis(300), Duration.ofMillis(25), null, System.err);

    IOException e = assertThrows(IOException.class, impatient::ensureRunning);

    assertTrue(e.getMessage().contains("mcp-sse.err.log"), e.getMessage());
  }

  // ───────────────────────────── version skew ─────────────────────────────

  private void runningOldDaemon(FakeSseDaemon old) throws Exception {
    daemon = old.start(true);
    publish(daemon, FakeSseDaemon.TOKEN, "rw-------");
  }

  @Test
  void aStaleIdleAutoStartedDaemonIsReplacedByOneOfTheBridgesVersion() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("1.0", true, 0));
    FakeSseDaemon fresh = new FakeSseDaemon().reporting("2.0", true, 0);
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(launching(fresh, launches), "2.0").ensureRunning();

    assertEquals(1, daemon.shutdownCalls(), "the stale daemon should have been asked to stop");
    assertEquals(1, launches.get());
    assertEquals(fresh.port(), endpoint.base().getPort(), "must end up on the new daemon");
  }

  @Test
  void aStaleDaemonWithClientsConnectedIsLeftAloneAndTheMismatchReported() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("1.0", true, 2));
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(launching(new FakeSseDaemon(), launches), "2.0").ensureRunning();

    assertEquals(0, daemon.shutdownCalls(), "must not stop a daemon other clients are using");
    assertEquals(0, launches.get());
    assertEquals(daemon.port(), endpoint.base().getPort());
    assertTrue(diag().contains("1.0") && diag().contains("2.0"), "stderr should say: " + diag());
  }

  @Test
  void aStaleDaemonThatWasNotAutoStartedIsNeverStopped() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("1.0", false, 0));
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(launching(new FakeSseDaemon(), launches), "2.0").ensureRunning();

    assertEquals(0, daemon.shutdownCalls(), "a supervised daemon is the supervisor's to restart");
    assertEquals(0, launches.get());
    assertEquals(daemon.port(), endpoint.base().getPort());
    assertTrue(diag().contains("1.0"), diag());
  }

  @Test
  void ifTheDaemonRefusesToStopTheBridgeUsesItAndSaysSo() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("1.0", true, 0).shutdownAccepted(false));
    AtomicInteger launches = new AtomicInteger();

    StdioSseBridge.DaemonEndpoint endpoint =
        locator(launching(new FakeSseDaemon(), launches), "2.0").ensureRunning();

    assertEquals(1, daemon.shutdownCalls());
    assertEquals(0, launches.get(), "a refusal means a client just connected: keep the daemon");
    assertEquals(daemon.port(), endpoint.base().getPort());
    assertTrue(diag().contains("1.0"), diag());
  }

  @Test
  void aMatchingVersionNeedsNoActionAndNoWarning() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("2.0", true, 0));

    locator(() -> {}, "2.0").ensureRunning();

    assertEquals(0, daemon.shutdownCalls());
    assertEquals("", diag());
  }

  @Test
  void aBridgeWhoseOwnVersionIsUnknownDoesNotSecondGuessTheDaemon() throws Exception {
    runningOldDaemon(new FakeSseDaemon().reporting("1.0", true, 0));

    locator(() -> {}, null).ensureRunning();

    assertEquals(0, daemon.shutdownCalls());
    assertEquals("", diag());
  }

  @Test
  void aDaemonFromBeforeTheHealthEndpointCannotBeVerifiedAndTheBridgeSaysSo() throws Exception {
    runningOldDaemon(new FakeSseDaemon().withoutHealthEndpoint());

    StdioSseBridge.DaemonEndpoint endpoint = locator(() -> {}, "2.0").ensureRunning();

    assertEquals(daemon.port(), endpoint.base().getPort(), "it still works, so attach");
    assertTrue(diag().contains("predates /mcp/health"), diag());
  }
}
