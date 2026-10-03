package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class GuidanceContractSessionIsolationTest {

  private static final String STALE_ALIAS = "must-not-appear-in-guidance-contract";
  private static final String EMPTY_SESSIONS =
      """
      # Open sessions

      ## JFR recordings
      _none open_

      ## Heap dumps
      _none open_

      ## pprof profiles
      _none open_

      ## OTLP profiles
      _none open_

      A session id or alias can be passed as `sessionId` to any tool of the matching family, and named in cross-session operators such as `join(session=...)`.

      """;

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void renderingAndExportIgnoreStaleSessionState(@TempDir Path tempDir) throws Exception {
    Path stale = tempDir.resolve("stale-sessions.json");
    Files.writeString(
        stale, "[{\"id\":99,\"path\":\"/tmp/stale.jfr\",\"alias\":\"" + STALE_ALIAS + "\"}]");
    String originalSessions = System.getProperty("jafar.mcp.sessions.file");
    try {
      System.setProperty("jafar.mcp.sessions.file", stale.toString());
      Path contractRoot;
      String rendered;
      try (GuidanceContractContext context = GuidanceContractContext.create()) {
        contractRoot = context.root();
        assertTrue(context.sessionsFile().startsWith(contractRoot));
        assertTrue(context.home().startsWith(contractRoot));
        assertFalse(context.sessionsFile().equals(stale.toAbsolutePath().normalize()));
        rendered = new GuidanceSurfaces().resourceTexts().get("jafar://sessions");
        assertEquals(EMPTY_SESSIONS, rendered);
        assertFalse(rendered.contains(STALE_ALIAS));
      }
      assertFalse(Files.exists(contractRoot), "context must remove only its owned root");

      Path exported = tempDir.resolve("exported-contract.json");
      McpContractExport.main(new String[] {exported.toString()});
      String exportedJson = Files.readString(exported);
      String exportedSessions = "";
      for (var resource : McpContractJson.parse(exportedJson).path("resources")) {
        if ("jafar://sessions".equals(resource.path("uri").asString())) {
          exportedSessions = resource.path("renderedText").asString();
        }
      }
      assertEquals(EMPTY_SESSIONS, exportedSessions);
      assertFalse(exportedJson.contains(STALE_ALIAS));
      assertTrue(Files.exists(exported));
    } finally {
      if (originalSessions == null) {
        System.clearProperty("jafar.mcp.sessions.file");
      } else {
        System.setProperty("jafar.mcp.sessions.file", originalSessions);
      }
    }
  }
}
