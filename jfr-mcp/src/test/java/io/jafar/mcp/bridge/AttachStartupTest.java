package io.jafar.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bridge's reason to exist is that attaching is cheap, and what makes it cheap is not loading
 * the server. Loading {@code JafarMcpServer} alone pulls in Jetty, the MCP SDK and Reactor (roughly
 * 850 classes, over a second), so nothing on the attach path may touch it. This runs the real entry
 * point in a fresh JVM and reads the class-load log, because that property cannot be seen from
 * inside one JVM that has already loaded everything.
 */
class AttachStartupTest {

  @Test
  void attachingNeverLoadsTheServerOrItsDependencies(@TempDir Path tmp) throws Exception {
    Path classLog = tmp.resolve("class-load.log");
    Process jvm =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xlog:class+load:file=" + classLog,
                "-Djafar.state.dir=" + tmp.resolve("state"),
                "-cp",
                System.getProperty("java.class.path"),
                "io.jafar.mcp.Main",
                "--stdio",
                "--attach")
            .redirectErrorStream(true)
            .redirectOutput(tmp.resolve("child-output.txt").toFile())
            .start();
    jvm.getOutputStream().close(); // stdin EOF: the bridge exits without needing a daemon

    assertTrue(jvm.waitFor(30, TimeUnit.SECONDS), "bridge did not exit when stdin closed");
    assertEquals(
        0, jvm.exitValue(), "child output: " + Files.readString(tmp.resolve("child-output.txt")));

    List<String> loaded = Files.readAllLines(classLog);
    assertTrue(
        loaded.stream().anyMatch(l -> l.contains("io.jafar.mcp.bridge.BridgeMain")),
        "sanity: the attach path should have run BridgeMain");
    for (String heavy :
        List.of("io.jafar.mcp.JafarMcpServer ", "org.eclipse.jetty.", "io.modelcontextprotocol.")) {
      assertFalse(
          loaded.stream().anyMatch(l -> l.contains(heavy)),
          "attach path loaded " + heavy.trim() + ", which makes every session pay for the server");
    }
  }

  /**
   * The expensive libraries are only reached when the bridge actually talks to a daemon, so the
   * check above (stdin closed at once) cannot see them. This drives a real attach against a fake
   * daemon: building an HttpClient initialises TLS (about 300ms) and an ObjectMapper loads ~350
   * databind classes (about 300ms), and the bridge was rewritten to avoid both.
   */
  @Test
  void aRealAttachAvoidsHttpClientTlsAndDatabind(@TempDir Path tmp) throws Exception {
    try (FakeSseDaemon daemon = new FakeSseDaemon().start(true)) {
      Path state = Files.createDirectories(tmp.resolve("state"));
      Files.writeString(state.resolve("mcp-sse.port"), String.valueOf(daemon.port()));
      Path token = state.resolve("mcp-sse.token");
      Files.writeString(token, FakeSseDaemon.TOKEN);
      Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));

      Path classLog = tmp.resolve("class-load.log");
      Process jvm =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xlog:class+load:file=" + classLog,
                  "-Djafar.state.dir=" + state,
                  "-cp",
                  System.getProperty("java.class.path"),
                  "io.jafar.mcp.Main",
                  "--stdio",
                  "--attach")
              .redirectError(tmp.resolve("child-stderr.txt").toFile())
              .start();
      jvm.getOutputStream()
          .write(
              ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n")
                  .getBytes(StandardCharsets.UTF_8));
      jvm.getOutputStream().flush();

      BufferedReader out =
          new BufferedReader(new InputStreamReader(jvm.getInputStream(), StandardCharsets.UTF_8));
      String reply = CompletableFuture.supplyAsync(() -> readLine(out)).get(30, TimeUnit.SECONDS);
      jvm.getOutputStream().close();
      assertTrue(jvm.waitFor(30, TimeUnit.SECONDS), "bridge did not exit when stdin closed");

      assertTrue(
          reply != null && reply.contains("\"serverInfo\""),
          "the attach should have reached the daemon; reply="
              + reply
              + " stderr="
              + Files.readString(tmp.resolve("child-stderr.txt")));
      List<String> loaded = Files.readAllLines(classLog);
      for (String heavy :
          List.of(
              "io.jafar.mcp.JafarMcpServer ",
              "org.eclipse.jetty.",
              "io.modelcontextprotocol.",
              "tools.jackson.databind.",
              "jdk.internal.net.http.",
              "sun.security.ssl.")) {
        assertFalse(
            loaded.stream().anyMatch(l -> l.contains(heavy)),
            "attach loaded " + heavy.trim() + ", which every session would pay for");
      }
    }
  }

  private static String readLine(BufferedReader r) {
    try {
      return r.readLine();
    } catch (java.io.IOException e) {
      return null;
    }
  }
}
