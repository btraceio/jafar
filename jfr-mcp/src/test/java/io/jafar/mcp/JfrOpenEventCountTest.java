package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opening a recording reads metadata only, so the event count is not known yet. It must be reported
 * as unknown, never as a zero that {@code jfr_summary} then contradicts.
 */
class JfrOpenEventCountTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int EVENTS = 5;

  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    Path recording = SimpleJfrFileBuilder.createExecutionSampleFile(EVENTS);
    invoke("handleJfrOpen", Map.of("path", recording.toString()));
  }

  @AfterEach
  void tearDown() throws Exception {
    invoke("handleJfrClose", Map.of("closeAll", true));
  }

  @Test
  void openReportsTheCountAsUnknownAndNamesWhereToGetIt() throws Exception {
    Path recording = SimpleJfrFileBuilder.createExecutionSampleFile(EVENTS);
    JsonNode opened = invoke("handleJfrOpen", Map.of("path", recording.toString()));

    assertTrue(opened.has("totalEvents"), "the key stays so clients can see it is unknown");
    assertTrue(opened.get("totalEvents").isNull(), "not counted yet, got: " + opened);
    assertTrue(opened.get("totalEventsNote").asString().contains("jfr_summary"), opened.toString());

    Method summary =
        JafarMcpServer.class.getDeclaredMethod(
            "handleJfrSummary", McpSyncServerExchange.class, Map.class, Object.class);
    summary.setAccessible(true);
    long counted =
        parse((CallToolResult) summary.invoke(server, null, Map.of(), null))
            .get("totalEvents")
            .asLong();
    assertEquals(EVENTS, counted, "the count the open could not know");
  }

  @Test
  void listTypesWithoutScanReportsTheCountAsUnknown() throws Exception {
    JsonNode listed = invoke("handleJfrListTypes", Map.of());

    assertFalse(listed.get("scanned").asBoolean());
    assertTrue(
        listed.get("totalEvents").isNull(), "not scanned, got: " + listed.get("totalEvents"));
    assertTrue(listed.get("countsNote").asString().contains("scan=true"), listed.toString());
  }

  @Test
  void listTypesWithScanReportsTheRealCount() throws Exception {
    JsonNode listed = invoke("handleJfrListTypes", Map.of("scan", true));

    assertTrue(listed.get("scanned").asBoolean());
    assertEquals(EVENTS, listed.get("totalEvents").asLong());
    assertFalse(listed.has("countsNote"), "the counts are real, nothing to caveat");
  }

  private JsonNode invoke(String handler, Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(handler, Map.class);
    method.setAccessible(true);
    CallToolResult result = (CallToolResult) method.invoke(server, args);
    assertFalse(result.isError(), handler + " failed: " + result.content());
    return parse(result);
  }

  private static JsonNode parse(CallToolResult result) {
    return MAPPER.readTree(((TextContent) result.content().get(0)).text());
  }
}
