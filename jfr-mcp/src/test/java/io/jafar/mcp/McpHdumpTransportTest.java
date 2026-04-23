package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Transport-level integration tests for all hdump_* tools. Exercises the full JSON-RPC over stdio
 * path using in-process pipes.
 *
 * <p>No real heap dump file is required — tests cover error handling and stateless tools.
 */
@Tag("integration")
class McpHdumpTransportTest extends McpTransportTestBase {

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpHelpOverviewReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_help", "{}");
    assertSuccess(resp, 1);
    assertTrue(resp.at("/result/content/0/text").asText().length() > 0);
  }

  @Test
  void hdumpHelpTopicReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_help", "{\"topic\":\"operators\"}");
    assertSuccess(resp, 1);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_open — error path (no real hprof file)
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpOpenWithBadPathReturnsError() throws Exception {
    JsonNode resp =
        harness.callTool(
            1, "hdump_open", "{\"path\":\"/nonexistent-" + System.nanoTime() + ".hprof\"}");
    assertNotNull(resp, "hdump_open must return a response");
    assertFalse(resp.has("error"), "must be a result, not a JSON-RPC error");
    assertTrue(resp.at("/result/isError").asBoolean(), "bad path must produce isError=true");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_query — error path (no open session)
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpQueryWithNoSessionReturnsError() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_query", "{\"query\":\"objects | count\"}");
    assertNotNull(resp, "hdump_query must return a response");
    assertFalse(resp.has("error"));
    assertTrue(
        resp.at("/result/isError").asBoolean(), "no-session query must produce isError=true");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_summary — error path (no open session)
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpSummaryWithNoSessionReturnsError() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_summary", "{}");
    assertNotNull(resp, "hdump_summary must return a response");
    assertFalse(resp.has("error"));
    assertTrue(resp.at("/result/isError").asBoolean());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_report — error path (no open session)
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpReportWithNoSessionReturnsError() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_report", "{}");
    assertNotNull(resp, "hdump_report must return a response");
    assertFalse(resp.has("error"));
    assertTrue(resp.at("/result/isError").asBoolean());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_close — closeAll=true with no sessions succeeds
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hdumpCloseAllWithNoSessionsSucceeds() throws Exception {
    JsonNode resp = harness.callTool(1, "hdump_close", "{\"closeAll\":true}");
    assertSuccess(resp, 1);
  }
}
