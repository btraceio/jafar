package io.jafar.mcp.bridge;

import io.jafar.mcp.lifecycle.SsePortRegistry;
import java.io.IOException;
import java.io.PrintStream;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

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
  private final String ownVersion;
  private final PrintStream diag;
  private boolean warned;

  /**
   * @param ownVersion this bridge's version, or {@code null} when unknown (an IDE or test run), in
   *     which case a running daemon is never second-guessed
   * @param diag where to say that a daemon of another version is being used
   */
  DaemonLocator(
      Path stateDir,
      Launcher launcher,
      Duration startTimeout,
      Duration pollInterval,
      String ownVersion,
      PrintStream diag) {
    this.stateDir = stateDir;
    this.launcher = launcher;
    this.startTimeout = startTimeout;
    this.pollInterval = pollInterval;
    this.ownVersion = ownVersion;
    this.diag = diag;
  }

  /**
   * What /mcp/health says; the whole thing is {@code null} for a daemon that has no such endpoint.
   */
  record Health(String version, int activeSessions, boolean autostarted) {}

  private record Running(StdioSseBridge.DaemonEndpoint endpoint, Health health) {}

  @Override
  public StdioSseBridge.DaemonEndpoint ensureRunning() throws IOException {
    Running running = probe();
    if (running != null && !shouldReplace(running)) {
      warnIfSkewed(running);
      return running.endpoint();
    }

    // Either nothing is running or what is running is stale and idle: both change what is running,
    // so both happen under the lock, one bridge at a time.
    Files.createDirectories(stateDir);
    long deadline = System.nanoTime() + startTimeout.toNanos();
    try (FileChannel channel =
            FileChannel.open(
                stateDir.resolve("mcp-sse.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock ignored = acquire(channel, deadline)) {
      // Someone holding the lock before us may have started or replaced it.
      running = probe();
      if (running != null) {
        if (!shouldReplace(running) || !stop(running)) {
          // Current, or a client connected since we looked and the daemon refused to stop.
          warnIfSkewed(running);
          return running.endpoint();
        }
        awaitGone(deadline);
      }
      launcher.launch();
      while (System.nanoTime() < deadline) {
        running = probe();
        if (running != null) {
          return running.endpoint();
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

  private static boolean known(String version) {
    return version != null && !version.isBlank() && !version.startsWith("unknown");
  }

  private boolean skewed(Running running) {
    return known(ownVersion)
        && running.health() != null
        && known(running.health().version())
        && !ownVersion.equals(running.health().version());
  }

  /**
   * Only a daemon that a bridge started, that has no client connected, and that is the wrong
   * version is worth replacing. A supervised one is its supervisor's to restart, and one with
   * clients would take their sessions with it.
   */
  private boolean shouldReplace(Running running) {
    return skewed(running)
        && running.health().autostarted()
        && running.health().activeSessions() == 0;
  }

  /** Asks the stale daemon to stop; false if it declined, e.g. because a client just connected. */
  private boolean stop(Running running) throws IOException {
    int status = post(running.endpoint(), "/mcp/shutdown");
    return status == 202;
  }

  private void awaitGone(long deadline) throws IOException {
    while (System.nanoTime() < deadline) {
      try {
        if (probe() == null) {
          return;
        }
      } catch (IOException stillShuttingDown) {
        // its token file goes before its port does, which probe() reports as a rejected token
      }
      sleep(pollInterval);
    }
    throw new IOException(
        "the out-of-date jfr-mcp daemon did not stop within "
            + startTimeout.toSeconds()
            + "s; see "
            + stateDir.resolve("mcp-sse.err.log"));
  }

  /** Says once per process why a daemon of another version (or an unknowable one) is in use. */
  private void warnIfSkewed(Running running) {
    if (warned || !known(ownVersion)) {
      return;
    }
    Health h = running.health();
    String message;
    if (h == null) {
      message =
          "the jfr-mcp daemon on port "
              + running.endpoint().base().getPort()
              + " predates /mcp/health, so its version cannot be verified against this bridge's "
              + ownVersion
              + "; restart it if it misbehaves";
    } else if (skewed(running)) {
      String why =
          !h.autostarted()
              ? "it was not started by a bridge, so restart it to upgrade"
              : h.activeSessions() > 0
                  ? h.activeSessions() + " client(s) are connected; it is replaced once they leave"
                  : "it could not be replaced just now";
      message =
          "using a jfr-mcp daemon of version "
              + h.version()
              + " although this bridge is "
              + ownVersion
              + ": "
              + why;
    } else {
      return;
    }
    warned = true;
    diag.println("jfr-mcp bridge: " + message);
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
   * Returns the running daemon if one is up, {@code null} if not, or throws if one is up but
   * unusable.
   */
  private Running probe() throws IOException {
    int port =
        new SsePortRegistry(stateDir.resolve("mcp-sse.port"), NOPLogger.NOP_LOGGER)
            .detectRunningServer();
    if (port <= 0) {
      return null;
    }
    String token = readToken();
    URI base = URI.create("http://127.0.0.1:" + port);
    StdioSseBridge.DaemonEndpoint endpoint = new StdioSseBridge.DaemonEndpoint(base, token);
    HealthReply reply = fetchHealth(endpoint);
    if (reply.status() == 200) {
      return new Running(endpoint, parseHealth(reply.body()));
    }
    if (reply.status() == 401) {
      throw new IOException(
          "the daemon on port "
              + port
              + " rejected the bearer token in "
              + stateDir.resolve("mcp-sse.token")
              + " (HTTP 401): is the token file stale, or is another daemon using this port?");
    }
    if (reply.status() == 404) {
      // A daemon from before /mcp/health existed: it is up and speaks the same protocol.
      return new Running(endpoint, null);
    }
    return null; // port open but not answering HTTP yet: still starting
  }

  private record HealthReply(int status, String body) {}

  private HealthReply fetchHealth(StdioSseBridge.DaemonEndpoint endpoint) {
    // HttpURLConnection, not HttpClient: this runs on every attach, and building an HttpClient
    // initialises the TLS stack (about 300ms) for a plain-http loopback request.
    try {
      HttpURLConnection http =
          (HttpURLConnection) endpoint.base().resolve("/mcp/health").toURL().openConnection();
      http.setConnectTimeout(2_000);
      http.setReadTimeout(3_000);
      http.setInstanceFollowRedirects(false);
      if (endpoint.token() != null) {
        http.setRequestProperty("Authorization", "Bearer " + endpoint.token());
      }
      try {
        int status = http.getResponseCode();
        String body = status == 200 ? new String(http.getInputStream().readAllBytes()) : "";
        return new HealthReply(status, body);
      } finally {
        http.disconnect();
      }
    } catch (IOException e) {
      return new HealthReply(-1, "");
    }
  }

  /** The fields the policy needs; a reply it cannot read counts as an unknown version. */
  static Health parseHealth(String json) {
    String version = null;
    int sessions = -1;
    boolean autostarted = false;
    try (JsonParser p = new JsonFactory().createParser(json)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return new Health(null, -1, false);
      }
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = p.currentName();
        JsonToken value = p.nextToken();
        switch (name) {
          case "version" -> version = value == JsonToken.VALUE_STRING ? p.getString() : null;
          case "activeSessions" ->
              sessions = value == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : -1;
          case "autostarted" -> autostarted = value == JsonToken.VALUE_TRUE;
          default -> p.skipChildren();
        }
      }
    } catch (JacksonException e) {
      return new Health(null, -1, false);
    }
    return new Health(version, sessions, autostarted);
  }

  private int post(StdioSseBridge.DaemonEndpoint endpoint, String path) {
    try {
      HttpURLConnection http =
          (HttpURLConnection) endpoint.base().resolve(path).toURL().openConnection();
      http.setRequestMethod("POST");
      http.setConnectTimeout(2_000);
      http.setReadTimeout(5_000);
      http.setInstanceFollowRedirects(false);
      if (endpoint.token() != null) {
        http.setRequestProperty("Authorization", "Bearer " + endpoint.token());
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
