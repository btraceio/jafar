package io.jafar.mcp.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Generates and persists the bearer token required to call the SSE daemon's HTTP endpoints.
 *
 * <p>The daemon has no other authentication, so anything reachable on its port could otherwise read
 * or manipulate open JFR/heap-dump/pprof/otlp sessions. The token is a fresh random value per
 * daemon startup, written to a file readable only by the owning user (best-effort — not all
 * filesystems support POSIX permissions), alongside the port marker file.
 */
public final class SseAuthToken {

  private static final int TOKEN_BYTES = 32;

  private final Path tokenFile;
  private final Logger logger;

  public SseAuthToken(Path tokenFile, Logger logger) {
    this.tokenFile = tokenFile;
    this.logger = logger;
  }

  public static SseAuthToken defaultToken(Logger logger) {
    return new SseAuthToken(StateDir.resolve().resolve("mcp-sse.token"), logger);
  }

  public Path path() {
    return tokenFile;
  }

  /** Generates a fresh random token and writes it to {@link #path()}, owner-readable only. */
  public String generateAndWrite() {
    byte[] bytes = new byte[TOKEN_BYTES];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    try {
      Files.createDirectories(tokenFile.getParent());
      Files.writeString(tokenFile, token, StandardCharsets.US_ASCII);
      restrictToOwner();
    } catch (IOException e) {
      logger.warn("Cannot write SSE auth token file: {}", e.getMessage());
    }
    return token;
  }

  private void restrictToOwner() {
    try {
      Files.setPosixFilePermissions(
          tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException e) {
      // Non-POSIX filesystem (e.g. some Windows setups) — best effort only.
      logger.debug("Could not restrict SSE auth token file permissions: {}", e.getMessage());
    }
  }

  public void delete() {
    try {
      Files.deleteIfExists(tokenFile);
    } catch (IOException e) {
      logger.warn("Cannot delete SSE auth token file: {}", e.getMessage());
    }
  }
}
