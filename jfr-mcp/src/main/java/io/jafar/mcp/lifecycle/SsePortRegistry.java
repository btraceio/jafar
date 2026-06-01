package io.jafar.mcp.lifecycle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

/** Manages the SSE daemon port marker file and URL formatting. */
public final class SsePortRegistry {

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

  /**
   * Returns the port from the port file if the server at that port is still reachable, otherwise
   * cleans up the stale file and returns -1.
   */
  public int detectRunningServer() {
    try {
      if (!Files.exists(portFile)) {
        return -1;
      }
      int port = Integer.parseInt(Files.readString(portFile).trim());
      if (isReachable(port)) {
        return port;
      }
      Files.deleteIfExists(portFile);
      return -1;
    } catch (Exception e) {
      return -1;
    }
  }

  public void write(int port) {
    try {
      Files.createDirectories(portFile.getParent());
      Files.writeString(portFile, String.valueOf(port));
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

  public String url(int port) {
    return "http://localhost:" + port + "/mcp/sse";
  }

  public boolean isPortInUse(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private boolean isReachable(int port) {
    return isPortInUse(port);
  }
}
