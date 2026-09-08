package io.jafar.shell.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmException;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;
import java.util.List;
import java.util.Optional;

/**
 * The Anthropic-backed {@link LlmBackend}, using the official Java SDK.
 *
 * <p><b>Both authentication modes come free.</b> {@code AnthropicOkHttpClient.fromEnv()} resolves
 * credentials in the SDK's documented order — {@code ANTHROPIC_API_KEY}, then {@code
 * ANTHROPIC_AUTH_TOKEN}, then the OAuth profile written by {@code ant auth login}, then Workload
 * Identity Federation, then the default profile on disk. So an API key and a keyless OAuth profile
 * are the same code path here, and neither needs configuration from us.
 *
 * <p>What the SDK does <em>not</em> do is fail fast when it finds no credentials at all: the client
 * constructs happily and the request goes out unauthenticated, surfacing as a 401 from the server.
 * That is why {@link #readiness} inspects the environment itself — a user with nothing configured
 * gets a local, actionable message instead.
 *
 * <p>The client is created lazily so that constructing this backend (which {@link
 * java.util.ServiceLoader} does at startup) never touches the network or the filesystem.
 */
public final class AnthropicBackend implements LlmBackend {

  private volatile AnthropicClient client;

  @Override
  public String id() {
    return "anthropic";
  }

  @Override
  public String displayName() {
    return "Anthropic API (anthropic-java)";
  }

  @Override
  public String defaultModel() {
    // Deliberately the strongest tier: a wrong query wastes the user's turn and teaches them the
    // wrong syntax, which costs more than the token difference. `set llm.model` overrides it.
    return "claude-opus-5";
  }

  @Override
  public String credentialHelp() {
    return "Set ANTHROPIC_API_KEY, or run `ant auth login` for keyless use.";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");

    // Both set is a hard failure: the SDK sends both and the API rejects the request. Catching it
    // locally turns a confusing 400 into a one-line fix.
    if (isSet(apiKey) && isSet(authToken)) {
      return Readiness.notReady(
          "Both ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN are set; the API rejects requests "
              + "carrying both.",
          "Unset one of them, e.g. unset ANTHROPIC_API_KEY");
    }

    // An empty-but-present key still wins its precedence slot and authenticates as empty, which
    // shadows an otherwise working OAuth profile. This is the most confusing failure of the lot.
    if (apiKey != null && apiKey.isBlank()) {
      return Readiness.notReady(
          "ANTHROPIC_API_KEY is set but empty. It still takes precedence over an OAuth profile "
              + "and authenticates as an empty key.",
          "Truly unset it: unset ANTHROPIC_API_KEY");
    }

    if (isSet(apiKey)) {
      return Readiness.ready("ANTHROPIC_API_KEY (environment)");
    }
    if (isSet(authToken)) {
      return Readiness.ready("ANTHROPIC_AUTH_TOKEN (environment)");
    }

    Optional<String> profile = CredentialDiagnostics.activeProfileDescription();
    if (profile.isPresent()) {
      return Readiness.ready(profile.get());
    }

    return Readiness.notReady(
        "No credentials found: no ANTHROPIC_API_KEY, no ANTHROPIC_AUTH_TOKEN, and no OAuth "
            + "profile on disk.",
        "Run `ant auth login` for keyless use, or export ANTHROPIC_API_KEY=...");
  }

  @Override
  public LlmResponse complete(LlmRequest request, LlmConfig config) throws LlmException {
    try {
      MessageCreateParams.Builder params =
          MessageCreateParams.builder()
              .model(config.modelFor(this))
              .maxTokens(request.maxTokens())
              // The system prefix is the query-language reference: large, and identical on every
              // call. Marking it ephemeral makes it a cache read after the first request, which is
              // most of the cost of this feature.
              .systemOfTextBlockParams(
                  List.of(
                      TextBlockParam.builder()
                          .text(request.systemPrefix())
                          .cacheControl(CacheControlEphemeral.builder().build())
                          .build()));

      for (LlmRequest.Turn turn : request.messages()) {
        switch (turn.role()) {
          case USER -> params.addUserMessage(turn.text());
          case ASSISTANT -> params.addAssistantMessage(turn.text());
        }
      }

      Message message = client().messages().create(params.build());
      return toResponse(message, config);

    } catch (RuntimeException e) {
      throw new LlmException(describeFailure(e), remedyFor(e), e);
    }
  }

  private LlmResponse toResponse(Message message, LlmConfig config) {
    StringBuilder text = new StringBuilder();
    for (ContentBlock block : message.content()) {
      block.text().ifPresent(t -> text.append(t.text()));
    }

    Usage usage = message.usage();
    LlmResponse.Usage accounting =
        new LlmResponse.Usage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens().orElse(0L),
            usage.cacheCreationInputTokens().orElse(0L));

    String stopReason = message.stopReason().map(Object::toString).orElse("");
    return new LlmResponse(
        text.toString().strip(), Optional.of(accounting), config.modelFor(this), stopReason);
  }

  private AnthropicClient client() {
    AnthropicClient local = client;
    if (local == null) {
      synchronized (this) {
        local = client;
        if (local == null) {
          local = AnthropicOkHttpClient.fromEnv();
          client = local;
        }
      }
    }
    return local;
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String describeFailure(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank()
        ? "LLM request failed: " + e.getClass().getSimpleName()
        : "LLM request failed: " + message;
  }

  /**
   * Maps the failures a user is most likely to hit to a concrete fix. The status codes matter more
   * than the exception type here, and the SDK reports them in the message.
   */
  private static String remedyFor(RuntimeException e) {
    String message =
        e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
    if (message.contains("401") || message.contains("authentication")) {
      return "Credentials were rejected. If you use an OAuth profile, its refresh token may have "
          + "expired — re-run `ant auth login`. Check `llm status` for which source is active.";
    }
    if (message.contains("403") || message.contains("permission")) {
      return "The credential is valid but not permitted for this model or workspace. "
          + "`ant auth status` shows the active workspace.";
    }
    if (message.contains("429") || message.contains("rate")) {
      return "Rate limited. Retry shortly, or use a smaller model via: set llm.model = ...";
    }
    if (message.contains("404") || message.contains("model")) {
      return "The configured model may not exist or is unavailable to this account. "
          + "Current setting: llm.model";
    }
    return null;
  }
}
