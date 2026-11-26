package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * A closed session's file must stop being mapped without waiting for the next garbage collection:
 * on Windows a mapped file cannot be deleted, moved or overwritten (#227). The test reads this
 * process's mappings from {@code /proc/self/maps}, so it runs on Linux only. It never requests a
 * collection itself: the close tools must.
 *
 * <p>{@code jfr_summary} and {@code jfr_exceptions} are the tools whose recording 0.29.0 kept
 * mapped after a collection too, through a static cache keyed by metadata class.
 */
class CloseReleasesMappedFileTest {

  private static final Path MAPS = Path.of("/proc/self/maps");
  private static final long WAIT_MILLIS = 10_000;

  @TempDir Path tempDir;

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    assumeTrue(Files.isReadable(MAPS), "needs /proc/self/maps (Linux)");
    server = new JafarMcpServer();
  }

  @Test
  void jfrCloseUnmapsTheRecording() throws Exception {
    Path recording = copyOfTestRecording("close.jfr");
    String id = openRecording(recording);

    assertMapped(recording, true, "after jfr_summary");
    call("handleJfrClose", Map.of("sessionId", id));

    assertMapped(recording, false, "after jfr_close");
  }

  @Test
  void jfrCloseAllUnmapsEveryRecording() throws Exception {
    Path first = copyOfTestRecording("first.jfr");
    Path second = copyOfTestRecording("second.jfr");
    openRecording(first);
    openRecording(second);

    call("handleJfrClose", Map.of("closeAll", true));

    assertMapped(first, false, "after jfr_close(closeAll)");
    assertMapped(second, false, "after jfr_close(closeAll)");
  }

  @Test
  void hdumpCloseUnmapsTheHeapDump() throws Exception {
    Path dump = minimalHprof();
    call("handleHdumpOpen", Map.of("path", dump.toString()));
    assertMapped(dump, true, "after hdump_open");

    call("handleHdumpClose", Map.of("closeAll", true));

    assertMapped(dump, false, "after hdump_close(closeAll)");
  }

  /** Opens {@code recording} and runs the two tools 0.29.0 leaked on. */
  private String openRecording(Path recording) throws Exception {
    String id =
        new ObjectMapper()
            .readTree(text(call("handleJfrOpen", Map.of("path", recording.toString()))))
            .get("id")
            .asString();
    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", id);
    call("handleJfrSummary", args);
    call("handleJfrExceptions", args);
    return id;
  }

  /**
   * Waits up to {@link #WAIT_MILLIS} for {@code file} to be mapped ({@code expected}) or not: the
   * JDK's cleaner thread unmaps a buffer shortly after the collection that found it unreachable.
   */
  private static void assertMapped(Path file, boolean expected, String when) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    long count = mappings(file);
    while ((count > 0) != expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
      count = mappings(file);
    }
    assertEquals(expected, count > 0, file.getFileName() + " mappings " + when + ": " + count);
  }

  private static long mappings(Path file) throws IOException {
    String name = file.toRealPath().toString();
    return Files.readAllLines(MAPS).stream().filter(line -> line.endsWith(" " + name)).count();
  }

  private Path copyOfTestRecording(String name) throws IOException {
    Path source = Path.of("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!Files.exists(source)) {
      source = Path.of("demo/src/test/resources/test-dd.jfr").normalize();
    }
    return Files.copy(source, tempDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
  }

  private Path minimalHprof() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
      out.writeInt(8);
      out.writeLong(0L);
    }
    return Files.write(tempDir.resolve("minimal.hprof"), bytes.toByteArray());
  }

  private CallToolResult call(String handler, Map<String, Object> args) throws Exception {
    CallToolResult result;
    try {
      Method method = JafarMcpServer.class.getDeclaredMethod(handler, Map.class);
      method.setAccessible(true);
      result = (CallToolResult) method.invoke(server, args);
    } catch (NoSuchMethodException noPlainOverload) {
      Method method =
          JafarMcpServer.class.getDeclaredMethod(
              handler, McpSyncServerExchange.class, Map.class, Object.class);
      method.setAccessible(true);
      result = (CallToolResult) method.invoke(server, null, args, null);
    }
    assertFalse(result.isError(), handler + " failed: " + text(result));
    return result;
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
