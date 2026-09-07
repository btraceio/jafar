package io.jafar.shell.core.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the shell's LLM features: builds prompts, applies redaction, calls a backend, and
 * accounts for what it cost.
 *
 * <p>The command layer talks to this class only, which is what keeps the Anthropic SDK, prompt
 * construction and redaction out of the shells. The same boundary is where the agentic mode will
 * attach: an {@code analyze} entry point joins {@link #ask} and {@link #explain} here, reusing the
 * redaction path, the usage accounting and the backend selection rather than duplicating them.
 */
public final class LlmService {

  private final LlmBackend backend;
  private final LlmConfig config;
  private final Redactor redactor;

  private LlmResponse.Usage sessionUsage = new LlmResponse.Usage(0, 0, 0, 0);
  private int requestCount;

  public LlmService(LlmBackend backend, LlmConfig config) {
    this.backend = backend;
    this.config = config;
    this.redactor = Redactor.from(config);
  }

  /**
   * Resolves the configured backend, if LLM support is installed and enabled.
   *
   * @return the service, or empty with a reason the caller should print
   */
  public static Result<LlmService> create(LlmConfig config) {
    if (!config.enabled()) {
      return Result.failure("LLM support is disabled.", "Enable it with: set llm.enabled = true");
    }
    Optional<LlmBackend> backend = LlmBackend.select(config.backendId());
    if (backend.isEmpty()) {
      List<LlmBackend> available = LlmBackend.discover();
      if (available.isEmpty()) {
        return Result.failure(
            "No LLM backend is installed.",
            "The llm-core module provides one; check that it is on the classpath.");
      }
      // Distinguishing these two matters: a typo in llm.backend and a missing module need
      // completely different fixes, and reporting both as "not installed" sends the user hunting
      // through their classpath for a problem that is one setting away.
      return Result.failure(
          "No LLM backend with id '" + config.backendId() + "'.",
          "Available: "
              + available.stream()
                  .map(LlmBackend::id)
                  .collect(java.util.stream.Collectors.joining(", "))
              + " — set llm.backend to one of these, or 'auto'.");
    }
    return Result.success(new LlmService(backend.get(), config));
  }

  public LlmBackend backend() {
    return backend;
  }

  public LlmConfig config() {
    return config;
  }

  /** Builds the request an {@code ask} would send, without sending it. Powers {@code dry-run}. */
  public LlmRequest buildAskRequest(
      String question, String moduleId, List<PromptBuilder.TypeEntry> inventory) {
    String language = LanguageReference.languageName(moduleId);
    String reference = LanguageReference.forModule(moduleId);
    return new LlmRequest(
        PromptBuilder.translationSystemPrompt(language, reference),
        List.of(LlmRequest.Turn.user(PromptBuilder.translationUserMessage(question, inventory))),
        config.maxTokens(),
        "ask");
  }

  /** Translates a question into a query proposal. */
  public QueryProposal ask(
      String question, String moduleId, List<PromptBuilder.TypeEntry> inventory)
      throws LlmException {
    LlmRequest request = buildAskRequest(question, moduleId, inventory);
    LlmResponse response = send(request);
    return QueryProposal.parse(response.text());
  }

  /**
   * Builds the request an {@code explain} would send, without sending it.
   *
   * <p>Rows are redacted and truncated here, so a dry-run shows exactly the bytes that a real call
   * would send — that equivalence is the whole value of the dry-run.
   */
  public LlmRequest buildExplainRequest(
      String query, List<Map<String, Object>> rows, String moduleId) {
    int total = rows.size();
    List<Map<String, Object>> shown =
        rows.size() > config.maxRows() ? rows.subList(0, config.maxRows()) : rows;
    List<Map<String, Object>> redacted = redactor.redactRows(shown);
    String language = LanguageReference.languageName(moduleId);
    return new LlmRequest(
        PromptBuilder.explanationSystemPrompt(language),
        List.of(
            LlmRequest.Turn.user(
                PromptBuilder.explanationUserMessage(query, redacted, total, redacted.size()))),
        config.maxTokens(),
        "explain");
  }

  /** Explains a result table. */
  public String explain(String query, List<Map<String, Object>> rows, String moduleId)
      throws LlmException {
    return send(buildExplainRequest(query, rows, moduleId)).text();
  }

  private LlmResponse send(LlmRequest request) throws LlmException {
    LlmBackend.Readiness readiness = backend.readiness(config);
    if (!readiness.ready()) {
      throw new LlmException(readiness.detail(), readiness.remedy());
    }
    LlmResponse response = backend.complete(request, config);
    response.usage().ifPresent(usage -> sessionUsage = sessionUsage.plus(usage));
    requestCount++;
    return response;
  }

  /** Token totals for this shell session. */
  public LlmResponse.Usage sessionUsage() {
    return sessionUsage;
  }

  public int requestCount() {
    return requestCount;
  }

  /** Either a value or a reason it is unavailable, with a remedy. */
  public record Result<T>(T value, String detail, String remedy) {
    public static <T> Result<T> success(T value) {
      return new Result<>(value, null, null);
    }

    public static <T> Result<T> failure(String detail, String remedy) {
      return new Result<>(null, detail, remedy);
    }

    public boolean isPresent() {
      return value != null;
    }
  }
}
