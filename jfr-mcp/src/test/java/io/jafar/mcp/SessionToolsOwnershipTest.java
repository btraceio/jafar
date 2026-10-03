package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.RequestScope;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the close tools tell a client when other clients share the server. Counts must be the
 * caller's own, never a total across everyone's sessions, and another client's id must not close.
 */
class SessionToolsOwnershipTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
    RequestScope.clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    RequestScope.clear(); // unscoped = the server itself: closes everything
    call("handleJfrClose", Map.of("closeAll", true));
    call("handleHdumpClose", Map.of("closeAll", true));
  }

  private CallToolResult call(String handler, Map<String, Object> args) throws Exception {
    Method m = JafarMcpServer.class.getDeclaredMethod(handler, Map.class);
    m.setAccessible(true);
    return (CallToolResult) m.invoke(server, new HashMap<>(args));
  }

  private static JsonNode json(CallToolResult result) {
    return MAPPER.readTree(((TextContent) result.content().get(0)).text());
  }

  private int openJfr() throws Exception {
    CallToolResult r = call("handleJfrOpen", Map.of("path", getComprehensiveJfr()));
    assertFalse(r.isError(), String.valueOf(r.content()));
    return json(r).get("id").asInt();
  }

  @Test
  void jfrCloseAllReportsOnlyTheCallersOwnSessions() throws Exception {
    RequestScope.set("A");
    openJfr();
    openJfr();
    RequestScope.set("B");
    openJfr();

    RequestScope.set("A");
    JsonNode closed = json(call("handleJfrClose", Map.of("closeAll", true)));

    assertEquals("Closed 2 session(s)", closed.get("message").asString());
    assertEquals(0, closed.get("remainingSessions").asInt());

    RequestScope.set("B");
    JsonNode closedB = json(call("handleJfrClose", Map.of("closeAll", true)));
    assertEquals("Closed 1 session(s)", closedB.get("message").asString(), "B's session survived");
  }

  @Test
  void jfrCloseByAnotherClientsIdIsRefused() throws Exception {
    RequestScope.set("A");
    int a = openJfr();

    RequestScope.set("B");
    CallToolResult refused = call("handleJfrClose", Map.of("sessionId", String.valueOf(a)));
    assertTrue(refused.isError(), "B must not be able to close A's session");

    RequestScope.set("A");
    assertFalse(call("handleJfrClose", Map.of("sessionId", String.valueOf(a))).isError());
  }

  @Test
  void jfrSingleCloseReportsTheCallersRemainingNotEveryonesSessions() throws Exception {
    RequestScope.set("A");
    int a = openJfr();
    RequestScope.set("B");
    openJfr();
    openJfr();

    RequestScope.set("A");
    JsonNode closed = json(call("handleJfrClose", Map.of("sessionId", String.valueOf(a))));

    assertEquals(0, closed.get("remainingSessions").asInt(), "A has none left; B's are not A's");
  }

  private Path minimalHprof() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
      out.writeInt(8);
      out.writeLong(0L);
    }
    Path dump = tempDir.resolve("minimal.hprof");
    Files.write(dump, bytes.toByteArray());
    return dump;
  }

  @Test
  void hdumpCloseAllReportsOnlyTheCallersOwnSessions() throws Exception {
    Path dump = minimalHprof();
    RequestScope.set("A");
    assertFalse(call("handleHdumpOpen", Map.of("path", dump.toString())).isError());
    RequestScope.set("B");
    assertFalse(call("handleHdumpOpen", Map.of("path", dump.toString())).isError());
    assertFalse(call("handleHdumpOpen", Map.of("path", dump.toString())).isError());

    RequestScope.set("B");
    JsonNode closed = json(call("handleHdumpClose", Map.of("closeAll", true)));

    assertEquals("Closed 2 session(s)", closed.get("message").asString());
    assertEquals(0, closed.get("remainingSessions").asInt());

    RequestScope.set("A");
    JsonNode closedA = json(call("handleHdumpClose", Map.of("closeAll", true)));
    assertEquals("Closed 1 session(s)", closedA.get("message").asString(), "A's dump survived");
  }
}
