package io.jafar.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Drop-in replacement for the SDK's {@code StdioServerTransportProvider} with two upstream bugs
 * fixed:
 *
 * <ol>
 *   <li>{@link #protocolVersions()} now advertises all four MCP protocol versions instead of only
 *       {@code 2024-11-05}.
 *   <li>The shutdown race condition is fixed: when the outbound sink is already terminated during
 *       server shutdown, {@link StdioMcpSessionTransport#sendMessage} silently drops the response
 *       instead of propagating an error that would crash the Reactor Flux pipeline.
 * </ol>
 *
 * <p>Source copied from SDK 1.1.1 {@code StdioServerTransportProvider} and modified.
 */
class FixedStdioServerTransportProvider implements McpServerTransportProvider, ShutdownAwaitable {

  private static final Logger logger =
      LoggerFactory.getLogger(FixedStdioServerTransportProvider.class);

  private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

  private final McpJsonMapper jsonMapper;

  private final InputStream inputStream;

  private final OutputStream outputStream;

  private McpServerSession session;

  private final AtomicBoolean isClosing = new AtomicBoolean(false);

  private final Sinks.One<Void> inboundReady = Sinks.one();

  private final Sinks.One<Void> shutdownSignal = Sinks.one();

  FixedStdioServerTransportProvider(McpJsonMapper jsonMapper) {
    this(jsonMapper, System.in, System.out);
  }

  FixedStdioServerTransportProvider(
      McpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream) {
    Assert.notNull(jsonMapper, "The JsonMapper can not be null");
    Assert.notNull(inputStream, "The InputStream can not be null");
    Assert.notNull(outputStream, "The OutputStream can not be null");

    this.jsonMapper = jsonMapper;
    this.inputStream = inputStream;
    this.outputStream = outputStream;
  }

  @Override
  public List<String> protocolVersions() {
    return List.of(
        ProtocolVersions.MCP_2024_11_05,
        ProtocolVersions.MCP_2025_03_26,
        ProtocolVersions.MCP_2025_06_18,
        ProtocolVersions.MCP_2025_11_25);
  }

  @Override
  public void setSessionFactory(McpServerSession.Factory sessionFactory) {
    var transport = new StdioMcpSessionTransport();
    this.session = sessionFactory.create(transport);
    transport.initProcessing();
  }

  @Override
  public Mono<Void> notifyClients(String method, Object params) {
    if (this.session == null) {
      return Mono.error(new IllegalStateException("No session to notify"));
    }
    return this.session
        .sendNotification(method, params)
        .doOnError(e -> logger.error("Failed to send notification: {}", e.getMessage()));
  }

  @Override
  public Mono<Void> notifyClient(String sessionId, String method, Object params) {
    return Mono.defer(
        () -> {
          if (this.session == null) {
            return Mono.error(new IllegalStateException("No session to notify"));
          }
          if (!this.session.getId().equals(sessionId)) {
            return Mono.error(
                new IllegalStateException(
                    "Existing session id "
                        + this.session.getId()
                        + " doesn't match the notification target: "
                        + sessionId));
          }
          return this.session.sendNotification(method, params);
        });
  }

  @Override
  public Mono<Void> closeGracefully() {
    if (this.session == null) {
      return Mono.empty();
    }
    return this.session.closeGracefully();
  }

  /**
   * Returns a {@link Mono} that completes when the inbound processing loop ends (stdin EOF or
   * graceful close). Callers can {@code .block()} on this to park the main thread until the
   * transport shuts itself down, instead of waiting on an unbounded latch.
   */
  @Override
  public Mono<Void> awaitShutdown() {
    return shutdownSignal.asMono();
  }

  private class StdioMcpSessionTransport implements McpServerTransport {

    private final Sinks.Many<JSONRPCMessage> inboundSink;

    private final Sinks.Many<JSONRPCMessage> outboundSink;

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private Scheduler inboundScheduler;

    private Scheduler outboundScheduler;

    private final Sinks.One<Void> outboundReady = Sinks.one();

    StdioMcpSessionTransport() {
      this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
      this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

      this.inboundScheduler =
          Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "stdio-inbound");
      this.outboundScheduler =
          Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "stdio-outbound");
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return Mono.zip(inboundReady.asMono(), outboundReady.asMono())
          .then(
              Mono.defer(
                  () -> {
                    if (outboundSink.tryEmitNext(message).isSuccess()) {
                      return Mono.empty();
                    }
                    // Sink terminated during shutdown — drop silently instead of crashing the
                    // pipeline. The sink can also fail for other reasons, so we only suppress
                    // when we know we are already closing.
                    if (isClosing.get()) {
                      return Mono.empty();
                    }
                    return Mono.error(new RuntimeException("Failed to enqueue message"));
                  }));
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
      return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.fromRunnable(
          () -> {
            isClosing.set(true);
            logger.debug("Session transport closing gracefully");
            inboundSink.tryEmitComplete();
          });
    }

    @Override
    public void close() {
      isClosing.set(true);
      logger.debug("Session transport closed");
    }

    private void initProcessing() {
      handleIncomingMessages();
      startInboundProcessing();
      startOutboundProcessing();
    }

    private void handleIncomingMessages() {
      this.inboundSink
          .asFlux()
          .flatMap(
              message ->
                  session
                      .handle(message)
                      .doOnError(e -> logger.error("Error handling inbound message", e))
                      .onErrorResume(e -> Mono.empty()))
          .doFinally(
              signalType -> {
                this.outboundSink.tryEmitComplete();
                this.inboundScheduler.dispose();
                if (!shutdownSignal.tryEmitEmpty().isSuccess()) {
                  logger.warn("Shutdown signal emission failed");
                }
              })
          .subscribe();
    }

    private void startInboundProcessing() {
      if (isStarted.compareAndSet(false, true)) {
        this.inboundScheduler.schedule(
            () -> {
              inboundReady.tryEmitValue(null);
              BufferedReader reader =
                  new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
              try {
                while (!isClosing.get()) {
                  try {
                    String line = reader.readLine();
                    if (line == null || isClosing.get()) {
                      break;
                    }
                    logger.debug("Received JSON message: {}", line);
                    try {
                      McpSchema.JSONRPCMessage message =
                          McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                      if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
                        break;
                      }
                    } catch (Exception e) {
                      logIfNotClosing("Error processing inbound message", e);
                      break;
                    }
                  } catch (IOException e) {
                    logIfNotClosing("Error reading from stdin", e);
                    break;
                  }
                }
              } catch (Exception e) {
                logIfNotClosing("Error in inbound processing", e);
              } finally {
                isClosing.set(true);
                try {
                  reader.close();
                } catch (IOException ignored) {
                }
                if (session != null) {
                  session.close();
                }
                inboundSink.tryEmitComplete();
              }
            });
      }
    }

    private void startOutboundProcessing() {
      outboundSink
          .asFlux()
          .doOnSubscribe(subscription -> outboundReady.tryEmitValue(null))
          .publishOn(outboundScheduler)
          .handle(
              (message, sink) -> {
                if (message != null && !isClosing.get()) {
                  try {
                    String jsonMessage = jsonMapper.writeValueAsString(message);
                    jsonMessage =
                        jsonMessage
                            .replace("\r\n", "\\n")
                            .replace("\n", "\\n")
                            .replace("\r", "\\n");
                    outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
                    outputStream.write(NEWLINE);
                    outputStream.flush();
                    sink.next(message);
                  } catch (IOException e) {
                    if (!isClosing.get()) {
                      logger.error("Error writing message", e);
                      sink.error(new RuntimeException(e));
                    } else {
                      logger.debug("Stream closed during shutdown", e);
                    }
                  }
                } else if (isClosing.get()) {
                  sink.complete();
                }
              })
          .doOnComplete(
              () -> {
                isClosing.set(true);
                outboundScheduler.dispose();
              })
          .doOnError(
              e -> {
                if (!isClosing.get()) {
                  logger.error("Error in outbound processing", e);
                  isClosing.set(true);
                }
                outboundScheduler.dispose();
              })
          .subscribe();
    }

    private void logIfNotClosing(String message, Exception e) {
      if (!isClosing.get()) {
        logger.error(message, e);
      }
    }
  }
}
