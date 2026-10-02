package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A real local shadow-JAR MCP client with per-test process and persistence isolation. */
final class McpStdioProcessHarness implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long TIMEOUT_MS = 30_000;

  private final Path root;
  private final Path sessionsFile;
  private final Path home;
  private final Process process;
  private final PrintWriter stdin;
  private final BlockingQueue<JsonNode> pending = new LinkedBlockingQueue<>();
  private final List<JsonNode> observed = java.util.Collections.synchronizedList(new ArrayList<>());
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private final Thread stdoutReader;
  private final Thread stderrReader;
  private int nextId = 1;

  McpStdioProcessHarness(String testName) throws Exception {
    Path parent = Path.of("build", "mcp-e2e").toAbsolutePath().normalize();
    Files.createDirectories(parent);
    root = Files.createTempDirectory(parent, testName.replaceAll("[^A-Za-z0-9_.-]", "_") + "-");
    sessionsFile = root.resolve("sessions.json").normalize();
    home = root.resolve("home").normalize();
    assertOwned(root, sessionsFile, home);
    Files.createDirectories(home);

    String java = ProcessHandle.current().info().command().orElse("java");
    process =
        new ProcessBuilder(
                java,
                "-Djafar.mcp.sessions.file=" + sessionsFile,
                "-Duser.home=" + home,
                "-jar",
                findShadowJar().toString(),
                "--stdio")
            .start();
    stdin =
        new PrintWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
    stdoutReader = new Thread(() -> readStdout(process.getInputStream()), "mcp-e2e-stdout");
    stderrReader = new Thread(() -> copyStderr(process.getErrorStream()), "mcp-e2e-stderr");
    stdoutReader.setDaemon(true);
    stderrReader.setDaemon(true);
    stdoutReader.start();
    stderrReader.start();
    initialize();
  }

  Path root() {
    return root;
  }

  Path sessionsFile() {
    return sessionsFile;
  }

  Path home() {
    return home;
  }

  JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
    return call(tool, arguments, null);
  }

  JsonNode call(String tool, Map<String, Object> arguments, Integer progressToken)
      throws Exception {
    int id = nextId++;
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", tool);
    params.set("arguments", MAPPER.valueToTree(arguments));
    if (progressToken != null) {
      ObjectNode meta = params.putObject("_meta");
      meta.put("claudecode/toolUseId", "toolu_tier1_" + id);
      meta.put("progressToken", progressToken);
    }
    send(id, "tools/call", params);
    JsonNode response = awaitId(id);
    assertNotNull(response, () -> tool + " did not respond; stderr:\n" + stderrText());
    assertFalse(
        response.has("error"),
        () -> tool + " JSON-RPC error: " + response + "\nstderr:\n" + stderrText());
    assertFalse(
        response.at("/result/isError").asBoolean(), () -> tool + " tool error: " + response);
    return response;
  }

  JsonNode readResource(String uri) throws Exception {
    int id = nextId++;
    ObjectNode params = MAPPER.createObjectNode();
    params.put("uri", uri);
    send(id, "resources/read", params);
    JsonNode response = awaitId(id);
    assertNotNull(response, () -> "resources/read did not respond; stderr:\n" + stderrText());
    assertFalse(response.has("error"), () -> "resources/read error: " + response);
    return response;
  }

  JsonNode contentJson(JsonNode response) throws IOException {
    JsonNode content = response.path("result").path("content");
    assertTrue(
        content.isArray() && !content.isEmpty(), () -> "missing MCP result content: " + response);
    String text = content.get(0).path("text").asString();
    assertFalse(text.isBlank(), () -> "empty MCP text payload: " + response);
    return MAPPER.readTree(text);
  }

  void assertProgress(int expectedToken) {
    List<JsonNode> progress = new ArrayList<>();
    synchronized (observed) {
      for (JsonNode message : observed) {
        if (message.has("method")
            && "notifications/progress".equals(message.path("method").asString())) {
          progress.add(message);
        }
      }
    }
    assertFalse(
        progress.isEmpty(),
        () -> "expected progress token " + expectedToken + "; observed=" + observed);
    for (JsonNode notification : progress) {
      JsonNode token = notification.at("/params/progressToken");
      assertTrue(
          token.isIntegralNumber(), () -> "progress token must be integral: " + notification);
      assertEquals(expectedToken, token.asInt(), () -> "wrong progress token: " + notification);
    }
  }

  private void initialize() throws Exception {
    ObjectNode params = MAPPER.createObjectNode();
    params.put("protocolVersion", "2025-06-18");
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "jafar-tier1-e2e").put("version", "1.0");
    send(0, "initialize", params);
    JsonNode response = awaitId(0);
    assertNotNull(response, () -> "MCP handshake failed; stderr:\n" + stderrText());
    assertFalse(
        response.has("error"),
        () -> "MCP handshake error: " + response + "\nstderr:\n" + stderrText());
    stdin.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
  }

  private void send(int id, String method, JsonNode params) throws IOException {
    ObjectNode request = MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", method);
    request.set("params", params);
    stdin.println(MAPPER.writeValueAsString(request));
    assertFalse(stdin.checkError(), () -> "could not write MCP request; stderr:\n" + stderrText());
  }

  private JsonNode awaitId(int id) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
    List<JsonNode> stashed = new ArrayList<>();
    try {
      while (System.nanoTime() < deadline) {
        JsonNode message = pending.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (message == null) {
          return null;
        }
        if (message.has("id") && message.path("id").asInt(Integer.MIN_VALUE) == id) {
          return message;
        }
        stashed.add(message);
      }
      return null;
    } finally {
      pending.addAll(stashed);
    }
  }

  private void readStdout(java.io.InputStream stream) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (!line.isBlank()) {
          JsonNode message = MAPPER.readTree(line);
          observed.add(message);
          pending.put(message);
        }
      }
    } catch (Exception ignored) {
      // Assertion failures include stderr and the messages received before the stream ended.
    }
  }

  private void copyStderr(java.io.InputStream stream) {
    try (stream) {
      stream.transferTo(stderr);
    } catch (IOException ignored) {
    }
  }

  private String stderrText() {
    return stderr.toString(StandardCharsets.UTF_8);
  }

  private static Path findShadowJar() throws IOException {
    Path candidate = Paths.get("build/libs");
    if (!Files.isDirectory(candidate)) {
      candidate = Paths.get("jfr-mcp/build/libs");
    }
    final Path libs = candidate;
    try (var jars = Files.list(libs)) {
      return jars.filter(path -> path.getFileName().toString().matches("jfr-mcp-.*-all\\.jar"))
          .max(Comparator.comparing(Path::toString))
          .orElseThrow(() -> new IOException("no jfr-mcp shadow JAR in " + libs));
    }
  }

  private static void assertOwned(Path root, Path sessionsFile, Path home) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    assertTrue(sessionsFile.toAbsolutePath().normalize().startsWith(normalizedRoot));
    assertTrue(home.toAbsolutePath().normalize().startsWith(normalizedRoot));
  }

  @Override
  public void close() throws Exception {
    stdin.close();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
    stdoutReader.interrupt();
    stderrReader.interrupt();
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  throw new IllegalStateException("cannot remove E2E-owned path: " + path, e);
                }
              });
    }
    assertFalse(Files.exists(root), () -> "E2E cleanup left owned directory: " + root);
  }
}
