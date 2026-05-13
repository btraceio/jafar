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

    // Reader thread is now parked on readLine() — no bytes have been written to stdin.
    // Ask the transport to close gracefully. The reader MUST be released.
    transport.closeGracefully().timeout(Duration.ofSeconds(3)).block();
    transport.awaitShutdown().timeout(Duration.ofSeconds(3)).block();

    Field stdioField = transport.getClass().getDeclaredField("stdioTransport");
    stdioField.setAccessible(true);
    Object stdio = stdioField.get(transport);
    Field inboundExecField = stdio.getClass().getDeclaredField("inboundExecutor");
    inboundExecField.setAccessible(true);
    ExecutorService inbound = (ExecutorService) inboundExecField.get(stdio);

    // Without the fix, the parked reader thread never exits and awaitTermination times out.
    assertTrue(
        inbound.awaitTermination(2, TimeUnit.SECONDS),
        "inbound executor must terminate after closeGracefully() — reader thread is parked otherwise");

    // Tidy.
    clientOut.close();
    clientIn.close();
    serverOut.close();
  }
}
