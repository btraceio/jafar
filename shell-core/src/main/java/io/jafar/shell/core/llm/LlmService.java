package io.jafar.shell.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the shell's LLM features: builds prompts, applies redaction, calls a backend, and
 * accounts for what it cost.
 *
 * <p>The command layer talks to this class only, which is what keeps provider SDKs, prompt
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
  private int retryCount;
  private String lastValidationError;

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
    Optional<LlmBackend> backend = LlmBackend.select(config.backendId(), config);
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

  /** Translates a question into a query proposal, with no local validation. */
  public QueryProposal ask(
      String question, String moduleId, List<PromptBuilder.TypeEntry> inventory)
      throws LlmException {
    return ask(question, moduleId, inventory, QueryValidator.NONE);
  }

  /**
   * Translates a question into a query proposal, validating the result locally and asking for a
   * correction when it does not parse.
   *
   * <p>This is the difference between the feature working on a frontier model and working on a
   * small local one. The shell owns the query parser, so an invalid query can be caught before it
   * is ever run, and the parser's own error message is the most useful correction signal available
   * — far better than a generic "that was wrong". The retry is provider-independent: it costs
   * nothing on a model that gets it right first time, and rescues most of the failures on a model
   * that does not.
   *
   * @param validator checks a candidate query, returning an error message when it is invalid
   */
  public QueryProposal ask(
      String question,
      String moduleId,
      List<PromptBuilder.TypeEntry> inventory,
      QueryValidator validator)
      throws LlmException {

    // Cleared per call: a stale error from a previous ask, or from an earlier attempt in this
    // one, would make the caller refuse to run a query that is actually fine.
    lastValidationError = null;

    LlmRequest request = buildAskRequest(question, moduleId, inventory);
    LlmResponse response = send(request);
    QueryProposal proposal = QueryProposal.parse(response.text());

    int retriesLeft = config.maxRetries();
    List<LlmRequest.Turn> turns = new ArrayList<>(request.messages());

    while (retriesLeft > 0 && proposal.hasQuery()) {
      Optional<String> error = validator.validate(proposal.query());
      if (error.isEmpty()) {
        lastValidationError = null;
        return proposal;
      }
      lastValidationError = error.get();

      // Show the model its own output and the parser's complaint, then ask for one correction.
      turns.add(LlmRequest.Turn.assistant(response.text()));
      turns.add(
          LlmRequest.Turn.user(PromptBuilder.correctionMessage(proposal.query(), error.get())));

      response =
          send(
              new LlmRequest(
                  request.systemPrefix(), List.copyOf(turns), request.maxTokens(), "ask-retry"));
      proposal = QueryProposal.parse(response.text());
      retriesLeft--;
      retryCount++;
    }

    // Surface a still-invalid query rather than hiding it: the command layer prints the query and
    // the error, which is more useful than silently returning nothing. Re-validating here also
    // clears the error when the last attempt did in fact succeed.
    if (proposal.hasQuery()) {
      lastValidationError = validator.validate(proposal.query()).orElse(null);
    }
    return proposal;
  }

  /** Checks whether a candidate query is valid for the current session's language. */
  @FunctionalInterface
  public interface QueryValidator {
    /** Returns an error message when the query is invalid, or empty when it parses. */
    Optional<String> validate(String query);

    /** A validator that accepts everything, for callers with no parser to hand. */
    QueryValidator NONE = query -> Optional.empty();
  }

  /** The parse error from the most recent {@code ask}, when the final query still did not parse. */
  public Optional<String> lastValidationError() {
    return Optional.ofNullable(lastValidationError);
  }

  /** How many correction round-trips this service has made. */
  public int retryCount() {
    return retryCount;
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
