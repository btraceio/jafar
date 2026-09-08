package io.jafar.shell.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmException;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the backend against a real HTTP server on loopback.
 *
 * <p>A stub server rather than a mocked client, because the things most likely to be wrong here are
 * on the wire: the JSON shape sent, the headers, and how an error body is surfaced. Nothing leaves
 * the machine and no provider account is involved.
 */
class OpenAiCompatibleBackendTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String responseBody = chatResponse("QUERY: events/jdk.FileRead | count()");

  /** A backend pointed at the stub, with no key required. */
  private static final class TestBackend extends OpenAiCompatibleBackend {
    TestBackend() {
      super(
          new Profile(
              "test",
              "Test endpoint",
              "http://unused",
              "test-default-model",
              List.of("TEST_KEY_THAT_IS_NOT_SET"),
              false,
              "no help needed"));
    }
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastPath.set(exchange.getRequestURI().getPath());
          lastAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("content-type", "application/json");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private LlmConfig config(Map<String, String> extra) {
    Map<String, String> settings = new HashMap<>(extra);
    settings.putIfAbsent("llm.base-url", baseUrl);
    return new LlmConfig(settings::get);
  }

  private static LlmRequest request() {
    return new LlmRequest(
        "SYSTEM PREFIX", List.of(LlmRequest.Turn.user("which threads?")), 512, "ask");
  }

  private static String chatResponse(String content) {
    return """
        {"id":"x","model":"served-model","choices":[{"index":0,"finish_reason":"stop",
         "message":{"role":"assistant","content":%s}}],
         "usage":{"prompt_tokens":120,"completion_tokens":18,
                  "prompt_tokens_details":{"cached_tokens":100}}}
        """
        .formatted(
            com.google.gson.JsonParser.parseString("\"" + content.replace("\"", "\\\"") + "\""));
  }

  @Test
  void sendsTheSystemPrefixAsASystemMessageAndTurnsInOrder() throws Exception {
    new TestBackend().complete(request(), config(Map.of()));

    String body = lastBody.get();
    assertTrue(body.contains("\"role\":\"system\""), body);
    assertTrue(body.contains("SYSTEM PREFIX"), body);
    assertTrue(body.contains("\"role\":\"user\""), body);
    assertTrue(body.contains("which threads?"), body);
    assertTrue(body.contains("\"max_tokens\":512"), body);
    assertTrue(body.contains("\"stream\":false"), body);
    assertEquals("/v1/chat/completions", lastPath.get());
  }

  @Test
  void usesTheBackendDefaultModelUnlessConfigured() throws Exception {
    new TestBackend().complete(request(), config(Map.of()));
    assertTrue(lastBody.get().contains("\"model\":\"test-default-model\""), lastBody.get());

    new TestBackend().complete(request(), config(Map.of("llm.model", "llama3.2")));
    assertTrue(lastBody.get().contains("\"model\":\"llama3.2\""), lastBody.get());
  }

  @Test
  void sendsNoAuthorizationHeaderWhenThereIsNoKey() throws Exception {
    new TestBackend().complete(request(), config(Map.of()));
    // A local model needs no credential, and sending an empty bearer breaks some servers.
    assertEquals(null, lastAuth.get());
  }

  @Test
  void sendsTheConfiguredKeyAsABearerToken() throws Exception {
    new TestBackend().complete(request(), config(Map.of("llm.api-key", "sk-test-value")));
    assertEquals("Bearer sk-test-value", lastAuth.get());
  }

  @Test
  void parsesContentUsageAndServedModel() throws Exception {
    LlmResponse response = new TestBackend().complete(request(), config(Map.of()));

    assertEquals("QUERY: events/jdk.FileRead | count()", response.text());
    assertEquals("served-model", response.model());
    assertEquals("stop", response.stopReason());

    LlmResponse.Usage usage = response.usage().orElseThrow();
    // Cached tokens are reported separately, so input excludes them and the figures add up the
    // way they do for the other backends.
    assertEquals(20, usage.inputTokens());
    assertEquals(18, usage.outputTokens());
    assertEquals(100, usage.cacheReadTokens());
  }

  @Test
  void toleratesAResponseWithNoUsageBlock() throws Exception {
    responseBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}";
    LlmResponse response = new TestBackend().complete(request(), config(Map.of()));

    assertEquals("hi", response.text());
    // A local server that reports nothing is not an error: there is no cost to report.
    assertEquals(0, response.usage().orElseThrow().totalTokens());
  }

  @Test
  void surfacesAnHttpErrorWithItsBodyAndARemedy() {
    status = 404;
    responseBody = "{\"error\":{\"message\":\"model 'nope' not found\"}}";

    LlmException e =
        assertThrows(
            LlmException.class, () -> new TestBackend().complete(request(), config(Map.of())));
    assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    assertTrue(e.getMessage().contains("not found"), e.getMessage());
    assertTrue(e.remedy().contains("llm.base-url"), e.remedy());
  }

  @Test
  void reportsAnUnparseableBodyRatherThanThrowingRaw() {
    responseBody = "not json at all";
    LlmException e =
        assertThrows(
            LlmException.class, () -> new TestBackend().complete(request(), config(Map.of())));
    assertTrue(
        e.getMessage().contains("parse") || e.remedy().contains("OpenAI-compatible"),
        e.getMessage() + " / " + e.remedy());
  }

  @Test
  void reportsAnEmptyChoicesArray() {
    responseBody = "{\"choices\":[]}";
    LlmException e =
        assertThrows(
            LlmException.class, () -> new TestBackend().complete(request(), config(Map.of())));
    assertTrue(e.getMessage().contains("no choices"), e.getMessage());
  }

  @Test
  void readinessProbesALocalEndpointAndReportsItReachable() {
    LlmBackend.Readiness readiness = new TestBackend().readiness(config(Map.of()));
    assertTrue(readiness.ready(), readiness.detail());
    assertTrue(readiness.detail().contains("reachable"), readiness.detail());
  }

  @Test
  void readinessReportsAnUnreachableLocalEndpointWithAFix() {
    LlmConfig config = new LlmConfig(Map.of("llm.base-url", "http://127.0.0.1:1/v1")::get);
    LlmBackend.Readiness readiness = new TestBackend().readiness(config);

    assertFalse(readiness.ready());
    assertTrue(readiness.detail().contains("Cannot reach"), readiness.detail());
  }

  @Test
  void ollamaDefaultsAreLocalAndKeyless() {
    OllamaBackend ollama = new OllamaBackend();
    assertEquals("ollama", ollama.id());
    // No key required: readiness must not fail for a missing credential, only for an absent server.
    LlmBackend.Readiness readiness =
        ollama.readiness(new LlmConfig(Map.of("llm.base-url", "http://127.0.0.1:1/v1")::get));
    assertFalse(readiness.ready());
    assertTrue(readiness.detail().contains("Cannot reach"), readiness.detail());
    assertTrue(ollama.credentialHelp().contains("ollama serve"), ollama.credentialHelp());
  }

  @Test
  void openAiRequiresAKeyAndSaysSo() {
    OpenAiBackend openai = new OpenAiBackend();
    assertEquals("openai", openai.id());
    LlmBackend.Readiness readiness = openai.readiness(new LlmConfig(Map.<String, String>of()::get));
    if (System.getenv("OPENAI_API_KEY") == null) {
      assertFalse(readiness.ready());
      assertTrue(readiness.detail().contains("No API key"), readiness.detail());
      assertTrue(readiness.remedy().contains("OPENAI_API_KEY"), readiness.remedy());
    }
  }

  @Test
  void aTrailingSlashOnTheBaseUrlDoesNotProduceADoubleSlash() throws Exception {
    new TestBackend().complete(request(), config(Map.of("llm.base-url", baseUrl + "/")));
    assertEquals("/v1/chat/completions", lastPath.get());
  }
}
