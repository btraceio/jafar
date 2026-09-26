package io.jafar.mcp.lifecycle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;

/**
 * Manages the SSE daemon port marker file and URL formatting.
 *
 * <p>The file holds the port on its first line and, on its second, the host clients should connect
 * to. That host is an IP literal, not {@code localhost}: where {@code localhost} resolves to {@code
 * ::1} first, a client connecting by name would miss a server bound to {@code 127.0.0.1}.
 */
public final class SsePortRegistry {

  /** Where a server is listening, as a client should address it. */
  public record Endpoint(String host, int port) {}

  private static final String DEFAULT_CLIENT_HOST = "127.0.0.1";

  private final Path portFile;
  private final Logger logger;

  public SsePortRegistry(Path portFile, Logger logger) {
    this.portFile = portFile;
    this.logger = logger;
  }

  public static SsePortRegistry defaultRegistry(Logger logger) {
    return new SsePortRegistry(
        Path.of(System.getProperty("user.home"), ".jafar", "mcp-sse.port"), logger);
  }

  /** The address a local client should use to reach a server bound to {@code bindHost}. */
  public static String clientHost(String bindHost) {
    if (bindHost == null || bindHost.isBlank() || bindHost.equals("0.0.0.0")) {
      return DEFAULT_CLIENT_HOST;
    }
    if (bindHost.equals("::") || bindHost.equals("[::]")) {
      return "::1";
    }
    return bindHost;
  }

  /**
   * Returns the server recorded in the port file if it is still reachable, otherwise cleans up the
   * stale file and returns {@code null}.
   */
  public Endpoint detectRunningServer() {
    try {
      if (!Files.exists(portFile)) {
        return null;
      }
      List<String> lines = Files.readAllLines(portFile);
      int port = Integer.parseInt(lines.get(0).trim());
      String host =
          lines.size() > 1 && !lines.get(1).isBlank() ? lines.get(1).trim() : DEFAULT_CLIENT_HOST;
      if (isPortInUse(host, port)) {
        return new Endpoint(host, port);
      }
      Files.deleteIfExists(portFile);
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  public void write(Endpoint endpoint) {
    try {
      Files.createDirectories(portFile.getParent());
      Files.writeString(portFile, endpoint.port() + "\n" + endpoint.host() + "\n");
    } catch (IOException e) {
      logger.warn("Cannot write SSE port file: {}", e.getMessage());
    }
  }

  public void delete() {
    try {
      Files.deleteIfExists(portFile);
    } catch (IOException e) {
      logger.warn("Cannot delete SSE port file: {}", e.getMessage());
    }
  }

  public String url(Endpoint endpoint) {
    String host = endpoint.host();
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    return "http://" + host + ":" + endpoint.port() + "/mcp/sse";
  }

  public boolean isPortInUse(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
