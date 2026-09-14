package io.jafar.shell.llm.openai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmException;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A backend for any endpoint that speaks the OpenAI chat-completions protocol.
 *
 * <p>One adapter covers a lot of ground, because these providers differ by URL and credential
 * rather than by protocol: OpenAI itself, Ollama (local and cloud, which serve an OpenAI-compatible
 * API alongside their native one), vLLM, LM Studio, llama.cpp's server, and the hosted gateways.
 * Subclasses supply only a {@link Profile} — an id, a default endpoint, a default model and the
 * environment variables to read a key from.
 *
 * <p>It deliberately uses the JDK's {@link HttpClient} and Gson rather than a provider SDK. The
 * request is a handful of JSON fields, and staying dependency-free matters most for exactly the
 * user this backend serves: someone running a local model because nothing may leave the machine.
 *
 * <p><b>Verify before trusting the wire details.</b> The request and response shapes here follow
 * the widely-implemented chat-completions contract, but they were written without access to the
 * providers' live documentation. The fields consumed are the stable core — {@code model}, {@code
 * messages}, {@code max_tokens}, and {@code choices[0].message.content} — and unknown response
 * fields are ignored, so a provider that adds to the shape will still work.
 */
public abstract class OpenAiCompatibleBackend implements LlmBackend {

  private static final Gson GSON = new Gson();

  /**
   * What distinguishes one OpenAI-compatible provider from another.
   *
   * @param id backend id used by {@code llm.backend}
   * @param displayName shown by {@code llm status}
   * @param defaultBaseUrl endpoint root, without the {@code /chat/completions} suffix
   * @param defaultModel used when {@code llm.model} is unset
   * @param apiKeyEnvVars environment variables consulted for a key, in order
   * @param requiresKey whether a missing key makes the backend unusable
   * @param credentialHelp one line telling the user how to authenticate
   */
  public record Profile(
      String id,
      String displayName,
      String defaultBaseUrl,
      String defaultModel,
      List<String> apiKeyEnvVars,
      boolean requiresKey,
      String credentialHelp) {}

  private final Profile profile;
  private volatile HttpClient client;

  protected OpenAiCompatibleBackend(Profile profile) {
    this.profile = profile;
  }

  @Override
  public String id() {
    return profile.id();
  }

  @Override
  public String displayName() {
    return profile.displayName();
  }

  @Override
  public String defaultModel() {
    return profile.defaultModel();
  }

  @Override
  public String credentialHelp() {
    return profile.credentialHelp();
  }

