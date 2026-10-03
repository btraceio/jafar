package io.jafar.mcp;

import io.jafar.mcp.bridge.BridgeMain;

/**
 * The jar's entry point. It exists to choose a mode before anything heavy is loaded.
 *
 * <p>Naming {@link JafarMcpServer} as the main class made the JVM load and verify it first, which
 * drags in Jetty, the MCP SDK and Reactor (hundreds of classes, over a second) even for {@code
 * --attach}, whose whole point is to be cheap. Here nothing references the server until we know we
 * need it.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    if (BridgeMain.requested(args)) {
      System.exit(BridgeMain.run());
    }
    JafarMcpServer.main(args);
  }
}
