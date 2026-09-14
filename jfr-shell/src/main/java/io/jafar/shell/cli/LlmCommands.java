package io.jafar.shell.cli;

import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmException;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;
import io.jafar.shell.core.llm.LlmService;
import io.jafar.shell.core.llm.PromptBuilder;
import io.jafar.shell.core.llm.QueryProposal;
import io.jafar.shell.core.llm.Redactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code ask}, {@code explain} and {@code llm} commands.
 *
 * <p>Kept separate from {@link CommandDispatcher} and talking to the shell only through {@link
 * Host}, so the whole feature is unit-testable against a fake backend and a fake host — no network,
 * no session, no recording.
 *
 * <p>The command surface is deliberately small and honest about what it does: {@code ask} always
 * prints the query before running it, so the user sees and learns the query language rather than
 * being insulated from it, and a wrong query is visible rather than mysterious.
 */
public final class LlmCommands {

  /** Everything these commands need from the surrounding shell. */
  public interface Host {
    void println(String line);

    /** Module id of the current session ({@code jfr}, {@code hdump}, ...), or empty if none. */
    Optional<String> currentModuleId();

    /** Type names available in the current session; empty when no session is open. */
    List<String> availableTypes();

    /**
     * The same types, carrying whatever the artifact's metadata says each one is for.
     *
     * <p>JFR annotates event classes with {@code @Label} and {@code @Description}; feeding those to
     * the model is the difference between choosing a type on meaning and choosing it because its
     * name happened to contain a word from the question. Defaults to names only, so a format whose
     * metadata carries no documentation needs no implementation.
     */
    default List<PromptBuilder.TypeEntry> documentedTypes() {
      return availableTypes().stream().map(PromptBuilder.TypeEntry::of).toList();
    }

    /**
     * The fields of the named types, plus the types those fields lead to.
     *
     * <p>Answers the model's {@code FIELDS:} request. Empty by default: a format whose metadata
     * carries no field information simply never supplies any, and the model is told that rather
     * than left to guess.
     */
    default List<PromptBuilder.TypeEntry> fieldsOf(List<String> typeNames) {
      return List.of();
    }

    /** Analyses this session can run, e.g. {@code diagnose}. Empty when none apply. */
    default List<String> availableAnalyses() {
      return List.of();
    }

    /** Runs one of {@link #availableAnalyses()} and returns what it found. */
    default Map<String, Object> runAnalysis(String name) throws Exception {
      throw new UnsupportedOperationException(name);
    }

    /**
     * Records an investigation's queries as a re-runnable script.
     *
     * <p>The loop's weakest property is that it is not reproducible. Writing the queries it ran to
     * a {@code .jfrs} script turns that around: the conclusion may have been produced by a model,
     * but the evidence is a file a person can read, re-run, and disagree with. Default does
     * nothing, for a host with no recorder.
     */
    default void saveTranscript(String question, List<String> queries) {}

    /** Runs a query against the current session and returns the rows. */
    List<Map<String, Object>> runQuery(String query) throws Exception;

    /** Renders rows the way the shell's own commands do. */
    void renderRows(List<Map<String, Object>> rows);

    /**
     * Tells the shell which result is now the most recent, so {@code explain} describes it.
     *
     * <p>The shell keeps its own "last result" for queries typed directly, and primes this handler
     * from it. Without this call the reverse never happens: a query run by {@code ask} or {@code
     * analyze} left that memory untouched, so an {@code explain} afterwards described whichever
     * query the user had typed before — older, unrelated, and reported as if it were the one just
     * run. Default does nothing, for a host that keeps no such memory.
     */
    default void rememberResult(String query, List<Map<String, Object>> rows) {}

    /** Resolves a shell setting, e.g. {@code llm.model}. */
    String setting(String name);

    /**
     * Checks a candidate query against the current session's parser, returning an error message
     * when it is invalid.
     *
     * <p>The default accepts everything, so a host with no parser to hand still works. Supplying a
     * real one is what lets {@code ask} catch a bad query before running it and ask the model to
     * correct itself — the difference between this feature working and not working on a small local
     * model.
     */
    default Optional<String> validateQuery(String query) {
      return Optional.empty();
    }
  }

