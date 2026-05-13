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

    Field inboundExecField = transport.getClass().getDeclaredField("inboundExecutor");
    inboundExecField.setAccessible(true);
    Field outboundExecField = transport.getClass().getDeclaredField("outboundExecutor");
    outboundExecField.setAccessible(true);

    ExecutorService inbound = (ExecutorService) inboundExecField.get(transport);
    ExecutorService outbound = (ExecutorService) outboundExecField.get(transport);

    assertTrue(inbound.isShutdown(), "inbound executor must be shut down");
    assertTrue(outbound.isShutdown(), "outbound executor must be shut down");
    assertTrue(inbound.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(outbound.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
  }
}
