package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Unit tests for {@link SseAuthToken}. */
class SseAuthTokenTest {

  private Path tokenFile;
  private SseAuthToken auth;

  @BeforeEach
  void setUp() throws Exception {
    tokenFile = Files.createTempDirectory("jafar-auth-test").resolve("mcp-sse.token");
    auth = new SseAuthToken(tokenFile, LoggerFactory.getLogger(SseAuthTokenTest.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    Files.deleteIfExists(tokenFile);
    Files.deleteIfExists(tokenFile.getParent());
  }

  @Test
  void generateAndWriteCreatesAReadableTokenFile() {
    String token = auth.generateAndWrite();

    assertTrue(Files.exists(tokenFile));
    assertFalse(token.isBlank());
  }

  @Test
  void writtenTokenFileContentsMatchReturnedToken() throws Exception {
    String token = auth.generateAndWrite();

    String onDisk = Files.readString(tokenFile);
    assertEquals(token, onDisk);
  }

  @Test
  void successiveTokensAreDifferent() {
    String first = auth.generateAndWrite();
    String second = auth.generateAndWrite();

    assertNotEquals(first, second);
  }

  @Test
  void tokenFileIsOwnerOnlyOnPosixFilesystems() throws Exception {
    auth.generateAndWrite();

    var view =
        Files.getFileAttributeView(tokenFile, java.nio.file.attribute.PosixFileAttributeView.class);
    if (view == null) {
      return; // non-POSIX filesystem — permission restriction is best-effort only.
    }
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFile);
    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
  }

  @Test
  void deleteRemovesTheTokenFile() {
    auth.generateAndWrite();
    assertTrue(Files.exists(tokenFile));

    auth.delete();

    assertFalse(Files.exists(tokenFile));
  }

  @Test
  void deleteOnMissingFileDoesNotThrow() {
    assertFalse(Files.exists(tokenFile));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> auth.delete());
  }

  @Test
  void pathReturnsConfiguredLocation() {
    assertEquals(tokenFile, auth.path());
  }
}