  /** The endpoint root in use: the configured override, else this provider's default. */
  protected String baseUrl(LlmConfig config) {
    String configured = config.baseUrl();
    String base = configured != null ? configured : profile.defaultBaseUrl();
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** The API key in use, from shell configuration or the provider's environment variables. */
  protected Optional<String> apiKey(LlmConfig config) {
    String configured = config.apiKey();
    if (configured != null && !configured.isBlank()) {
      return Optional.of(configured);
    }
    for (String var : profile.apiKeyEnvVars()) {
      String value = System.getenv(var);
      if (value != null && !value.isBlank()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    Optional<String> key = apiKey(config);
    if (profile.requiresKey() && key.isEmpty()) {
      return Readiness.notReady(
          "No API key for " + profile.displayName() + ".", profile.credentialHelp());
    }

    String base = baseUrl(config);
    if (isLoopback(base)) {
      // A local server is either running or it is not, and that is worth knowing before a request
      // hangs. A remote endpoint is not probed: that would cost a round trip on every status call.
      return probeLocal(base, config)
          .map(error -> Readiness.notReady(error, profile.credentialHelp()))
          .orElseGet(() -> Readiness.ready("local endpoint " + base + " is reachable"));
    }

    return Readiness.ready(
        key.map(k -> "API key (" + mask(k) + ") for " + base)
            .orElse("no credentials needed, " + base));
  }

  /** Returns an error message when a local endpoint cannot be reached, or empty when it can. */
  private Optional<String> probeLocal(String base, LlmConfig config) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base + "/models"))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      HttpResponse<String> response =
          client(config).send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 500) {
        return Optional.empty();
      }
      return Optional.of(base + " answered HTTP " + response.statusCode() + ".");
    } catch (IOException e) {
      // ConnectException often carries a null message; the class name is the useful part then.
      String detail =
          e.getMessage() != null && !e.getMessage().isBlank()
              ? e.getMessage()
              : e.getClass().getSimpleName();
      return Optional.of("Cannot reach " + base + " (" + detail + ").");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("Interrupted probing " + base + ".");
    }
  }

  @Override
  public LlmResponse complete(LlmRequest request, LlmConfig config) throws LlmException {
    String model = config.modelFor(this);
    String url = baseUrl(config) + "/chat/completions";

    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", request.maxTokens());
    body.addProperty("stream", false);

    JsonArray messages = new JsonArray();
    messages.add(message("system", request.systemPrefix()));
    for (LlmRequest.Turn turn : request.messages()) {
      messages.add(
          message(turn.role() == LlmRequest.Role.USER ? "user" : "assistant", turn.text()));
    }
    body.add("messages", messages);

    HttpRequest.Builder http =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
    apiKey(config).ifPresent(key -> http.header("authorization", "Bearer " + key));

    HttpResponse<String> response;
    try {
      response = client(config).send(http.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      String detail =
          e.getMessage() != null && !e.getMessage().isBlank()
              ? e.getMessage()
              : e.getClass().getSimpleName();
      throw new LlmException("Could not reach " + url + ": " + detail, connectionRemedy(url), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Request to " + url + " was interrupted", null, e);
    }

    if (response.statusCode() != 200) {
      throw new LlmException(
          "LLM request failed: HTTP " + response.statusCode() + " — " + summarise(response.body()),
          remedyFor(response.statusCode(), model),
          null);
    }

    return parseResponse(response.body(), model);
  }

  private LlmResponse parseResponse(String body, String model) throws LlmException {
    try {
      JsonObject json = GSON.fromJson(body, JsonObject.class);
      JsonArray choices = json.getAsJsonArray("choices");
      if (choices == null || choices.isEmpty()) {
        throw new LlmException(
            "The endpoint returned no choices. Body: " + summarise(body),
            "Check that the configured model exists on this endpoint.",
            null);
      }
      JsonObject first = choices.get(0).getAsJsonObject();
      String text = "";
      if (first.has("message") && first.get("message").isJsonObject()) {
        JsonObject message = first.getAsJsonObject("message");
        if (message.has("content") && !message.get("content").isJsonNull()) {
          text = message.get("content").getAsString();
        }
      }
      String stopReason =
          first.has("finish_reason") && !first.get("finish_reason").isJsonNull()
              ? first.get("finish_reason").getAsString()
              : "";

      LlmResponse.Usage usage = usage(json);
      String servedModel =
          json.has("model") && !json.get("model").isJsonNull()
              ? json.get("model").getAsString()
              : model;
      return new LlmResponse(text.strip(), Optional.of(usage), servedModel, stopReason);

    } catch (LlmException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new LlmException(
          "Could not parse the endpoint's response: " + e.getMessage(),
          "The endpoint may not be OpenAI-compatible. Body: " + summarise(body),
          e);
    }
  }

  /**
   * Reads token usage, tolerating its absence.
   *
   * <p>Cache accounting differs by provider — Anthropic reports explicit cache reads, OpenAI
   * reports automatic prefix caching under {@code prompt_tokens_details.cached_tokens}, and a local
   * server usually reports nothing at all. Where it is absent the counts stay zero, which is
   * honest: a local model has no cost to report.
   */
  private LlmResponse.Usage usage(JsonObject json) {
    if (!json.has("usage") || !json.get("usage").isJsonObject()) {
      return new LlmResponse.Usage(0, 0, 0, 0);
    }
    JsonObject usage = json.getAsJsonObject("usage");
    long prompt = optLong(usage, "prompt_tokens");
    long completion = optLong(usage, "completion_tokens");
    long cached = 0;
    if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
      cached = optLong(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
    }
    // Report uncached input separately so the figures add up the way they do for other backends.
    return new LlmResponse.Usage(Math.max(0, prompt - cached), completion, cached, 0);
  }

  private static long optLong(JsonObject object, String field) {
    return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsLong() : 0;
  }

  private static JsonObject message(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content);
    return message;
  }

  private HttpClient client(LlmConfig config) {
    HttpClient local = client;
    if (local == null) {
      synchronized (this) {
        local = client;
        if (local == null) {
          local =
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(Math.min(10, config.timeoutSeconds())))
                  .followRedirects(HttpClient.Redirect.NORMAL)
                  .build();
          client = local;
        }
      }
    }
    return local;
  }

  private static boolean isLoopback(String url) {
    String lower = url.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("://localhost")
        || lower.contains("://127.0.0.1")
        || lower.contains("://[::1]");
  }

  private String connectionRemedy(String url) {
    if (isLoopback(url)) {
      return "Is the local server running? For Ollama: `ollama serve`, then `ollama pull "
          + profile.defaultModel()
          + "`.";
    }
    return "Check llm.base-url and network access to " + url + ".";
  }

  private String remedyFor(int status, String model) {
    return switch (status) {
      case 401, 403 -> "The endpoint rejected the credential. " + profile.credentialHelp();
      case 404 ->
          "Not found. The model '"
              + model
              + "' may not exist on this endpoint, or llm.base-url may be wrong "
              + "(it should end at /v1, without /chat/completions).";
      case 429 -> "Rate limited. Retry shortly, or use a smaller model via: set llm.model = ...";
      case 400 ->
          "The endpoint rejected the request. Some servers cap max_tokens per model; try "
              + "lowering llm.max-tokens.";
      default -> null;
    };
  }

  /** Trims a body for an error message: enough to diagnose, not enough to flood the terminal. */
  private static String summarise(String body) {
    if (body == null || body.isBlank()) {
      return "(empty body)";
    }
    String trimmed = body.strip().replaceAll("\\s+", " ");
    return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
  }

  private static String mask(String secret) {
    if (secret.length() <= 8) {
      return "****";
    }
    return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
  }
}
