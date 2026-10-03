package io.jafar.mcp.bridge;

import io.jafar.mcp.lifecycle.SsePortRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import org.slf4j.helpers.NOPLogger;

/**
 * Finds the user's shared SSE daemon, starting it if nobody has.
 *
 * <p>A daemon is "running" when the port file names a port that answers {@code /mcp/health}; the
 * port file is the same one the daemon writes and {@link SsePortRegistry} reads, so there is one
 * definition of it. Starting is serialised with a file lock so that several clients opening at once
 * (the normal case when Claude Code starts a few agents) launch one daemon between them rather than
 * racing for the port.
 */
final class DaemonLocator implements StdioSseBridge.DaemonSupplier {

  /** Starts a detached daemon. Returns once it has been launched, not once it is ready. */
  @FunctionalInterface
  interface Launcher {
    void launch() throws IOException;
  }

  private static final Set<PosixFilePermission> NOT_FOR_OTHERS =
      Set.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  private final Path stateDir;
  private final Launcher launcher;
  private final Duration startTimeout;
  private final Duration pollInterval;

  DaemonLocator(Path stateDir, Launcher launcher, Duration startTimeout, Duration pollInterval) {
    this.stateDir = stateDir;
    this.launcher = launcher;
    this.startTimeout = startTimeout;
    this.pollInterval = pollInterval;
  }

  @Override
  public StdioSseBridge.DaemonEndpoint ensureRunning() throws IOException {
    StdioSseBridge.DaemonEndpoint running = probe();
    if (running != null) {
      return running;
    }

    Files.createDirectories(stateDir);
    long deadline = System.nanoTime() + startTimeout.toNanos();
    try (FileChannel channel =
            FileChannel.open(
                stateDir.resolve("mcp-sse.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock ignored = acquire(channel, deadline)) {
      // Someone holding the lock before us may have brought the daemon up.
      running = probe();
      if (running != null) {
        return running;
      }
      launcher.launch();
      while (System.nanoTime() < deadline) {
        running = probe();
        if (running != null) {
          return running;
        }
        sleep(pollInterval);
      }
    }
    throw new IOException(
        "the jfr-mcp daemon did not come up within "
            + startTimeout.toSeconds()
            + "s; see "
            + stateDir.resolve("mcp-sse.err.log"));
  }

  private FileLock acquire(FileChannel channel, long deadline) throws IOException {
    while (System.nanoTime() < deadline) {
      try {
        FileLock lock = channel.tryLock();
        if (lock != null) {
          return lock;
        }
      } catch (OverlappingFileLockException heldInThisJvm) {
        // another thread here holds it; wait like we would for another process
      }
      sleep(pollInterval);
    }
    throw new IOException(
        "could not take the daemon start lock " + stateDir.resolve("mcp-sse.lock"));
  }

  /**
   * Returns the daemon's endpoint if one is up, {@code null} if not, or throws if one is up but
   * unusable.
   */
  private StdioSseBridge.DaemonEndpoint probe() throws IOException {
    int port =
        new SsePortRegistry(stateDir.resolve("mcp-sse.port"), NOPLogger.NOP_LOGGER)
            .detectRunningServer();
    if (port <= 0) {
      return null;
    }
    String token = readToken();
    URI base = URI.create("http://127.0.0.1:" + port);
    int status = healthStatus(base, token);
    if (status == 200) {
      return new StdioSseBridge.DaemonEndpoint(base, token);
    }
    if (status == 401) {
      throw new IOException(
          "the daemon on port "
              + port
              + " rejected the bearer token in "
              + stateDir.resolve("mcp-sse.token")
              + " (HTTP 401): is the token file stale, or is another daemon using this port?");
    }
    if (status == 404) {
      // A daemon from before /mcp/health existed: it is up and speaks the same protocol.
      return new StdioSseBridge.DaemonEndpoint(base, token);
    }
    return null; // port open but not answering HTTP yet: still starting
  }

  private int healthStatus(URI base, String token) {
    // HttpURLConnection, not HttpClient: this runs on every attach, and building an HttpClient
    // initialises the TLS stack (about 300ms) for a plain-http loopback request.
    try {
      HttpURLConnection http =
          (HttpURLConnection) base.resolve("/mcp/health").toURL().openConnection();
      http.setConnectTimeout(2_000);
      http.setReadTimeout(3_000);
      http.setInstanceFollowRedirects(false);
      if (token != null) {
        http.setRequestProperty("Authorization", "Bearer " + token);
      }
      try {
        return http.getResponseCode();
      } finally {
        http.disconnect();
      }
    } catch (IOException e) {
      return -1;
    }
  }

  /** The bearer token, or {@code null} when the daemon runs without auth and wrote none. */
  private String readToken() throws IOException {
    Path file = stateDir.resolve("mcp-sse.token");
    if (!Files.exists(file)) {
      return null;
    }
    try {
      Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file);
      for (PosixFilePermission p : mode) {
        if (NOT_FOR_OTHERS.contains(p)) {
          throw new IOException(
              "refusing to use "
                  + file
                  + ": it is readable by other users (mode "
                  + java.nio.file.attribute.PosixFilePermissions.toString(mode)
                  + "); run chmod 600 on it");
        }
      }
    } catch (UnsupportedOperationException nonPosix) {
      // no permission bits to check on this filesystem
    }
    return Files.readString(file).trim();
  }

  private static void sleep(Duration d) throws IOException {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for the daemon", e);
    }
  }
}
