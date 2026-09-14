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
  private LlmResponse lastResponse;

  // Raised once a reply proves the model reasons before answering; 0 until then. Session-scoped:
  // the discovery is about the model in use, and changing llm.model starts the question over.
  private int discoveredCeiling;
  private String discoveredFor;
  private boolean escalatedThisCall;

  /**
   * How many times one {@code ask} will answer a request for field metadata.
   *
   * <p>One is enough for the intended exchange — name the types, get the fields, write the query —
   * and a model that asks again after being told is looping, not learning.
   */
  private static final int MAX_FIELD_ROUNDS = 1;

  private int fieldRoundsUsed;

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
        PromptBuilder.translationSystemPrompt(language, reference, inventory),
        List.of(LlmRequest.Turn.user(PromptBuilder.translationUserMessage(question))),
        effectiveMaxTokens(),
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
  /**
   * Runs one of the shell's built-in analyses.
   *
   * <p>These carry judgement the query language does not — USE saturation, thread-state analysis,
   * the diagnosis thresholds. Before this the loop could only rebuild them, badly, out of queries.
   */
  public interface AnalysisRunner {
    /** The analyses this host can run, e.g. {@code diagnose}. Empty when none are available. */
    List<String> available();

    Map<String, Object> run(String name) throws Exception;

    AnalysisRunner NONE =
        new AnalysisRunner() {
          @Override
          public List<String> available() {
            return List.of();
          }

          @Override
          public Map<String, Object> run(String name) {
            throw new UnsupportedOperationException(name);
          }
        };
  }

  /** Runs a query and returns its rows. The loop's only way to see the recording. */
  @FunctionalInterface
  public interface QueryRunner {
    List<Map<String, Object>> run(String query) throws Exception;
  }

  /** One completed move, for the transcript and for showing the user what was done. */
  public record Step(String query, int rowCount, String error) {}

  /**
   * The outcome of an investigation.
   *
   * @param answer the model's conclusion, or null when it never reached one
   * @param steps every query actually run, in order — the replayable part
   * @param complete whether it answered, as opposed to running out of budget
   */
  public record Investigation(String answer, List<Step> steps, boolean complete) {
    public Investigation {
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  /**
   * Investigates a question over several steps.
   *
   * <p>{@code ask} translates; this one *looks*. It runs a query, reads the result, and decides
   * what to do next — which is what separates answering "how many execution samples are there" from
   * answering "why is this slow", and almost no real question is the former.
   *
   * <p>Bounded on two axes, because an unbounded loop against a paid API is a way to lose money
   * quietly: {@code llm.max-steps} caps the moves, and {@code llm.max-total-tokens} caps the spend
   * across the whole investigation. Both are checked before each request, and the model is told how
   * many steps remain so it can conclude rather than be cut off.
   *
   * <p>Every result goes through {@link Redactor} and the {@code llm.max-rows} cap on the way back,
   * exactly as {@code explain} does. This loop sends far more recording data than {@code ask} ever
   * does, so that matters more here, not less.
   */
  public Investigation analyze(
      String question,
      String moduleId,
      List<PromptBuilder.TypeEntry> inventory,
      QueryValidator validator,
      FieldLookup fields,
      QueryRunner runner,
      java.util.function.Consumer<Step> onStep)
      throws LlmException {
    return analyze(
        question, moduleId, inventory, validator, fields, runner, AnalysisRunner.NONE, onStep);
  }

  /** As above, with the shell's built-in analyses available to the model. */
  public Investigation analyze(
      String question,
      String moduleId,
      List<PromptBuilder.TypeEntry> inventory,
      QueryValidator validator,
      FieldLookup fields,
      QueryRunner runner,
      AnalysisRunner analyses,
      java.util.function.Consumer<Step> onStep)
      throws LlmException {
    int maxSteps = config.maxSteps();
    long tokenCap = config.maxTotalTokens();
    long startingTokens = sessionUsage.totalTokens();

    String language = LanguageReference.languageName(moduleId);
    String reference = LanguageReference.forModule(moduleId);
    String system = PromptBuilder.analysisSystemPrompt(language, reference, inventory, maxSteps);

    List<LlmRequest.Turn> turns = new ArrayList<>();
    turns.add(LlmRequest.Turn.user("Question: " + question));
    List<Step> steps = new ArrayList<>();

    for (int step = 0; step < maxSteps; step++) {
      if (tokenCap > 0 && sessionUsage.totalTokens() - startingTokens >= tokenCap) {
        return new Investigation(null, steps, false);
      }

      LlmResponse response =
          send(new LlmRequest(system, List.copyOf(turns), effectiveMaxTokens(), "analyze"));
      AnalysisStep move = AnalysisStep.parse(response.text());
      turns.add(LlmRequest.Turn.assistant(response.text()));
      int stepsLeft = maxSteps - step - 1;

      switch (move.kind()) {
        case ANSWER -> {
          return new Investigation(move.text(), steps, true);
        }
        case FIELDS ->
            turns.add(
                LlmRequest.Turn.user(PromptBuilder.fieldsMessage(fields.fieldsOf(move.types()))));
        case QUERY -> {
          Optional<String> invalid = validator.validate(move.query());
          if (invalid.isPresent()) {
            steps.add(new Step(move.query(), 0, invalid.get()));
            if (onStep != null) {
              onStep.accept(steps.get(steps.size() - 1));
            }
            turns.add(
                LlmRequest.Turn.user(
                    PromptBuilder.analysisQueryRejected(move.query(), invalid.get(), stepsLeft)));
            break;
          }
          List<Map<String, Object>> rows;
          try {
            rows = runner.run(move.query());
          } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            steps.add(new Step(move.query(), 0, detail));
            if (onStep != null) {
              onStep.accept(steps.get(steps.size() - 1));
            }
            turns.add(
                LlmRequest.Turn.user(
                    PromptBuilder.analysisQueryRejected(move.query(), detail, stepsLeft)));
            break;
          }
          steps.add(new Step(move.query(), rows.size(), null));
          if (onStep != null) {
            onStep.accept(steps.get(steps.size() - 1));
          }
          int total = rows.size();
          List<Map<String, Object>> shown =
              total > config.maxRows() ? rows.subList(0, config.maxRows()) : rows;
          turns.add(
              LlmRequest.Turn.user(
                  PromptBuilder.analysisResultMessage(
                      move.query(), redactor.redactRows(shown), total, shown.size(), stepsLeft)));
        }
        case ANALYSIS -> {
          String name = move.text();
          if (!analyses.available().contains(name)) {
            turns.add(
                LlmRequest.Turn.user(
                    PromptBuilder.analysisUnavailable(name, analyses.available(), stepsLeft)));
            break;
          }
          Map<String, Object> outcome;
          try {
            outcome = analyses.run(name);
          } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            steps.add(new Step("analysis:" + name, 0, detail));
            if (onStep != null) {
              onStep.accept(steps.get(steps.size() - 1));
            }
            turns.add(
                LlmRequest.Turn.user(
                    PromptBuilder.analysisQueryRejected("ANALYSIS: " + name, detail, stepsLeft)));
            break;
          }
          steps.add(new Step("analysis:" + name, outcome.size(), null));
          if (onStep != null) {
            onStep.accept(steps.get(steps.size() - 1));
          }
          // Analysis output is recording-derived too — method names, thread names, paths — so it
          // takes the same egress path as query rows rather than a shorter one.
          Map<String, Object> redacted =
              Redactor.forAnalysis(config).redactRows(List.of(outcome)).get(0);
          turns.add(
              LlmRequest.Turn.user(
                  PromptBuilder.analysisResultMessage(
                      name, redacted, stepsLeft, config.maxAnalysisChars())));
        }
        case UNKNOWN ->
            turns.add(
                LlmRequest.Turn.user(
                    "That reply had no QUERY:, FIELDS: or ANSWER: line. "
                        + (stepsLeft <= 0
                            ? "No steps remain — answer now with ANSWER:.\n"
                            : stepsLeft + " step(s) remain.\n")));
      }
    }
    return new Investigation(null, steps, false);
  }

  /** Supplies the fields of named types, for a model that asked before guessing. */
  @FunctionalInterface
  public interface FieldLookup {
    List<PromptBuilder.TypeEntry> fieldsOf(List<String> typeNames);

    /** No metadata available: the model is told so rather than left waiting. */
    FieldLookup NONE = names -> List.of();
  }

  public QueryProposal ask(
      String question,
      String moduleId,
      List<PromptBuilder.TypeEntry> inventory,
      QueryValidator validator)
      throws LlmException {

    // Cleared per call: a stale error from a previous ask, or from an earlier attempt in this
    // one, would make the caller refuse to run a query that is actually fine.
    lastValidationError = null;

    return ask(question, moduleId, inventory, validator, FieldLookup.NONE);
  }

  /**
   * Asks, answering a request for field metadata if the model makes one.
   *
   * <p>Two rounds rather than one because JFR is self-describing: an event's fields are whatever
   * the recording declares, so they cannot be inferred from the type name, and sending every type's
   * fields up front costs about 9,800 tokens on an ordinary recording — nearly all of it about
   * types the question never touches, and unbounded on a recording full of custom events. The model
   * sees what each type is *for* in the cached prefix, names the few it needs, and gets their
   * fields.
   */
  public QueryProposal ask(
      String question,
      String moduleId,
      List<PromptBuilder.TypeEntry> inventory,
      QueryValidator validator,
      FieldLookup fields)
      throws LlmException {
    lastValidationError = null;
    fieldRoundsUsed = 0;

    escalatedThisCall = false;
    LlmRequest request = buildAskRequest(question, moduleId, inventory);
    LlmResponse response = send(request);
    QueryProposal proposal = QueryProposal.parse(response.text());

    // The model reasons before answering, and the modest default cut it off mid-thought. The reply
    // says so itself — no model-name list required — so raise the ceiling, remember it for this
    // model, and ask once more. Without this the user sees "no query could be extracted" and is
    // left to discover a setting they did not know existed.
    if (!proposal.hasQuery() && stoppedOnLength(response) && noteThinkingModel()) {
      escalatedThisCall = true;
      request = buildAskRequest(question, moduleId, inventory);
      response = send(request);
      proposal = QueryProposal.parse(response.text());
    }

    List<LlmRequest.Turn> conversation = new ArrayList<>(request.messages());
    while (proposal.needsFields() && fieldRoundsUsed < MAX_FIELD_ROUNDS) {
      fieldRoundsUsed++;
      List<PromptBuilder.TypeEntry> described = fields.fieldsOf(proposal.fieldsRequested());
      conversation.add(LlmRequest.Turn.assistant(response.text()));
      conversation.add(LlmRequest.Turn.user(PromptBuilder.fieldsMessage(described)));
      request =
          new LlmRequest(
              request.systemPrefix(), List.copyOf(conversation), effectiveMaxTokens(), "ask");
      response = send(request);
      proposal = QueryProposal.parse(response.text());
    }
    if (proposal.needsFields()) {
      // It kept asking. Better to say so than to loop at the user's expense.
      return proposal;
    }

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
  private static boolean stoppedOnLength(LlmResponse response) {
    String stop = response.stopReason();
    return "length".equalsIgnoreCase(stop) || "max_tokens".equalsIgnoreCase(stop);
  }

  /**
   * Records that the model in use reasons before answering.
   *
   * @return true when this changed anything — false if the ceiling is already at least as high, so
   *     a retry would send exactly the same request and waste a round trip
   */
  private boolean noteThinkingModel() {
    String model = config.modelFor(backend);
    if (LlmConfig.MAX_TOKENS_WHEN_THINKING <= effectiveMaxTokens()) {
      return false;
    }
    discoveredCeiling = LlmConfig.MAX_TOKENS_WHEN_THINKING;
    discoveredFor = model;
    return true;
  }

  /** The ceiling to send: what was discovered for this model, else what is configured. */
  public int effectiveMaxTokens() {
    String model = config.modelFor(backend);
    boolean stillTheSameModel = discoveredFor != null && discoveredFor.equals(model);
    return stillTheSameModel ? Math.max(discoveredCeiling, config.maxTokens()) : config.maxTokens();
  }

  /**
   * The ceiling this call raised itself to, when it discovered a reasoning model.
   *
   * <p>Reported rather than applied silently: the user configured a number, and something else
   * overriding it without saying so is the kind of thing that is impossible to debug later.
   */
  /** Whether this call spent a round trip fetching field metadata. */
  public int fieldRounds() {
    return fieldRoundsUsed;
  }

  public Optional<Integer> autoRaisedTo() {
    return escalatedThisCall ? Optional.of(effectiveMaxTokens()) : Optional.empty();
  }

  public Optional<String> lastValidationError() {
    return Optional.ofNullable(lastValidationError);
  }

  /**
   * The most recent reply from the backend.
   *
   * <p>Exposed so that a failure to find a query in it can say *why* — the reply carries {@code
   * finish_reason}, and discarding it turned "you hit the token ceiling mid-thought" into the far
   * less useful "no query could be extracted".
   */
  public Optional<LlmResponse> lastResponse() {
    return Optional.ofNullable(lastResponse);
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
        effectiveMaxTokens(),
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
    lastResponse = response;
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
