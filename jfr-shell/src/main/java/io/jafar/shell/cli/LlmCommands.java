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

    /** Runs a query against the current session and returns the rows. */
    List<Map<String, Object>> runQuery(String query) throws Exception;

    /** Renders rows the way the shell's own commands do. */
    void renderRows(List<Map<String, Object>> rows);

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

  // ── ask ───────────────────────────────────────────────────────────────────────

  /** Translates a question into a query, prints it, and runs it. */
  public void ask(String question) {
    if (question == null || question.isBlank()) {
      host.println("Usage: ask <question>");
      host.println("  e.g. ask which threads used the most CPU?");
      return;
    }

    LlmConfig config = config();
    LlmService.Result<LlmService> service = LlmService.create(config);
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
          service.value().ask(question, moduleId, inventory(), host::validateQuery);

      proposal.rationaleText().ifPresent(why -> host.println("# " + why));

      if (proposal.unanswerable()) {
        host.println(
            "The model reports this recording cannot answer that question. Nothing was run.");
        printUsage(service.value());
        return;
      }
      if (!proposal.hasQuery()) {
        host.println("No query could be extracted from the model's reply. Nothing was run.");
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
    host.renderRows(rows);
  }

  // ── explain ───────────────────────────────────────────────────────────────────

  /** Explains the most recent result. */
  public void explain() {
    if (lastQuery == null || lastRows == null) {
      host.println("Nothing to explain yet — run a query, or 'ask' a question, first.");
      return;
    }

    LlmService.Result<LlmService> service = LlmService.create(config());
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

  /** Dispatches {@code llm <subcommand>}. */
  public void llm(List<String> args) {
    String sub = args.isEmpty() ? "status" : args.get(0).toLowerCase(java.util.Locale.ROOT);
    switch (sub) {
      case "status" -> status();
      case "dry-run", "dryrun" -> dryRun(String.join(" ", args.subList(1, args.size())));
      case "cost" -> cost();
      default -> {
        host.println("Unknown: llm " + sub);
        host.println("Usage: llm [status | dry-run <question> | cost]");
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
    if (question == null || question.isBlank()) {
      host.println("Usage: llm dry-run <question>");
      return;
    }
    LlmConfig config = config();
    LlmService.Result<LlmService> service = LlmService.create(config);
    if (!service.isPresent()) {
      reportUnavailable(service);
      return;
    }
    String moduleId = host.currentModuleId().orElse("jfr");
    LlmRequest request = service.value().buildAskRequest(question, moduleId, inventory());

    host.println("Nothing was sent. This is exactly what an 'ask' would transmit.");
    host.println("");
    host.println("model      : " + config.model());
    host.println("backend    : " + service.value().backend().id());
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
    LlmService.Result<LlmService> service = LlmService.create(config());
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
    List<PromptBuilder.TypeEntry> entries = new ArrayList<>();
    for (String type : host.availableTypes()) {
      entries.add(PromptBuilder.TypeEntry.of(type));
    }
    return entries;
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
          ask <question>        Turn a question into a query, show it, and run it
          explain               Explain the most recent result
          llm status            Backends, readiness, credential source, settings
          llm dry-run <q>       Print exactly what 'ask' would send, and send nothing
          llm cost              Token usage for this process

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
        model is redacted by default, and 'llm dry-run' shows exactly what would be
        sent.""";
  }

  /** Exposed for tests: the redactor a given config would apply. */
  static Redactor redactorFor(LlmConfig config) {
    return Redactor.from(config);
  }
}
