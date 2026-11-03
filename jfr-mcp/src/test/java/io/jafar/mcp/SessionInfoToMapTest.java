package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.SessionRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code SessionInfo.toMap()} is the payload of {@code jfr_open}. It must report a chunk count it
 * could not compute instead of leaving the key out: the old {@code catch (Exception)} logged at
 * debug level and returned a map that simply had no {@code chunkCount}, indistinguishable from an
 * older server that never sent one.
 */
class SessionInfoToMapTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDir;
  private final SessionRegistry registry = new SessionRegistry();

  @AfterEach
  void closeAll() throws Exception {
    registry.closeAll();
  }

  @Test
  void reportsChunkCountOfAReadableRecording() throws Exception {
    Map<String, Object> map = registry.open(TCK_RECORDING, null).toMap();
    assertEquals(2, map.get("chunkCount"));
    assertEquals(204, map.get("availableTypes"));
    assertFalse(map.containsKey("chunkCountError"), () -> "unexpected error: " + map);
  }

  @Test
  void reportsWhyTheChunkCountIsMissing() throws Exception {
    Path copy = tempDir.resolve("rec.jfr");
    Files.copy(TCK_RECORDING, copy);
    SessionRegistry.SessionInfo info = registry.open(copy, null);

    // Chunks are listed lazily, on the first toMap(). Replace the file (a new inode, so anything
    // the open session has mapped stays valid) with one that is not a recording.
    Files.delete(copy);
    Files.writeString(copy, "<!DOCTYPE html>not a recording", StandardCharsets.US_ASCII);

    Map<String, Object> map = info.toMap();
    assertTrue(map.containsKey("chunkCount"), () -> "chunkCount must be present: " + map);
    assertNull(map.get("chunkCount"), () -> "chunkCount: " + map);
    String error = String.valueOf(map.get("chunkCountError"));
    assertTrue(error.contains(copy.toString()), () -> "error names the recording: " + error);
    // The exact string doc/mcp/Tutorial.md shows for this case.
    assertEquals(
        "Failed to list chunks of "
            + copy
            + ": Error occurred while parsing JFR recording: Invalid JFR Magic Number: 3c21444f",
        error);
    // Everything that did not depend on the failed scan is still reported.
    assertEquals(204, map.get("availableTypes"));
    assertEquals(copy.toString(), map.get("path"));
  }
}
