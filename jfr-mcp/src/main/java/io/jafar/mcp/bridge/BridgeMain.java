package io.jafar.mcp.bridge;

import io.jafar.mcp.lifecycle.StateDir;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Entry point for {@code jfr-mcp --stdio --attach}: a stdio server that is really a thin client of
 * the shared SSE daemon.
 *
 * <p>Kept free of the server's own classes on purpose. The bridge starts once per MCP client, so
 * its startup time is paid on every session, and it must not initialise the logging stack or build
 * the tool registry just to forward bytes.
 */
public final class BridgeMain {

  private static final long DEFAULT_START_TIMEOUT_SECONDS = 20;

  private BridgeMain() {}

  /** The flag that selects this mode. */
  public static final String FLAG = "--attach";

  /** True when the command line asks for the bridge rather than a server. */
  public static boolean requested(String[] args) {
    for (String arg : args) {
      if (FLAG.equals(arg)) {
        return true;
      }
    }
    return false;
  }

  /** Relays stdin/stdout to the daemon, starting it if needed. Returns the exit code. */
  public static int run() {
    // Fd 1 is the protocol channel. Take it raw, and point System.out at stderr so nothing that
    // prints by accident can put a stray byte into the JSON-RPC stream.
    PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), false);
    System.setOut(System.err);

    DaemonLocator locator =
        new DaemonLocator(
            StateDir.resolve(),
            new DaemonLauncher(StateDir.resolve()),
            Duration.ofSeconds(
                Long.getLong("mcp.attach.start.timeout.seconds", DEFAULT_START_TIMEOUT_SECONDS)),
            Duration.ofMillis(100),
            BridgeMain.class.getPackage().getImplementationVersion(),
            System.err);
    return new StdioSseBridge(locator, System.in, protocolOut, System.err, Duration.ofSeconds(10))
        .run();
  }
}
