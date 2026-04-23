package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for idle timeout / activity tracking logic. */
class IdleTimeoutTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @AfterEach
  void tearDown() {
    // Interrupt any watchdog threads started by tests so they cannot fire System.exit(0)
    Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().equals("mcp-idle-watchdog"))
        .forEach(Thread::interrupt);
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

    Method wrap =
        JafarMcpServer.class.getDeclaredMethod("withActivityTracking", SyncToolSpecification.class);
    wrap.setAccessible(true);

    Method createHelp = JafarMcpServer.class.getDeclaredMethod("createJfrHelpTool");
    createHelp.setAccessible(true);
    var spec = (SyncToolSpecification) createHelp.invoke(server);
    var wrapped = (SyncToolSpecification) wrap.invoke(server, spec);

    long before = System.nanoTime();
    wrapped.callHandler().apply(null, new CallToolRequest("jfr_help", Map.of("topic", "overview")));
    long after = System.nanoTime();

    long recorded = (long) field.get(server);
    assertTrue(recorded >= before, "lastActivityNanos should be updated after wrapped call");
    assertTrue(recorded <= after);
  }

  @Test
  void activeRequestsCounterIsZeroAfterWrappedCallCompletes() throws Exception {
    Field activeField = JafarMcpServer.class.getDeclaredField("activeRequests");
    activeField.setAccessible(true);

    Method wrap =
        JafarMcpServer.class.getDeclaredMethod("withActivityTracking", SyncToolSpecification.class);
    wrap.setAccessible(true);

    Method createHelp = JafarMcpServer.class.getDeclaredMethod("createJfrHelpTool");
    createHelp.setAccessible(true);
    var spec = (SyncToolSpecification) createHelp.invoke(server);

    // Capture activeRequests value seen during the call
    AtomicInteger duringCall = new AtomicInteger(-1);
    SyncToolSpecification spied =
        new SyncToolSpecification(
            spec.tool(),
            (exchange, args) -> {
              try {
                duringCall.set(((AtomicInteger) activeField.get(server)).get());
              } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
              }
              return spec.callHandler().apply(exchange, args);
            });

    var wrapped = (SyncToolSpecification) wrap.invoke(server, spied);
    wrapped.callHandler().apply(null, new CallToolRequest("jfr_help", Map.of("topic", "overview")));

    assertEquals(1, duringCall.get(), "activeRequests should be 1 while the call is running");
    assertEquals(
        0,
        ((AtomicInteger) activeField.get(server)).get(),
        "activeRequests should be 0 after the call");
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
      Method start = JafarMcpServer.class.getDeclaredMethod("startIdleWatchdog");
      start.setAccessible(true);
      start.invoke(server);

      // Thread.start() is async — spin-wait until the watchdog thread appears or 2s pass
      long deadline = System.currentTimeMillis() + 2_000;
      boolean found = false;
      while (System.currentTimeMillis() < deadline) {
        found =
            Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("mcp-idle-watchdog"));
        if (found) break;
        Thread.sleep(10);
      }
      assertTrue(found, "Watchdog thread should have been started within 2 seconds");
    } finally {
      System.clearProperty("mcp.idle.timeout.minutes");
    }
  }
}
