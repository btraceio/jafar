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
  }

  private void publish(FakeSseDaemon d, String token, String tokenPerms) throws IOException {
    Files.writeString(state.resolve("mcp-sse.port"), String.valueOf(d.port()));
    if (token != null) {
      Path tokenFile = state.resolve("mcp-sse.token");
      Files.writeString(tokenFile, token);
      Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString(tokenPerms));
    }
  }

  private DaemonLocator locator(DaemonLocator.Launcher launcher) {
    return new DaemonLocator(state, launcher, Duration.ofSeconds(3), Duration.ofMillis(25));
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
        new DaemonLocator(state, () -> {}, Duration.ofMillis(300), Duration.ofMillis(25));

    IOException e = assertThrows(IOException.class, impatient::ensureRunning);

    assertTrue(e.getMessage().contains("mcp-sse.err.log"), e.getMessage());
  }
}
