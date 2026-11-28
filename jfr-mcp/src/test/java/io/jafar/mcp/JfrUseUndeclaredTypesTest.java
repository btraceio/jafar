package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * #291: jfr_use queried JDK event types by name, and a query naming a type the recording does not
 * declare throws "Event type ... not found". A recording not written by the JDK (here a JMC-written
 * one with only jdk.ExecutionSample) failed the memory and I/O sections instead of reporting that
 * they had no data.
 */
class JfrUseUndeclaredTypesTest {

  @TempDir Path dir;

  private final JafarMcpServer server = new JafarMcpServer();

  @AfterEach
  void closeSessions() throws Exception {
    Method close = JafarMcpServer.class.getDeclaredMethod("handleJfrClose", Map.class);
    close.setAccessible(true);
    close.invoke(server, new HashMap<>(Map.of("closeAll", true)));
  }

  @Test
  void sectionsWithoutTheirEventTypesReportNoDataInsteadOfFailing() throws Exception {
    // In the test's own directory, so a session left behind is not restored by later tests
    Path recording =
        Files.move(
            SimpleJfrFileBuilder.createExecutionSampleFile(20), dir.resolve("samples-only.jfr"));

    JsonNode resources = use(recording).path("resources");

    for (String section : List.of("memory", "io")) {
      JsonNode node = resources.path(section);
      assertFalse(node.isMissingNode(), section + ": " + resources);
      assertFalse(node.has("error"), section + ": " + node);
      assertFalse(node.toString().contains("not found"), section + ": " + node);
    }
    assertTrue(
        resources.path("memory").path("saturation").path("message").asString().contains("No GC"),
        resources.path("memory").toString());
    // No heap figures: the memory section does not call the heap healthy
    assertEquals(
        "UNKNOWN",
        resources.path("memory").path("assessment").asString(),
        resources.path("memory").toString());
  }

  @Test
  void declaredTypesAreStillQueried() throws Exception {
    JsonNode resources = use(gcAndIoRecording()).path("resources");

    JsonNode memory = resources.path("memory");
    assertEquals(100, memory.path("utilization").path("heapUsedMB").asLong(), memory.toString());
    assertEquals(50.0, memory.path("utilization").path("value").asDouble(), memory.toString());
    assertEquals(3, memory.path("saturation").path("gcCount").asInt(), memory.toString());
    assertEquals("HEALTHY", memory.path("assessment").asString(), memory.toString());
    JsonNode io = resources.path("io");
    assertEquals(4, io.path("utilization").path("totalOperations").asLong(), io.toString());
    assertEquals(1, io.path("saturation").path("slowOperations").asLong(), io.toString());
  }

  /**
   * A JMC-written recording declaring jdk.GCHeapSummary (one "After GC" summary: 100 of 200 MB
   * committed), three jdk.GCPhasePause events and four jdk.FileRead events, one of them over 10 ms.
   */
  private Path gcAndIoRecording() throws Exception {
    long mb = 1024L * 1024L;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (Recording r = Recordings.newRecording(bytes)) {
      Type when = r.registerType("jdk.types.GCWhen", t -> t.addField("when", Types.Builtin.STRING));
      Type space =
          r.registerType(
              "jdk.types.VirtualSpace", t -> t.addField("committedSize", Types.Builtin.LONG));
      Type heap =
          r.registerEventType(
              "jdk.GCHeapSummary",
              t ->
                  t.addField("when", when)
                      .addField("heapSpace", space)
                      .addField("heapUsed", Types.Builtin.LONG));
      Type pause =
          r.registerEventType("jdk.GCPhasePause", t -> t.addField("duration", Types.Builtin.LONG));
      Type read =
          r.registerEventType("jdk.FileRead", t -> t.addField("duration", Types.Builtin.LONG));
      long start = System.nanoTime();
      r.writeEvent(
          heap.asValue(
              v -> {
                v.putField("startTime", start);
                v.putField("when", when.asValue(w -> w.putField("when", "After GC")));
                v.putField("heapSpace", space.asValue(c -> c.putField("committedSize", 200 * mb)));
                v.putField("heapUsed", 100 * mb);
              }));
      for (int i = 0; i < 3; i++) {
        long at = start + (i + 1) * 1000L;
        r.writeEvent(
            pause.asValue(
                v -> {
                  v.putField("startTime", at);
                  v.putField("duration", 2_000_000L);
                }));
      }
      for (int i = 0; i < 4; i++) {
        long at = start + (i + 10) * 1000L;
        long duration = i == 0 ? 20_000_000L : 1_000_000L;
        r.writeEvent(
            read.asValue(
                v -> {
                  v.putField("startTime", at);
                  v.putField("duration", duration);
                }));
      }
    }
    return Files.write(dir.resolve("gc-and-io.jfr"), bytes.toByteArray());
  }

  private JsonNode use(Path recording) throws Exception {
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    assertFalse(
        ((CallToolResult) open.invoke(server, Map.of("path", recording.toString()))).isError());
    Method use =
        JafarMcpServer.class.getDeclaredMethod(
            "handleJfrUse", McpSyncServerExchange.class, Map.class, Object.class);
    use.setAccessible(true);
    Map<String, Object> args = new HashMap<>();
    args.put("resources", List.of("all"));
    CallToolResult result = (CallToolResult) use.invoke(server, null, args, null);
    assertFalse(result.isError(), result.content().toString());
    return new ObjectMapper().readTree(((TextContent) result.content().get(0)).text());
  }
}
