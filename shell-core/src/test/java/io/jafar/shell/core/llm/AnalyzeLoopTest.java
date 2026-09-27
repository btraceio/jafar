package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The multi-step investigation loop.
 *
 * <p>{@code ask} translates a question into one query. That answers "how many", and almost no real
 * performance question is "how many" — the useful ones need a look, a narrowing, and a conclusion
 * drawn from what came back. This is that loop.
 *
 * <p>What has to hold: results actually reach the model, a bad query does not end the run, the
 * budget is enforced on both axes, and rows are redacted on the way out. The last one matters more
 * here than anywhere else in the feature, because this is the path that sends recording data
 * repeatedly rather than once.
 */
class AnalyzeLoopTest {

  /** Replies from a script, recording every request. */
  private static final class ScriptedBackend implements LlmBackend {
    private final List<String> replies;
    final List<LlmRequest> requests = new ArrayList<>();

    ScriptedBackend(String... replies) {
      this.replies = List.of(replies);
    }

    @Override
    public String id() {
      return "scripted";
    }

    @Override
    public String displayName() {
      return "Scripted";
    }

    @Override
    public String defaultModel() {
      return "scripted-v1";
    }

    @Override
    public Readiness readiness(LlmConfig config) {
      return Readiness.ready("fake");
    }

    @Override
    public LlmResponse complete(LlmRequest request, LlmConfig config) {
      String reply = replies.get(Math.min(requests.size(), replies.size() - 1));
      requests.add(request);
      return new LlmResponse(
          reply, Optional.of(new LlmResponse.Usage(100, 20, 0, 0)), "scripted-v1", "stop");
    }
  }

  private static final List<PromptBuilder.TypeEntry> INVENTORY =
      List.of(PromptBuilder.TypeEntry.documented("jdk.ExecutionSample", "Samples", null));

  private static LlmService service(LlmBackend backend, Map<String, String> settings) {
    return new LlmService(backend, new LlmConfig(settings::get));
  }

  private static LlmService.Investigation run(LlmService service, LlmService.QueryRunner runner)
      throws Exception {
    return service.analyze(
        "why slow?",
        "jfr",
        INVENTORY,
        LlmService.QueryValidator.NONE,
        LlmService.FieldLookup.NONE,
        runner,
        null);
  }

  @Test
  void itRunsSeveralQueriesThenConcludes() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend(
            "QUERY: events/jdk.ExecutionSample | count()",
            "QUERY: events/jdk.ExecutionSample | groupBy(sampledThread/javaName)",
            "ANSWER: main is hot.");
    LlmService service = service(backend, Map.of());
    List<String> ran = new ArrayList<>();

    LlmService.Investigation result =
        run(
            service,
            query -> {
              ran.add(query);
              return List.of(Map.of("count", 42));
            });

