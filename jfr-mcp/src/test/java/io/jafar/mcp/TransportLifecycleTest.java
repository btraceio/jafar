package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

class TransportLifecycleTest {

  @Test
  void executorsAreTerminatedAfterShutdown() throws Exception {
    PipedInputStream serverIn = new PipedInputStream(1 << 16);
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream(1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    try {
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
    } finally {
      try {
        serverIn.close();
      } catch (Exception ignored) {
      }
      try {
        clientOut.close();
      } catch (Exception ignored) {
      }
      try {
        clientIn.close();
      } catch (Exception ignored) {
      }
      try {
        serverOut.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void closeGracefullyTerminatesInboundExecutorEvenWhileReaderIsParked() throws Exception {
    UninterruptibleBlockingInputStream serverIn = new UninterruptibleBlockingInputStream();
    PipedInputStream clientIn = new PipedInputStream(1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    try {
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
    } finally {
      try {
        serverIn.close();
      } catch (Exception ignored) {
      }
      try {
        clientIn.close();
      } catch (Exception ignored) {
      }
      try {
        serverOut.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void handlerErrorYieldsJsonRpcErrorAndPipelineContinues() throws Exception {
    PipedInputStream serverIn = new PipedInputStream(1 << 16);
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream(1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    FixedStdioServerTransportProvider transport =
        new FixedStdioServerTransportProvider(McpJsonDefaults.getMapper(), serverIn, serverOut);

    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              McpJsonMapper mapper = McpJsonDefaults.getMapper();
              try (BufferedReader br =
                  new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                  if (line.isBlank()) continue;
                  received.put(mapper.readValue(line, JsonNode.class));
                }
              } catch (Exception ignored) {
              }
            },
            "test-reader");
    reader.setDaemon(true);
    reader.start();

    try {
      transport.setSessionFactory(
          (McpServerTransport sessionTransport) ->
              new McpServerSession(
                  "test-session",
                  Duration.ofSeconds(5),
                  sessionTransport,
                  initReq -> Mono.empty(),
                  Collections.emptyMap(),
                  Collections.emptyMap()) {
                @Override
                public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
                  if (message instanceof McpSchema.JSONRPCRequest req) {
                    if (Integer.valueOf(1).equals(req.id())) {
                      return Mono.error(new RuntimeException("boom"));
                    }
                    McpSchema.JSONRPCResponse ok =
                        new McpSchema.JSONRPCResponse(
                            McpSchema.JSONRPC_VERSION, req.id(), "ok", null);
                    return sessionTransport.sendMessage(ok);
                  }
                  return Mono.empty();
                }
              });

      clientOut.write(
          ("{\"jsonrpc\":\"" + McpSchema.JSONRPC_VERSION + "\",\"id\":1,\"method\":\"x\"}\n")
              .getBytes(StandardCharsets.UTF_8));
      clientOut.write(
          ("{\"jsonrpc\":\"" + McpSchema.JSONRPC_VERSION + "\",\"id\":2,\"method\":\"x\"}\n")
              .getBytes(StandardCharsets.UTF_8));
      clientOut.flush();

      JsonNode first = received.poll(5, TimeUnit.SECONDS);
      JsonNode second = received.poll(5, TimeUnit.SECONDS);

      assertNotNull(first, "expected JSON-RPC error response for id=1");
      assertNotNull(second, "expected JSON-RPC success response for id=2");

      // Order is not strictly guaranteed by flatMap; identify by id.
      JsonNode errResp = first.get("id").asInt() == 1 ? first : second;
      JsonNode okResp = first.get("id").asInt() == 2 ? first : second;

      assertEquals(1, errResp.get("id").asInt());
      assertTrue(errResp.has("error"), "id=1 response must carry an 'error' member");
      assertFalse(errResp.has("result"), "id=1 response must not carry a 'result' member");

      assertEquals(2, okResp.get("id").asInt());
      assertTrue(okResp.has("result"), "id=2 response must carry a 'result' member");
    } finally {
      try {
        clientOut.close();
      } catch (Exception ignored) {
      }
      try {
        transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
      } catch (Exception ignored) {
      }
      try {
        serverIn.close();
      } catch (Exception ignored) {
      }
      try {
        clientIn.close();
      } catch (Exception ignored) {
      }
      try {
        serverOut.close();
      } catch (Exception ignored) {
      }
      reader.interrupt();
    }
  }

  /**
   * Test-only InputStream that blocks forever on read() and ignores Thread.interrupt(). Released
   * only by close().
   */
  private static final class UninterruptibleBlockingInputStream extends InputStream {
    private final CountDownLatch released = new CountDownLatch(1);
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
