package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import io.jafar.mcp.prompt.JafarPrompts;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #289: the leak-hunt prompt's queries did not run. Step 3 ranked {@code classes} by {@code
 * retained}, which classes rows do not have (an error); step 6 joined classes {@code by=class} (no
 * such field on classes: every allocCount null) and then filtered on {@code retained} (no rows at
 * all). Every hdump_query the prompt gives is run here, as an agent would run it, against a heap
 * dump and a recording of allocations of a class the dump holds.
 */
class LeakHuntPromptJoinTest {

  private static final Pattern QUERY = Pattern.compile("hdump_query \"((?:[^\"\\\\]|\\\\.)*)\"");

  @TempDir Path dir;

  private final JafarMcpServer server = new JafarMcpServer();

  @AfterEach
  void closeSessions() throws Exception {
    call("handleJfrClose", Map.of("closeAll", true));
    call("handleHdumpClose", Map.of("closeAll", true));
  }

  @Test
  void everyQueryThePromptGivesRunsAndReturnsRows() throws Exception {
    Path dump = dir.resolve("strings.hprof");
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(dump, 200);
    Path recording = allocationsOf("java/lang/String", 5);
    assertFalse(isError(call("handleHdumpOpen", Map.of("path", dump.toString()))));
    assertFalse(
        isError(call("handleJfrOpen", Map.of("path", recording.toString(), "alias", "rec"))));

    List<String> queries = promptQueries();
    assertTrue(queries.size() >= 2, queries.toString());
    boolean correlated = false;
    for (String query : queries) {
      CallToolResult result = call("handleHdumpQuery", Map.of("query", query));
      String text = ((TextContent) result.content().get(0)).text();
      assertFalse(isError(result), query + " -> " + text);
      JsonNode rows = new ObjectMapper().readTree(text).path("results");
      assertTrue(rows.isArray() && rows.size() > 0, query + " returned no rows: " + text);
      if (query.contains("jdk.ObjectAllocationSample")) {
        correlated = true;
        assertTrue(rows.get(0).path("allocCount").asLong() > 0, query + " -> " + text);
      }
    }
    assertTrue(correlated, "no allocation query in " + queries);
  }

  /**
   * The prompt's hdump_query strings, rendered with a recording; the session placeholder filled.
   */
  private static List<String> promptQueries() {
    var spec =
        new JafarPrompts(null, null)
            .createPromptSpecifications().stream()
                .filter(s -> s.prompt().name().equals("leak-hunt"))
                .findFirst()
                .orElseThrow();
    McpSchema.GetPromptResult result =
        spec.promptHandler()
            .apply(
                null,
                new McpSchema.GetPromptRequest(
                    "leak-hunt", Map.of("dump", "d.hprof", "recording", "r.jfr")));
    StringBuilder text = new StringBuilder();
    for (var message : result.messages()) {
      if (message.content() instanceof McpSchema.TextContent content) {
        text.append(content.text());
      }
    }
    List<String> queries = new ArrayList<>();
    Matcher m = QUERY.matcher(text);
    while (m.find()) {
      queries.add(m.group(1).replace("\\\"", "\"").replaceAll("<[^>]+>", "rec"));
    }
    return queries;
  }

  /**
   * A JMC-written recording of {@code count} allocation samples of the class {@code internalName}.
   */
  private Path allocationsOf(String internalName, int count) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (Recording r = Recordings.newRecording(bytes)) {
      // objectClass as the JDK writes it: a java.lang.Class whose name is a jdk.types.Symbol
      Type symbol =
          r.registerType("jdk.types.Symbol", t -> t.addField("string", Types.Builtin.STRING));
      Type clazz = r.registerType("java.lang.Class", t -> t.addField("name", symbol));
      Type sample =
          r.registerEventType(
              "jdk.ObjectAllocationSample",
              t -> t.addField("objectClass", clazz).addField("weight", Types.Builtin.LONG));
      long start = System.nanoTime();
      for (int i = 0; i < count; i++) {
        long at = start + i * 1000L;
        r.writeEvent(
            sample.asValue(
                v -> {
                  v.putField("startTime", at);
                  v.putField(
                      "objectClass",
                      clazz.asValue(
                          c ->
                              c.putField(
                                  "name",
                                  symbol.asValue(n -> n.putField("string", internalName)))));
                  v.putField("weight", 64L);
                }));
      }
    }
    return Files.write(dir.resolve("allocations.jfr"), bytes.toByteArray());
  }

  private CallToolResult call(String handler, Map<String, Object> args) throws Exception {
    Map<String, Object> arguments = new HashMap<>(args);
    try {
      Method method = JafarMcpServer.class.getDeclaredMethod(handler, Map.class);
      method.setAccessible(true);
      return (CallToolResult) method.invoke(server, arguments);
    } catch (NoSuchMethodException noPlainOverload) {
      Method method =
          JafarMcpServer.class.getDeclaredMethod(
              handler, McpSyncServerExchange.class, Map.class, Object.class);
      method.setAccessible(true);
      return (CallToolResult) method.invoke(server, null, arguments, null);
    }
  }

  private static boolean isError(CallToolResult result) {
    return Boolean.TRUE.equals(result.isError());
  }
}
