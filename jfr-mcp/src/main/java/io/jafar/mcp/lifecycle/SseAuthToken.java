package io.jafar.mcp.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
    return new SseAuthToken(
        Path.of(System.getProperty("user.home"), ".jafar", "mcp-sse.token"), logger);
  }

  public Path path() {
    return tokenFile;
  }

  /** Returns a fresh random token. Nothing is written; see {@link #write(String)}. */
  public static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /**
   * Writes {@code token} to {@link #path()}, replacing any previous token.
   *
   * <p>The secret goes into a temporary file that is owner-read/write from the moment it is
   * created, which is then renamed over {@link #path()}, so the token is never readable by other
   * users, even briefly, and a reader never sees a partially written file. On filesystems without
   * POSIX permissions the file gets the default permissions.
   */
  public void write(String token) {
    Path tmp = null;
    try {
      Path dir = tokenFile.toAbsolutePath().getParent();
      Files.createDirectories(dir);
      tmp = createOwnerOnlyTempFile(dir);
      Files.writeString(tmp, token, StandardCharsets.US_ASCII);
      Files.move(
          tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      tmp = null;
    } catch (IOException e) {
      logger.warn("Cannot write SSE auth token file: {}", e.getMessage());
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
  }

  private Path createOwnerOnlyTempFile(Path dir) throws IOException {
    String prefix = "." + tokenFile.getFileName();
    try {
      return Files.createTempFile(
          dir,
          prefix,
          ".tmp",
          PosixFilePermissions.asFileAttribute(
              Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
    } catch (UnsupportedOperationException e) {
      logger.debug("POSIX permissions unsupported; SSE auth token file uses default permissions");
      return Files.createTempFile(dir, prefix, ".tmp");
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
