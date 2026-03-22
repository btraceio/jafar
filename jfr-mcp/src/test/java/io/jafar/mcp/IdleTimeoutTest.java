package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for idle timeout / activity tracking logic. */
class IdleTimeoutTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @Test
  void touchActivityUpdatesLastActivityNanos() throws Exception {
    Field field = JafarMcpServer.class.getDeclaredField("lastActivityNanos");
    field.setAccessible(true);

    long before = System.nanoTime();
    Method touch = JafarMcpServer.class.getDeclaredMethod("touchActivity");
    touch.setAccessible(true);
    touch.invoke(server);
    long after = System.nanoTime();

    long recorded = (long) field.get(server);
    assertTrue(recorded >= before, "lastActivityNanos should be >= before touch");
    assertTrue(recorded <= after, "lastActivityNanos should be <= after touch");
  }

  @Test
  void withActivityTrackingUpdatesTimestampOnCall() throws Exception {
    Field field = JafarMcpServer.class.getDeclaredField("lastActivityNanos");
    field.setAccessible(true);

    // Set lastActivityNanos to a very old value
    field.set(server, 0L);

    // Call a wrapped tool (jfr_help has no session requirement)
    Method handleHelp = JafarMcpServer.class.getDeclaredMethod("handleJfrHelp", Map.class);
    handleHelp.setAccessible(true);

    Method wrap =
        JafarMcpServer.class.getDeclaredMethod(
            "withActivityTracking",
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
    wrap.setAccessible(true);

    Method createHelp = JafarMcpServer.class.getDeclaredMethod("createJfrHelpTool");
    createHelp.setAccessible(true);
    var spec =
        (io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification)
            createHelp.invoke(server);
    var wrapped =
        (io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification)
            wrap.invoke(server, spec);

    long before = System.nanoTime();
    wrapped.call().apply(null, Map.of("topic", "overview"));
    long after = System.nanoTime();

    long recorded = (long) field.get(server);
    assertTrue(recorded >= before, "lastActivityNanos should be updated after wrapped call");
    assertTrue(recorded <= after);
  }

  @Test
  void idleWatchdogDisabledWhenTimeoutIsZero() throws Exception {
    System.setProperty("mcp.idle.timeout.minutes", "0");
    try {
      Method start = JafarMcpServer.class.getDeclaredMethod("startIdleWatchdog");
      start.setAccessible(true);
      // Should return immediately without starting a thread — just verify no exception
      assertDoesNotThrow(() -> start.invoke(server));
    } finally {
      System.clearProperty("mcp.idle.timeout.minutes");
    }
  }

  @Test
  void idleWatchdogStartsWithPositiveTimeout() throws Exception {
    System.setProperty("mcp.idle.timeout.minutes", "10");
    try {
      long threadsBefore =
          Thread.getAllStackTraces().keySet().stream()
              .filter(t -> t.getName().equals("mcp-idle-watchdog"))
              .count();

      Method start = JafarMcpServer.class.getDeclaredMethod("startIdleWatchdog");
      start.setAccessible(true);
      start.invoke(server);

      long threadsAfter =
          Thread.getAllStackTraces().keySet().stream()
              .filter(t -> t.getName().equals("mcp-idle-watchdog"))
              .count();

      assertTrue(threadsAfter > threadsBefore, "Watchdog thread should have been started");
    } finally {
      System.clearProperty("mcp.idle.timeout.minutes");
    }
  }
}
