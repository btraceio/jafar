package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Transport-level integration tests for JFR tools not covered by {@link
 * McpTransportIntegrationTest}. Each test exercises the full JSON-RPC over stdio path using
 * in-process pipes.
 */
@Tag("integration")
class McpJfrTransportTest extends McpTransportTestBase {

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrHelpOverviewReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "jfr_help", "{}");
    assertSuccess(resp, 1);
  }

  @Test
  void jfrHelpTopicReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "jfr_help", "{\"topic\":\"filters\"}");
    assertSuccess(resp, 1);
    assertTrue(resp.at("/result/content/0/text").asText().length() > 0);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrQueryCountReturnsResult() throws Exception {
    openJfr(1);
    JsonNode resp =
        harness.callTool(2, "jfr_query", "{\"query\":\"events/jdk.ExecutionSample | count()\"}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_list_types
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrListTypesReturnsEventList() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_list_types", "{}");
    assertSuccess(resp, 2);
    String text = resp.at("/result/content/0/text").asText();
    assertTrue(text.contains("jdk.ExecutionSample"), "must list jdk.ExecutionSample");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrCloseSessionSucceeds() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_close", "{\"closeAll\":true}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrFlamegraphReturnsData() throws Exception {
    openJfr(1);
    JsonNode resp =
        harness.callTool(2, "jfr_flamegraph", "{\"eventType\":\"jdk.ExecutionSample\"}");
    assertNotNull(resp, "jfr_flamegraph must return a response");
    assertFalse(resp.has("error"), "must not be a JSON-RPC error");
    // Success or graceful error — either way transport must deliver a result
    assertNotNull(resp.get("result"), "must have a result field");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_callgraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrCallgraphReturnsData() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_callgraph", "{\"eventType\":\"jdk.ExecutionSample\"}");
    assertNotNull(resp, "jfr_callgraph must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_exceptions
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrExceptionsReturnsData() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_exceptions", "{}");
    assertNotNull(resp, "jfr_exceptions must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrHotmethodsReturnsData() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_hotmethods", "{}");
    assertNotNull(resp, "jfr_hotmethods must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrUseReturnsReport() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_use", "{}");
    assertNotNull(resp, "jfr_use must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_tsa
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrTsaReturnsReport() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_tsa", "{}");
    assertNotNull(resp, "jfr_tsa must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_diagnose
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrDiagnoseReturnsReport() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_diagnose", "{}");
    assertNotNull(resp, "jfr_diagnose must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_stackprofile
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void jfrStackprofileReturnsData() throws Exception {
    openJfr(1);
    JsonNode resp = harness.callTool(2, "jfr_stackprofile", "{}");
    assertNotNull(resp, "jfr_stackprofile must return a response");
    assertFalse(resp.has("error"));
    assertNotNull(resp.get("result"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private void openJfr(int id) throws Exception {
    String path = getComprehensiveJfr().replace("\\", "\\\\");
    JsonNode resp = harness.callTool(id, "jfr_open", "{\"path\":\"" + path + "\"}");
    assertNotNull(resp, "jfr_open must respond");
    assertFalse(resp.has("error"), "jfr_open must not return a JSON-RPC error");
    assertFalse(resp.at("/result/isError").asBoolean(), "jfr_open must succeed");
  }
}
