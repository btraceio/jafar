package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.resource.JafarResources;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.shell.core.RequestScope;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code jafar://sessions} resource lists open recordings and profiles. Resource reads run
 * outside a tool call, so unless the handler sets the caller's scope itself it lists every client's
 * sessions, and with them their file paths.
 */
class SessionsResourceOwnershipTest {

  @TempDir Path tempDir;

  private PprofSessionRegistry pprof;
  private JafarResources resources;

  @BeforeEach
  void setUp() {
    pprof = new PprofSessionRegistry();
    resources =
        new JafarResources(
            new SessionRegistry(),
            new HeapSessionRegistry(),
            pprof,
            new OtlpSessionRegistry(),
            new io.jafar.mcp.jfr.JfrHelpProvider(),
            null);
    RequestScope.clear();
  }

  @AfterEach
  void tearDown() {
    RequestScope.clear();
    pprof.shutdown();
  }

  private Path profile(String dirName) throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve(dirName));
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    long fn = b.addFunction(b.addString("main"), b.addString("Main.java"));
    long loc = b.addLocation(fn, 1);
    b.addSample(List.of(loc), List.of(1_000_000L), List.of());
    return b.write(dir);
  }

  private String readSessionsResourceAs(String clientSessionId) {
    McpServerFeatures.SyncResourceSpecification spec =
        resources.createResourceSpecifications().stream()
            .filter(s -> "jafar://sessions".equals(s.resource().uri()))
            .findFirst()
            .orElseThrow();
    McpSyncServerExchange exchange =
        new McpSyncServerExchange(
            new McpAsyncServerExchange(
                clientSessionId, null, null, null, McpTransportContext.EMPTY));

    // The read handler runs on a thread with no request scope, as in the real server.
    RequestScope.clear();
    McpSchema.ReadResourceResult result =
        spec.readHandler().apply(exchange, new McpSchema.ReadResourceRequest("jafar://sessions"));
    assertNotNull(result);
    return ((McpSchema.TextResourceContents) result.contents().get(0)).text();
  }

  @Test
  void eachClientSeesOnlyItsOwnSessionsInTheResource() throws Exception {
    RequestScope.set("A");
    pprof.open(profile("alice"), null);
    RequestScope.set("B");
    pprof.open(profile("bob"), null);

    String seenByA = readSessionsResourceAs("A");
    String seenByB = readSessionsResourceAs("B");

    assertTrue(seenByA.contains("alice"), seenByA);
    assertFalse(seenByA.contains("bob"), "A must not see B's recording path:\n" + seenByA);
    assertTrue(seenByB.contains("bob"), seenByB);
    assertFalse(seenByB.contains("alice"), "B must not see A's recording path:\n" + seenByB);
  }

  @Test
  void theHandlerDoesNotLeaveItsScopeBehindOnTheThread() throws Exception {
    RequestScope.set("A");
    pprof.open(profile("alice"), null);

    readSessionsResourceAs("A");

    assertTrue(
        RequestScope.current() == null,
        "a scope left on the thread would leak into the next request it serves");
  }
}
