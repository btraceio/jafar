package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/** Regression coverage for the real local shadow-JAR stdio progress-token path. */
@Tag("e2e")
class McpEndToEndTest {

  @Test
  @Timeout(120)
  void serverSurvivesOpenQueryDiagnoseSequence() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("diagnose-progress")) {
      JsonNode open =
          mcp.call("jfr_open", Map.of("path", findTestJfr().toString(), "alias", "tier1-jfr"));
      assertNotNull(mcp.contentJson(open).path("sessionId"));

      JsonNode query =
          mcp.call(
              "jfr_query",
              Map.of("query", "events/jdk.JVMInformation | select(jvmVersion, jvmName)"));
      assertFalse(
          mcp.contentJson(query).path("results").isMissingNode(), () -> "query payload=" + query);

      JsonNode diagnose = mcp.call("jfr_diagnose", Map.of(), 4);
      assertFalse(mcp.contentJson(diagnose).isMissingNode(), () -> "diagnose payload=" + diagnose);
      mcp.assertProgress(4);
      mcp.call("jfr_close", Map.of("sessionId", "tier1-jfr"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  @Test
  @Timeout(120)
  void serverSurvivesUseMethodAfterOpen() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("use-progress")) {
      mcp.call("jfr_open", Map.of("path", findTestJfr().toString(), "alias", "tier1-use"));
      mcp.call("jfr_use", Map.of(), 3);
      mcp.assertProgress(3);
      mcp.call("jfr_close", Map.of("sessionId", "tier1-use"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  static Path findTestJfr() {
    String override = System.getProperty("mcp.e2e.jfr");
    if (override != null) {
      Path supplied = Path.of(override).toAbsolutePath().normalize();
      if (!Files.exists(supplied)) {
        throw new IllegalArgumentException("JFR file from mcp.e2e.jfr not found: " + supplied);
      }
      return supplied;
    }
    Path fixture = Paths.get("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!Files.exists(fixture)) {
      fixture = Paths.get("demo/src/test/resources/test-dd.jfr").normalize();
    }
    if (!Files.exists(fixture)) {
      throw new IllegalStateException("test-dd.jfr not found — ensure the demo module is present");
    }
    return fixture.toAbsolutePath();
  }
}