    assertTrue(result.complete());
    assertEquals("main is hot.", result.answer());
    assertEquals(2, ran.size(), "both queries should have run");
    assertEquals(2, result.steps().size());
  }

  @Test
  void resultsActuallyReachTheModel() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.ExecutionSample | count()", "ANSWER: done");
    LlmService service = service(backend, Map.of());

    run(service, query -> List.of(Map.of("thread", "worker-7", "count", 1234)));

    // A loop that runs queries and never shows the model the answers is just a slower `ask`.
    String secondRequest = backend.requests.get(1).messages().get(2).text();
    assertTrue(secondRequest.contains("worker-7"), secondRequest);
    assertTrue(secondRequest.contains("1234"), secondRequest);
  }

  @Test
  void rowsAreRedactedOnTheWayBack() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.FileRead | show()", "ANSWER: done");
    LlmService service = service(backend, Map.of());

    Map<String, Object> row = new HashMap<>();
    row.put("path", "/secrets/customer.key");
    row.put("count", 3);
    run(service, query -> List.of(row));

    String sent = backend.requests.get(1).messages().get(2).text();
    assertFalse(sent.contains("/secrets/customer.key"), sent);
    assertTrue(sent.contains(Redactor.PLACEHOLDER), sent);
    assertTrue(sent.contains("3"), "unredacted columns still go through");
  }

  @Test
  void aRejectedQueryIsFedBackRatherThanEndingTheRun() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("QUERY: this is not valid", "ANSWER: recovered");
    LlmService service = service(backend, Map.of());

    LlmService.Investigation result =
        service.analyze(
            "why slow?",
            "jfr",
            INVENTORY,
            query -> query.startsWith("events/") ? Optional.empty() : Optional.of("bad syntax"),
            LlmService.FieldLookup.NONE,
            query -> List.of(),
            null);

    assertTrue(result.complete());
    assertEquals("recovered", result.answer());
    assertEquals(1, result.steps().size());
    assertEquals("bad syntax", result.steps().get(0).error());
    assertTrue(backend.requests.get(1).messages().get(2).text().contains("bad syntax"));
  }

  @Test
  void aQueryThatThrowsIsAlsoRecoverable() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.X | count()", "ANSWER: recovered");
    LlmService service = service(backend, Map.of());

    LlmService.Investigation result =
        run(
            service,
            query -> {
              throw new IllegalStateException("no such type");
            });

    assertTrue(result.complete());
    assertEquals("no such type", result.steps().get(0).error());
  }

  @Test
  void theStepCapStopsALoopThatNeverConcludes() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("QUERY: events/jdk.ExecutionSample | count()");
    LlmService service = service(backend, Map.of("llm.max-steps", "3"));

    LlmService.Investigation result = run(service, query -> List.of(Map.of("count", 1)));

    assertFalse(result.complete(), "it never answered");
    assertNull(result.answer());
    assertEquals(3, backend.requests.size(), "exactly the cap, not one more");
  }

  @Test
  void theTokenCapStopsAnExpensiveRunEvenWithStepsLeft() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("QUERY: events/jdk.ExecutionSample | count()");
    // Each scripted reply reports 120 tokens, so a cap of 200 allows two before it bites.
    LlmService service =
        service(backend, Map.of("llm.max-steps", "10", "llm.max-total-tokens", "200"));

    LlmService.Investigation result = run(service, query -> List.of(Map.of("count", 1)));

    assertFalse(result.complete());
    assertTrue(backend.requests.size() < 10, "the cap must bite before the step limit");
  }

  @Test
  void theModelIsToldHowManyStepsRemain() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.ExecutionSample | count()", "ANSWER: done");
    LlmService service = service(backend, Map.of("llm.max-steps", "4"));

    run(service, query -> List.of(Map.of("count", 1)));

    // Being cut off mid-thought is a worse outcome than being asked to wrap up.
    assertTrue(backend.requests.get(1).messages().get(2).text().contains("step(s) remain"));
  }

  /** An analysis runner offering one analysis, recording what was asked for. */
  private static final class FakeAnalyses implements LlmService.AnalysisRunner {
    final List<String> ran = new ArrayList<>();

    @Override
    public List<String> available() {
      return List.of("diagnose");
    }

    @Override
    public Map<String, Object> run(String name) {
      ran.add(name);
      return Map.of(
          "headlines",
          List.of("HIGH GC PRESSURE: 609 collections"),
          "description",
          "Compare total pause against the recording wall clock.");
    }
  }

  private static LlmService.Investigation runWith(
      LlmService service, LlmService.QueryRunner runner, LlmService.AnalysisRunner analyses)
      throws Exception {
    return service.analyze(
        "why slow?",
        "jfr",
        INVENTORY,
        LlmService.QueryValidator.NONE,
        LlmService.FieldLookup.NONE,
        runner,
        analyses,
        null);
  }

  @Test
  void theModelCanRunABuiltInAnalysis() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("ANALYSIS: diagnose", "ANSWER: done");
    FakeAnalyses analyses = new FakeAnalyses();

    LlmService.Investigation result =
        runWith(service(backend, Map.of()), query -> List.of(), analyses);

    assertEquals(List.of("diagnose"), analyses.ran);
    assertTrue(result.complete());
    assertEquals("analysis:diagnose", result.steps().get(0).query());
  }

  @Test
  void whatTheAnalysisFoundReachesTheModel() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("ANALYSIS: diagnose", "ANSWER: done");

    runWith(service(backend, Map.of()), query -> List.of(), new FakeAnalyses());

    String second = backend.requests.get(1).messages().get(2).text();
    assertTrue(second.contains("HIGH GC PRESSURE: 609 collections"), second);
  }

  @Test
  void aFindingsOwnDescriptionIsNotRedacted() throws Exception {
    // `description` is redacted in an event row, where it can carry application data. In a Finding
    // it is Jafar's explanation of what it found, and redacting it keeps the numbers and throws
    // away the reasoning.
    ScriptedBackend backend = new ScriptedBackend("ANALYSIS: diagnose", "ANSWER: done");

    runWith(service(backend, Map.of()), query -> List.of(), new FakeAnalyses());

    String second = backend.requests.get(1).messages().get(2).text();
    assertTrue(second.contains("Compare total pause against the recording wall clock"), second);
  }

  @Test
  void askingForAnAnalysisThatDoesNotExistNamesTheOnesThatDo() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("ANALYSIS: telepathy", "ANSWER: fine");

    LlmService.Investigation result =
        runWith(service(backend, Map.of()), query -> List.of(), new FakeAnalyses());

    String second = backend.requests.get(1).messages().get(2).text();
    assertTrue(second.contains("no analysis called 'telepathy'"), second);
    assertTrue(second.contains("diagnose"), second);
    // A name it invented must not count as work done.
    assertTrue(result.steps().isEmpty(), result.steps().toString());
  }

  @Test
  void withNoAnalysesAvailableTheModelIsToldSoRatherThanFailing() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("ANALYSIS: diagnose", "ANSWER: fine");

    LlmService.Investigation result = run(service(backend, Map.of()), query -> List.of());

    assertTrue(result.complete());
    assertTrue(
        backend.requests.get(1).messages().get(2).text().contains("(none for this session)"));
  }

  @Test
  void anAnalysisVerbIsParsed() {
    assertEquals(AnalysisStep.Kind.ANALYSIS, AnalysisStep.parse("ANALYSIS: diagnose").kind());
    assertEquals("diagnose", AnalysisStep.parse("ANALYSIS: `diagnose`").text());
  }

  @Test
  void aDirectiveSmuggledInsideAQueryLineIsReadAsTheDirective() {
    // Seen in a real run: the model wrote "QUERY: FIELDS: jdk.types.StackFrame, jdk.types.Symbol".
    // Taken at face value that runs "FIELDS: ..." as a query and fails with "Unknown root:
    // FIELDS:",
    // spending a step to learn nothing.
    AnalysisStep step = AnalysisStep.parse("QUERY: FIELDS: jdk.types.StackFrame, jdk.types.Symbol");

    assertEquals(AnalysisStep.Kind.FIELDS, step.kind());
    assertEquals(List.of("jdk.types.StackFrame", "jdk.types.Symbol"), step.types());
  }

  @Test
  void anOrdinaryQueryIsUntouchedByThatRecovery() {
    AnalysisStep step = AnalysisStep.parse("QUERY: events/jdk.ExecutionSample | count()");

    assertEquals(AnalysisStep.Kind.QUERY, step.kind());
    assertEquals("events/jdk.ExecutionSample | count()", step.query());
  }

  @Test
  void aTruncatedDirectiveDoesNotLoopOrCrash() {
    assertEquals(AnalysisStep.Kind.UNKNOWN, AnalysisStep.parse("QUERY: QUERY:").kind());
  }

  @Test
  void anUnusableReplyIsNudgedRatherThanTreatedAsAnAnswer() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("I think we should look at the GC.", "ANSWER: ok");
    LlmService service = service(backend, Map.of());

    LlmService.Investigation result = run(service, query -> List.of());

    assertTrue(result.complete());
    assertTrue(
        backend.requests.get(1).messages().get(2).text().contains("no QUERY:, FIELDS: or ANSWER:"));
  }
}