  private final Host host;

  /** Retained so {@code explain} can work on what the user just looked at. */
  private String lastQuery;

  private List<Map<String, Object>> lastRows;

  public LlmCommands(Host host) {
    this.host = host;
  }

  /** The host this instance talks to. Package-private: it exists so tests can drive the adapter. */
  Host host() {
    return host;
  }

  /** Records a query the user ran directly, so {@code explain} can describe it. */
  public void noteResult(String query, List<Map<String, Object>> rows) {
    this.lastQuery = query;
    this.lastRows = rows;
  }

  private LlmConfig config() {
    return new LlmConfig(host::setting);
  }

  private LlmService.Result<LlmService> cachedService;
  private boolean servicePinned;
  private String cachedBackendId;

  /**
   * The service for the configured backend, built once and kept for the session.
   *
   * <p>It used to be built per command, which quietly undid what the service learns: having
   * discovered that a model reasons before answering and raised its token ceiling, the next {@code
   * ask} started from scratch and paid for the truncated round trip again. The config it holds
   * reads settings live through {@code host::setting}, so a cached service still sees {@code set}
   * changes; only a different {@code llm.backend} needs a new one.
   */
  private LlmService.Result<LlmService> service(LlmConfig config) {
    if (servicePinned) {
      return cachedService;
    }
    String backendId = config.backendId();
    if (cachedService == null || !backendId.equals(cachedBackendId)) {
      cachedService = LlmService.create(config);
      cachedBackendId = backendId;
    }
    return cachedService;
  }

  /**
   * Runs these commands against a service the caller supplies, instead of discovering one.
   *
   * <p>Package-private, for tests: without it the command layer can only be exercised on the paths
   * that stop before a backend is reached, which leaves what the commands do with a *result* —
   * render it, remember it for {@code explain} — covered nowhere.
   */
  void pinService(LlmService service) {
    this.cachedService = LlmService.Result.success(service);
    this.servicePinned = true;
  }

