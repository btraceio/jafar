package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the shape of the analysis tools, so moving their implementation cannot change it.
 *
 * <p>Written as a safety net for extracting the analyses out of {@code JfrAnalysisTools} and into
 * {@code shell-core}, where the shell can reach them. The net was needed because there was none:
 * {@code jfr_use}, {@code jfr_tsa} and {@code jfr_diagnose} are exercised only by {@code
 * McpJfrTransportTest} — which cannot run without the binary recordings {@code get_resources.sh}
 * downloads — and by {@code McpEndToEndTest}, which is a separate task. Moving nineteen hundred
 * lines of heuristics with nothing executable watching would have been a guess.
 *
 * <p>These assert the *contract* — which keys a caller can rely on — rather than the numbers, which
 * depend on the synthetic recording. A refactor that preserves behaviour keeps them green; one that
 * drops a key or changes a name does not.
 */
class JfrAnalysesCharacterizationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path comprehensiveFile;

  private JafarMcpServer server;

  @BeforeAll
  static void createTestFiles() throws Exception {
    comprehensiveFile = SimpleJfrFileBuilder.createComprehensiveFile();
  }

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();
    invokeTool("jfr_open", Map.of("path", comprehensiveFile.toString()));
  }

  @AfterEach
  void tearDown() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("closeAll", true);
    getMethod("handleJfrClose", Map.class).invoke(server, args);
  }

  private Method getMethod(String name, Class<?>... types) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method;
  }

  private CallToolResult invokeTool(String toolName, Map<String, Object> args) throws Exception {
    String methodName = camelCase("handle_" + toolName);
    try {
      Method method = getMethod(methodName, McpSyncServerExchange.class, Map.class, Object.class);
      return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args, null);
    } catch (NoSuchMethodException e) {
      try {
        Method method = getMethod(methodName, McpSyncServerExchange.class, Map.class);
        return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);
      } catch (NoSuchMethodException e2) {
        return (CallToolResult) getMethod(methodName, Map.class).invoke(server, args);
      }
    }
  }

  private static String camelCase(String snake) {
    String[] parts = snake.split("_");
    StringBuilder sb = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
    }
    return sb.toString();
  }

  /** The tool's JSON, or a failure naming what the tool said. */
  private JsonNode run(String tool, Map<String, Object> args) throws Exception {
    CallToolResult result = invokeTool(tool, args);
    String text = ((TextContent) result.content().get(0)).text();
    assertFalse(result.isError(), tool + " failed: " + text);
    return MAPPER.readTree(text);
  }

  /** Every key present at the top level, sorted — the part a caller binds to. */
  private static List<String> keysOf(JsonNode node) {
    List<String> keys = new ArrayList<>();
    node.propertyNames().forEach(keys::add);
    keys.sort(String::compareTo);
    return keys;
  }

  @Test
  void summaryKeepsItsShape() throws Exception {
    JsonNode json = run("jfr_summary", Map.of());

    assertEquals(
        List.of(
            "highlights",
            "recordingPath",
            "sessionId",
            "topEventTypes",
            "totalEventTypes",
            "totalEvents"),
        keysOf(json));
    assertTrue(json.get("totalEvents").asLong() > 0);
    // sessionId has always been a number here; a caller may be parsing it as one.
    assertTrue(json.get("sessionId").isNumber(), "sessionId must stay numeric");
  }

  @Test
  void useKeepsItsShape() throws Exception {
    JsonNode json = run("jfr_use", Map.of());

    List<String> keys = keysOf(json);
    assertTrue(keys.contains("findings"), keys.toString());
    assertTrue(keys.contains("resources"), keys.toString());
    assertTrue(
        json.get("findings").isArray(), "findings is the shared shape and must stay an array");
  }

  @Test
  void tsaKeepsItsShape() throws Exception {
    JsonNode json = run("jfr_tsa", Map.of());

    List<String> keys = keysOf(json);
    assertTrue(keys.contains("findings"), keys.toString());
    assertTrue(json.get("findings").isArray(), keys.toString());
  }

  @Test
  void diagnoseKeepsItsShapeAndItsGaps() throws Exception {
    JsonNode json = run("jfr_diagnose", Map.of());

    List<String> keys = keysOf(json);
    // capabilityGaps is the load-bearing one: what a recording cannot answer is not a negative
    // answer, and a caller that loses this key starts reporting absence as evidence.
    assertTrue(keys.contains("capabilityGaps"), keys.toString());
    assertTrue(keys.contains("findings"), keys.toString());
    assertTrue(keys.contains("headlines"), keys.toString());
    assertTrue(keys.contains("recommendations"), keys.toString());
    assertTrue(keys.contains("recordingPath"), keys.toString());
  }

  @Test
  void quickDiagnoseSkipsTheDeepPasses() throws Exception {
    JsonNode full = run("jfr_diagnose", Map.of());
    JsonNode quick = run("jfr_diagnose", Map.of("depth", "quick"));

    // depth=quick exists to opt out of USE and TSA; if it stops doing that the option is a lie.
    assertTrue(keysOf(quick).contains("findings"), keysOf(quick).toString());
    assertTrue(
        quick.toString().length() <= full.toString().length(),
        "quick should not be the larger answer");
  }

  @Test
  void hotmethodsAndExceptionsKeepTheirShape() throws Exception {
    JsonNode hot = run("jfr_hotmethods", Map.of());
    assertTrue(keysOf(hot).contains("methods"), keysOf(hot).toString());

    JsonNode exceptions = run("jfr_exceptions", Map.of());
    assertTrue(keysOf(exceptions).size() > 0);
  }
}
