package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransportLifecycleTest {

  @Test
  void executorsAreTerminatedAfterShutdown() throws Exception {
    PipedInputStream serverIn = new PipedInputStream(1 << 16);
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream(1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    FixedStdioServerTransportProvider transport =
        new FixedStdioServerTransportProvider(McpJsonDefaults.getMapper(), serverIn, serverOut);

    McpServer.sync(transport)
        .serverInfo("test", "0.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .build();

    // Close stdin so the inbound loop exits naturally.
    clientOut.close();
    transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();

    Field stdioField = transport.getClass().getDeclaredField("stdioTransport");
    stdioField.setAccessible(true);
    Object stdio = stdioField.get(transport);

    Field inboundExecField = stdio.getClass().getDeclaredField("inboundExecutor");
    inboundExecField.setAccessible(true);
    Field outboundExecField = stdio.getClass().getDeclaredField("outboundExecutor");
    outboundExecField.setAccessible(true);

    ExecutorService inbound = (ExecutorService) inboundExecField.get(stdio);
    ExecutorService outbound = (ExecutorService) outboundExecField.get(stdio);

    assertTrue(inbound.isShutdown(), "inbound executor must be shut down");
    assertTrue(outbound.isShutdown(), "outbound executor must be shut down");
    assertTrue(inbound.awaitTermination(2, TimeUnit.SECONDS));
    assertTrue(outbound.awaitTermination(2, TimeUnit.SECONDS));
  }

  @Test
  void closeGracefullyTerminatesInboundExecutorEvenWhileReaderIsParked() throws Exception {
    UninterruptibleBlockingInputStream serverIn = new UninterruptibleBlockingInputStream();
    PipedInputStream clientIn = new PipedInputStream(1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    FixedStdioServerTransportProvider transport =
        new FixedStdioServerTransportProvider(McpJsonDefaults.getMapper(), serverIn, serverOut);

    McpServer.sync(transport)
        .serverInfo("test", "0.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .build();

    // Reader thread is parked inside UninterruptibleBlockingInputStream.read() and ignores
    // interrupts. closeGracefully() MUST close the InputStream to release the reader.
    transport.closeGracefully().timeout(Duration.ofSeconds(3)).block();
    transport.awaitShutdown().timeout(Duration.ofSeconds(3)).block();

    Field stdioField = transport.getClass().getDeclaredField("stdioTransport");
    stdioField.setAccessible(true);
    Object stdio = stdioField.get(transport);
    Field inboundExecField = stdio.getClass().getDeclaredField("inboundExecutor");
    inboundExecField.setAccessible(true);
    ExecutorService inbound = (ExecutorService) inboundExecField.get(stdio);

    assertTrue(
        inbound.awaitTermination(2, TimeUnit.SECONDS),
        "inbound executor must terminate after closeGracefully() — interrupts alone do not release the parked reader");

    clientIn.close();
    serverOut.close();
  }

  /**
   * Test-only InputStream that blocks forever on read() and ignores Thread.interrupt(). Released
   * only by close().
   */
  private static final class UninterruptibleBlockingInputStream extends java.io.InputStream {
    private final java.util.concurrent.CountDownLatch released =
        new java.util.concurrent.CountDownLatch(1);
    private volatile boolean closed = false;

    @Override
    public int read() {
      while (!closed) {
        try {
          if (released.await(1, TimeUnit.SECONDS)) break;
        } catch (InterruptedException ignored) {
          // intentionally swallow — simulate a native, non-interruptible read
        }
      }
      return -1; // EOF on close
    }

    @Override
    public void close() {
      closed = true;
      released.countDown();
    }
  }
}