  /** Whether {@code --dry-run} appears as a whole word in the argument. */
  private static boolean hasDryRunFlag(String argument) {
    if (argument == null) {
      return false;
    }
    for (String word : argument.trim().split("\\s+")) {
      if ("--dry-run".equals(word) || "--dryrun".equals(word)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The argument with the flag removed.
   *
   * <p>Removed wherever it appears, not just at the front: {@code ask which threads --dry-run} is a
   * thing people type, and silently treating the flag as part of the question would send the very
   * request they were trying not to send.
   */
  private static String stripDryRunFlag(String argument) {
    if (argument == null) {
      return null;
    }
    StringBuilder kept = new StringBuilder();
    for (String word : argument.trim().split("\\s+")) {
      if ("--dry-run".equals(word) || "--dryrun".equals(word)) {
        continue;
      }
      if (kept.length() > 0) {
        kept.append(' ');
      }
      kept.append(word);
    }
    return kept.toString();
  }

  // ── ask ───────────────────────────────────────────────────────────────────────

  /**
   * Translates a question into a query, prints it, and runs it.
   *
   * <p>{@code --dry-run} builds the identical request and prints it instead of sending it. It is a
   * flag rather than a separate command because it is a mode of this one: same question, same
   * bytes, differing only in whether they leave the machine.
   */
  public void asQuery(String argument) {
    boolean dryRun = hasDryRunFlag(argument);
    String question = stripDryRunFlag(argument);

    if (question == null || question.isBlank()) {
      host.println("Usage: as-query [--dry-run] <question>");
      host.println("  e.g. as-query which threads used the most CPU?");
      host.println("       as-query --dry-run which threads used the most CPU?");
      host.println("  For a question one query cannot answer, use 'ask <question>'.");
      return;
    }
    if (dryRun) {
      dryRunAsQuery(question);
      return;
    }

    LlmConfig config = config();
    LlmService.Result<LlmService> service = service(config);
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    if (host.currentModuleId().isEmpty()) {
      host.println("No session open. Use 'open <file>' first.");
      return;
    }

    String moduleId = host.currentModuleId().get();
    try {
      QueryProposal proposal =
          service.value().ask(question, moduleId, inventory(), host::validateQuery, host::fieldsOf);

      service
          .value()
          .autoRaisedTo()
          .ifPresent(
              ceiling ->
                  host.println(
                      "# This model reasons before answering; raised llm.max-tokens to "
                          + ceiling
                          + " for this session."));

      proposal.rationaleText().ifPresent(why -> host.println("# " + why));

      if (proposal.unanswerable()) {
        host.println(
            "The model reports this recording cannot answer that question. Nothing was run.");
        printUsage(service.value());
        return;
      }
      if (!proposal.hasQuery()) {
        explainMissingQuery(service.value(), config);
        printUsage(service.value());
        return;
      }

      String query = proposal.query();
      host.println("");
      host.println(query);
      host.println("");

      Optional<String> stillInvalid = service.value().lastValidationError();
      if (stillInvalid.isPresent()) {
        // The retry did not rescue it. Show the query and the parser's complaint rather than
        // running something known to be broken.
        host.println("That query does not parse: " + stillInvalid.get());
        host.println("Nothing was run. Try rephrasing, or write the query yourself.");
        printUsage(service.value());
        return;
      }

      if (config.confirmBeforeRun()) {
        host.println("(llm.confirm is on — copy the query above to run it)");
        printUsage(service.value());
        return;
      }

      runAndRender(query);
      printUsage(service.value());

    } catch (LlmException e) {
      reportLlmFailure(e);
    } catch (Exception e) {
      host.println("Query failed: " + e.getMessage());
      host.println("The query above came from the model; it may be invalid. Try rephrasing.");
      // The request was paid for whether or not the query ran, so report it either way.
      printUsage(service.value());
    }
  }

  private void runAndRender(String query) throws Exception {
    List<Map<String, Object>> rows = host.runQuery(query);
    noteResult(query, rows);
    host.rememberResult(query, rows);
    host.renderRows(rows);
  }

  // ── explain ───────────────────────────────────────────────────────────────────

  /** Explains the most recent result. {@code --dry-run} prints the request instead of sending. */
  public void explain(String argument) {
    if (hasDryRunFlag(argument)) {
      dryRunExplain();
      return;
    }
    explain();
  }

  /** Explains the most recent result. */
  public void explain() {
    if (lastQuery == null || lastRows == null) {
      host.println("Nothing to explain yet — run a query, or 'ask' a question, first.");
      return;
    }

    LlmService.Result<LlmService> service = service(config());
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }

    try {
      String moduleId = host.currentModuleId().orElse("jfr");
      String explanation = service.value().explain(lastQuery, lastRows, moduleId);
      host.println(explanation);
      printUsage(service.value());
    } catch (LlmException e) {
      reportLlmFailure(e);
    }
  }

  // ── llm ───────────────────────────────────────────────────────────────────────

  /**
   * Says where settings come from.
   *
   * <p>Printed even when there is no file, because "no settings file" is the answer to the question
   * someone asks when their file is not being read — and because it is the only place the shell can
   * name the path it looks at without the user guessing.
   */
  private void reportSettingsFile(LlmConfig config) {
    host.println("Settings file");
    host.println("-------------");
    var file = config.settingsFile();
    if (file.isEmpty()) {
      host.println("  none — create ~/.config/jafar/llm.properties to keep a key off the");
      host.println("  environment, then chmod 600 it. Keys are the same names 'set' uses:");
      host.println("      llm.backend=openai");
      host.println("      llm.api-key=sk-...");
    } else {
      host.println("  " + file.get().path());
      file.get().warning().ifPresent(w -> host.println("  !! " + w));
      // A key that resolves from somewhere other than the file is the thing people get wrong.
      for (String[] setting :
          new String[][] {
            {"llm.api-key", "JAFAR_LLM_API_KEY"},
            {"llm.backend", "JAFAR_LLM_BACKEND"},
            {"llm.model", "JAFAR_LLM_MODEL"},
            {"llm.base-url", "JAFAR_LLM_BASE_URL"},
          }) {
        LlmConfig.Source source = config.sourceOf(setting[0], setting[1]);
        if (source != LlmConfig.Source.DEFAULT) {
          host.println("  %-14s from %s".formatted(setting[0], describe(source, setting[1])));
        }
      }
    }
    host.println("");
  }

  private static String describe(LlmConfig.Source source, String envVar) {
    return switch (source) {
      case SHELL_VARIABLE -> "a 'set' command in this shell";
      case ENVIRONMENT -> envVar + " (overrides the settings file)";
      case SETTINGS_FILE -> "the settings file";
      case DEFAULT -> "the default";
    };
  }

  /** Dispatches {@code llm <subcommand>}. */
  public void llm(List<String> args) {
    String sub = args.isEmpty() ? "status" : args.get(0).toLowerCase(java.util.Locale.ROOT);
    switch (sub) {
      case "status" -> status();
      case "dry-run", "dryrun" -> dryRun(String.join(" ", args.subList(1, args.size())));
      case "cost" -> cost();
      default -> {
        host.println("Unknown: llm " + sub);
        host.println("Usage: llm [status | cost]   (dry-run moved to 'as-query --dry-run')");
      }
    }
  }

  /**
   * Reports which credential source will be used and what the settings are.
   *
   * <p>This exists because the SDK resolves credentials silently and does not fail fast when there
   * are none, so without it a misconfigured user's first signal is an opaque 401.
   */
  public void status() {
    LlmConfig config = config();
    host.println("Configuration");
    host.println("-------------");
    host.println(config.describe());
    host.println("");

    reportSettingsFile(config);

    List<LlmBackend> backends = LlmBackend.discover();
    host.println("Backends");
    host.println("--------");
    if (backends.isEmpty()) {
      host.println("  none installed — the llm-core module is not on the classpath");
      return;
    }
    for (LlmBackend backend : backends) {
      LlmBackend.Readiness readiness = backend.readiness(config);
      host.println(
          "  %-12s %-34s %s"
              .formatted(
                  backend.id(), backend.displayName(), readiness.ready() ? "READY" : "NOT READY"));
      host.println("               " + readiness.detail());
      host.println("               default model: " + backend.defaultModel());
      if (!readiness.ready()) {
        String remedy = readiness.remedy() != null ? readiness.remedy() : backend.credentialHelp();
        if (remedy != null) {
          host.println("               -> " + remedy);
        }
      }
    }
  }

  /**
   * Prints exactly what an {@code ask} would send, and sends nothing.
   *
   * <p>The bytes shown are the bytes that would go out: the same builder, the same redaction. That
   * equivalence is the point — it is what lets someone approve this feature for a machine holding
   * production recordings.
   */
  public void dryRun(String question) {
    // Retained for `llm dry-run`, which is an undocumented alias for `as-query --dry-run`.
    if (question == null || question.isBlank()) {
      host.println("Usage: as-query --dry-run <question>");
      return;
    }
    dryRunAsQuery(question);
  }

  /** Prints what an {@code ask} would send, and sends nothing. */
  private void dryRunAsQuery(String question) {
    LlmConfig config = config();
    LlmService.Result<LlmService> service = service(config);
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    String moduleId = host.currentModuleId().orElse("jfr");
    printRequest(
        "ask",
        service.value().buildAskRequest(question, moduleId, inventory()),
        config,
        service.value());
  }

  /**
   * Prints what an {@code explain} would send, and sends nothing.
   *
   * <p>This is the one that matters most for egress review, and until {@code --dry-run} became a
   * flag there was no way to reach it: {@code explain} is the command that puts recording-derived
   * result rows into a prompt, where {@code ask} sends only the question and the type names.
   */
  private void dryRunExplain() {
    if (lastQuery == null || lastRows == null) {
      host.println("Nothing to explain yet — run a query, or 'ask' a question, first.");
      return;
    }
    LlmConfig config = config();
    LlmService.Result<LlmService> service = service(config);
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    String moduleId = host.currentModuleId().orElse("jfr");
    printRequest(
        "explain",
        service.value().buildExplainRequest(lastQuery, lastRows, moduleId),
        config,
        service.value());
  }

  private void printRequest(
      String command, LlmRequest request, LlmConfig config, LlmService service) {
    host.println("Nothing was sent. This is exactly what an '" + command + "' would transmit.");
    host.println("");
    host.println("model      : " + config.modelFor(service.backend()));
    host.println("backend    : " + service.backend().id());
    host.println(
        "redaction  : "
            + (config.redactionEnabled()
                ? "on (" + String.join(", ", config.redactFields()) + ")"
                : "OFF — results would be sent verbatim"));
    host.println("characters : " + request.characterCount());
    host.println("");
    host.println("---------------- system (cached prefix) ----------------");
    host.println(request.systemPrefix());
    for (LlmRequest.Turn turn : request.messages()) {
      host.println("---------------- " + turn.role() + " ----------------");
      host.println(turn.text());
    }
    host.println("--------------------------------------------------------");
  }

  /** Shows what this session has spent so far. */
  public void cost() {
    LlmService.Result<LlmService> service = service(config());
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    LlmResponse.Usage usage = service.value().sessionUsage();
    if (service.value().requestCount() == 0) {
      host.println("No LLM requests have been made in this shell process.");
      host.println(
          "Note: usage is tracked per LlmService instance, so this resets between commands "
              + "until a persistent session is added.");
      return;
    }
    host.println("requests : " + service.value().requestCount());
    host.println("tokens   : " + usage);
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  private List<PromptBuilder.TypeEntry> inventory() {
    return host.documentedTypes();
  }

  /**
   * Says why a reply carried no query.
   *
   * <p>"No query could be extracted" is true but nearly useless: the common cause is that the model
   * hit {@code llm.max-tokens} while still reasoning, and the reply says so in its stop reason. The
   * shell used to read that field and throw it away, leaving the user to guess at a ceiling they
   * did not know existed.
   */
  private void explainMissingQuery(LlmService service, LlmConfig config) {
    String stop = service.lastResponse().map(LlmResponse::stopReason).orElse("");
    String text = service.lastResponse().map(LlmResponse::text).orElse("");

    if ("length".equalsIgnoreCase(stop) || "max_tokens".equalsIgnoreCase(stop)) {
      int ceiling = service.effectiveMaxTokens();
      host.println(
          "The reply stopped at the llm.max-tokens ceiling ("
              + ceiling
              + ") before it produced a query, even after raising it. Nothing was run.");
      host.println("    set llm.max-tokens = " + ceiling * 2 + "   # to go higher still");
      host.println("Or use a model that does less thinking: 'llm status' lists what is available.");
      return;
    }

    if (text.isBlank()) {
      host.println("The endpoint returned an empty reply. Nothing was run.");
      host.println(
          "Some models put their output in a separate reasoning field, which is not read here. "
              + "Try a different model, or 'as-query --dry-run' to check what is being sent.");
      return;
    }

    host.println("No query could be extracted from the model's reply. Nothing was run.");
    host.println("The model answered, but with no QUERY: line and no code block. It said:");
    host.println("    " + snippet(text));
  }

  /** First line or so of a reply, for an error message. */
  private static String snippet(String text) {
    String flat = text.strip().replaceAll("\\s+", " ");
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
  }

  /**
   * Investigates a question over several steps, showing the work.
   *
   * <p>Every query is printed before it runs, exactly as {@code ask} prints its one query. The
   * point is not to hide the investigation behind a conclusion: a reader who disagrees with the
   * answer needs to see which queries produced it, and a reader who agrees still has to be able to
   * re-run them.
   */
  public void analyze(String argument) {
    String question = stripDryRunFlag(argument);
    if (question.isBlank()) {
      host.println("Usage: ask [--dry-run] <question>   ('?' is short for it)");
      host.println("Runs several queries, reads each result, and concludes. 'as-query' is the");
      host.println("one-shot form; this one is for questions a single query cannot answer.");
      return;
    }

    LlmConfig config = config();
    if (config.confirmBeforeRun() && !hasDryRunFlag(argument)) {
      // llm.confirm says: show me a query before it runs. An investigation picks its next query
      // from the last result, so there is no honest way to honour that and still investigate.
      // Checked before the backend is resolved, so this costs nothing and sends nothing.
      host.println("llm.confirm is on, and 'ask' cannot show each of several queries first.");
      host.println("Use 'as-query' for one query you approve, or 'ask --dry-run' to see the first");
      host.println("request. Nothing was sent.");
      return;
    }
    LlmService.Result<LlmService> service = service(config);
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    if (host.currentModuleId().isEmpty()) {
      host.println("No session open. Use 'open <file>' first.");
      return;
    }
    String moduleId = host.currentModuleId().get();

    if (hasDryRunFlag(argument)) {
      host.println("Nothing was sent. This is the first request an 'analyze' would transmit;");
      host.println("later steps depend on what the earlier ones return, so they cannot be shown.");
      printRequest(
          "analyze",
          new LlmRequest(
              io.jafar.shell.core.llm.PromptBuilder.analysisSystemPrompt(
                  io.jafar.shell.core.llm.LanguageReference.languageName(moduleId),
                  io.jafar.shell.core.llm.LanguageReference.forModule(moduleId),
                  inventory(),
                  config.maxSteps()),
              List.of(LlmRequest.Turn.user("Question: " + question)),
              config.maxTokens(),
              "analyze"),
          config,
          service.value());
      return;
    }

    List<String> ranQueries = new ArrayList<>();
    // The loop hands the step callback a row count; the runner is where the rows themselves pass
    // through. Parking the last ones here lets the step line print first and its table under it.
    List<List<Map<String, Object>>> justRan = new ArrayList<>(1);
    String[] lastRan = {null};
    try {
      LlmService.Investigation result =
          service
              .value()
              .analyze(
                  question,
                  moduleId,
                  inventory(),
                  host::validateQuery,
                  host::fieldsOf,
                  query -> {
                    justRan.clear();
                    List<Map<String, Object>> rows = host.runQuery(query);
                    justRan.add(rows);
                    lastRan[0] = query;
                    return rows;
                  },
                  new LlmService.AnalysisRunner() {
                    @Override
                    public List<String> available() {
                      return host.availableAnalyses();
                    }

                    @Override
                    public Map<String, Object> run(String name) throws Exception {
                      return host.runAnalysis(name);
                    }
                  },
                  step -> {
                    host.println("");
                    host.println(
                        step.query().startsWith("analysis:")
                            ? "* " + step.query().substring("analysis:".length())
                            : "> " + step.query());
                    if (step.error() != null) {
                      host.println("  rejected: " + step.error());
                    } else {
                      if (step.query().startsWith("analysis:")) {
                        host.println("  done");
                      } else {
                        host.println(
                            "  " + step.rowCount() + (step.rowCount() == 1 ? " row" : " rows"));
                        ranQueries.add(step.query());
                        renderStepRows(justRan.isEmpty() ? List.of() : justRan.get(0), config);
                      }
                    }
                  });

      host.println("");
      if (result.answer() != null) {
        host.println(result.answer());
      } else {
        host.println(
            "The investigation ran out of budget before reaching a conclusion. "
                + "Raise llm.max-steps, or ask a narrower question.");
      }
      if (!ranQueries.isEmpty()) {
        host.saveTranscript(question, ranQueries);
      }
      if (lastRan[0] != null && !justRan.isEmpty()) {
        // So 'explain' after an 'analyze' describes the last thing the investigation looked at.
        noteResult(lastRan[0], justRan.get(0));
        host.rememberResult(lastRan[0], justRan.get(0));
      }
      printUsage(service.value());

    } catch (LlmException e) {
      host.println(e.getMessage());
      if (e.remedy() != null) {
        host.println("-> " + e.remedy());
      }
      printUsage(service.value());
    }
  }

  /**
   * Shows what a step actually returned, capped at {@code llm.max-rows}.
   *
   * <p>An investigation used to print only "3 rows", which is the one thing about a result that
   * cannot be checked. The numbers are the evidence for the conclusion underneath, and they are
   * already in memory — the same rows, and only as many as were sent to the model.
   */
  private void renderStepRows(List<Map<String, Object>> rows, LlmConfig config) {
    if (rows.isEmpty()) return;
    int cap = config.maxRows();
    host.renderRows(rows.size() > cap ? rows.subList(0, cap) : rows);
    if (rows.size() > cap) {
      host.println("  (" + cap + " of " + rows.size() + " rows shown)");
    }
  }

  private void printUsage(LlmService service) {
    LlmResponse.Usage usage = service.sessionUsage();
    if (usage.totalTokens() > 0) {
      host.println("");
      String corrections =
          service.retryCount() > 0 ? ", " + service.retryCount() + " correction(s)" : "";
      host.println("[llm: " + usage + corrections + "]");
    }
  }

  private void reportUnavailable(LlmService.Result<LlmService> result) {
    host.println(result.detail());
    if (result.remedy() != null) {
      host.println("  -> " + result.remedy());
    }
  }

  private void reportLlmFailure(LlmException e) {
    host.println(e.getMessage());
    if (e.remedy() != null) {
      host.println("  -> " + e.remedy());
    }
  }

  /** Help text, printed by the shell's {@code help} command. */
  public static String helpText() {
    return """
        LLM commands (require a backend module on the classpath, and for a hosted
        provider a credential):
          ask [--dry-run] <q>      Investigate over several queries and conclude
          as-query [--dry-run] <q> Turn a question into one query, show it, run it
          explain [--dry-run]      Explain the most recent result
          llm status               Backends, readiness, credential source, settings
          llm cost                 Token usage for this process

        '?' is short for 'ask' and takes the rest of the line, with or without a
        space: '?why is this slow' and 'ask why is this slow' are the same command.
        'analyze' and 'investigate' are word aliases for it.

        --dry-run builds the identical request and prints it instead of sending
        it. It is a flag rather than a command because it is a mode of the two
        verbs above: same input, same bytes, differing only in whether they
        leave the machine. On 'explain' it is the one worth reaching for, since
        that is the command that puts result rows into a prompt.

        'as-query' is one question, one query. 'ask' runs several: it reads each
        result and decides what to look at next, which is what most real questions
        need. It prints every query and the rows it returned, up to llm.max-rows —
        the same rows the model was given — and writes the queries to a re-runnable
        .jfrs script, so the conclusion can be checked rather than trusted. Its last
        result is what a following 'explain' describes. It is bounded by
        llm.max-steps and llm.max-total-tokens, and llm.confirm turns it off, since
        an investigation cannot ask before a query it has not decided on yet.

        The query language is whichever one the current session uses: JfrPath for a
        recording, HdumpPath for a heap dump, the samples grammar for pprof and OTLP.

        Settings (use 'set'):
          llm.enabled, llm.backend, llm.model, llm.base-url, llm.api-key,
          llm.max-tokens, llm.max-rows, llm.max-retries, llm.timeout,
          llm.confirm, llm.redact, llm.redact-fields

        Backends ship for Anthropic, OpenAI and Ollama, are discovered on the classpath,
        and are selected with llm.backend ('auto' takes the first that is ready).
        'llm status' lists them with how to authenticate to each. Point llm.base-url at
        any other OpenAI-compatible server to use that instead; with a local one nothing
        leaves the machine.

        A query the parser rejects is never run: the parser's error goes back to the
        model for a correction, up to llm.max-retries times. Recording data sent to the
        model is redacted by default, and --dry-run shows exactly what would be
        sent.

        Examples:
          ask why is this workload slow
          ? gc behaviour in detail
          as-query which threads used the most CPU?
          as-query what allocated the most bytes, by class?
          as-query show me file reads slower than 10ms
          explain                       # describe the result just printed
          as-query --dry-run which threads used the most CPU?
          explain --dry-run             # see the result rows before they are sent
          llm status                    # before the first question, to see what is used

          set llm.backend = ollama      # keep everything on this machine
          set llm.confirm = true        # print the query, do not run it
          set llm.max-rows = 10         # send fewer result rows to 'explain'""";
  }

  /** Exposed for tests: the redactor a given config would apply. */
  static Redactor redactorFor(LlmConfig config) {
    return Redactor.from(config);
  }
}
