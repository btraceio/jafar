package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.llm.PromptBuilder.FieldEntry;
import io.jafar.shell.core.llm.PromptBuilder.TypeEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two-round exchange: name the types, get their fields, then write the query.
 *
 * <p>JFR is self-describing — an event's fields are whatever that recording declares, and differ
 * between JDK versions and entirely for custom events — so a field name cannot be inferred from a
 * type name. Sending every type's fields up front would cost about 9,800 tokens on an ordinary
 * recording, nearly all of it about types the question never touches, and would grow without bound
 * on a recording full of custom events. So the model asks.
 */
class FieldRequestTest {

  /** Replies in sequence, recording what it was sent. */
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
          reply, Optional.of(new LlmResponse.Usage(10, 5, 0, 0)), "scripted-v1", "stop");
    }
  }

  private static final List<TypeEntry> INVENTORY =
      List.of(TypeEntry.documented("jdk.ExecutionSample", "Java Execution Sample", "Snapshot."));

  private static final LlmService.FieldLookup LOOKUP =
      names ->
          List.of(
              TypeEntry.event(
                  "jdk.ExecutionSample",
                  "Java Execution Sample",
                  "Snapshot.",
                  List.of(new FieldEntry("sampledThread", "java.lang.Thread"))),
              TypeEntry.fieldType(
                  "java.lang.Thread", List.of(new FieldEntry("javaName", "java.lang.String"))));

  private static LlmService service(LlmBackend backend) {
    return new LlmService(backend, new LlmConfig(Map.<String, String>of()::get));
  }

  @Test
  void aFieldsRequestIsAnsweredAndTheQueryComesBack() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend(
            "FIELDS: jdk.ExecutionSample",
            "QUERY: events/jdk.ExecutionSample | groupBy(sampledThread/javaName)\nWHY: ranks them");
    LlmService service = service(backend);

    QueryProposal proposal =
        service.ask("which threads?", "jfr", INVENTORY, LlmService.QueryValidator.NONE, LOOKUP);

    assertTrue(proposal.hasQuery(), "the second round should produce a query");
    assertEquals(2, backend.requests.size(), "exactly one extra round trip");
    assertEquals(1, service.fieldRounds());
  }

  @Test
  void theSecondRoundCarriesTheFieldsAndTheTypesTheyLeadTo() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("FIELDS: jdk.ExecutionSample", "QUERY: x\nWHY: y");
    LlmService service = service(backend);

    service.ask("which threads?", "jfr", INVENTORY, LlmService.QueryValidator.NONE, LOOKUP);

    String second = backend.requests.get(1).messages().get(2).text();
    assertTrue(second.contains("sampledThread: java.lang.Thread"), second);
    // Without the type a field leads to, a path like sampledThread/javaName is still a guess.
    assertTrue(second.contains("java.lang.Thread"), second);
    assertTrue(second.contains("javaName"), second);
  }

  @Test
  void theCachedPrefixIsUnchangedBetweenTheTwoRounds() throws Exception {
    ScriptedBackend backend =
        new ScriptedBackend("FIELDS: jdk.ExecutionSample", "QUERY: x\nWHY: y");
    LlmService service = service(backend);

    service.ask("which threads?", "jfr", INVENTORY, LlmService.QueryValidator.NONE, LOOKUP);

    assertEquals(
        backend.requests.get(0).systemPrefix(),
        backend.requests.get(1).systemPrefix(),
        "the second round must reuse the cached prefix, not rebuild it");
  }

  @Test
  void aModelThatKeepsAskingIsStoppedRatherThanLoopingAtTheUsersExpense() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("FIELDS: jdk.ExecutionSample");
    LlmService service = service(backend);

    QueryProposal proposal =
        service.ask("which threads?", "jfr", INVENTORY, LlmService.QueryValidator.NONE, LOOKUP);

    assertTrue(proposal.needsFields(), "the unmet request is reported, not swallowed");
    assertFalse(proposal.hasQuery());
    assertEquals(2, backend.requests.size(), "one request, one answer, then stop");
  }

  @Test
  void aDirectAnswerCostsNoExtraRoundTrip() throws Exception {
    ScriptedBackend backend = new ScriptedBackend("QUERY: events/jdk.CPULoad\nWHY: direct");
    LlmService service = service(backend);

    QueryProposal proposal =
        service.ask("cpu?", "jfr", INVENTORY, LlmService.QueryValidator.NONE, LOOKUP);

    assertTrue(proposal.hasQuery());
    assertEquals(1, backend.requests.size());
    assertEquals(0, service.fieldRounds());
  }

  @Test
  void aRequestIsCappedSoOneReplyCannotPullTheWholeRecording() {
    StringBuilder many = new StringBuilder("FIELDS:");
    for (int i = 0; i < 50; i++) {
      many.append(" jdk.Type").append(i).append(',');
    }

    QueryProposal proposal = QueryProposal.parse(many.toString());

    assertTrue(proposal.needsFields());
    assertEquals(PromptBuilder.MAX_FIELD_REQUEST, proposal.fieldsRequested().size());
  }

  @Test
  void aQueryWinsOverAFieldsLineInTheSameReply() {
    // If it already knows enough to write the query, there is nothing to fetch.
    QueryProposal proposal =
        QueryProposal.parse("FIELDS: jdk.CPULoad\nQUERY: events/jdk.CPULoad\nWHY: done");

    assertTrue(proposal.hasQuery());
    assertFalse(proposal.needsFields());
  }
}
