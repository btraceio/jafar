package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Discovering that a model reasons before it answers.
 *
 * <p>A reasoning model spends the output budget thinking before it writes anything, so the default
 * ceiling — sized for a query and one line of rationale — cuts it off mid-thought. The reply then
 * carries no query at all, and the tokens are billed for nothing. The first report of this was a
 * user seeing {@code [llm: 1357 in, 2048 out]} and the unhelpful "No query could be extracted",
 * with 2048 being exactly the ceiling.
 *
 * <p>The signal is the reply's own stop reason, not the model's name: a list of reasoning model
 * names would be stale within a month, and says nothing about a local model someone renamed.
 */
class ThinkingModelCeilingTest {

  /** Truncates at its ceiling until given enough room, like a model that thinks first. */
  private static final class ThinkingBackend implements LlmBackend {
    private final int tokensNeeded;
    final List<Integer> ceilingsSeen = new ArrayList<>();

    ThinkingBackend(int tokensNeeded) {
      this.tokensNeeded = tokensNeeded;
    }

    @Override
    public String id() {
      return "thinker";
    }

    @Override
    public String displayName() {
      return "Thinking model";
    }

    @Override
    public String defaultModel() {
      return "thinks-a-lot-v1";
    }

    @Override
    public Readiness readiness(LlmConfig config) {
      return Readiness.ready("fake");
    }

    @Override
    public LlmResponse complete(LlmRequest request, LlmConfig config) {
      ceilingsSeen.add(request.maxTokens());
      boolean enoughRoom = request.maxTokens() >= tokensNeeded;
      String text = enoughRoom ? "QUERY: events/jdk.FileRead | count()\nWHY: counts reads" : "hmm…";
      return new LlmResponse(
          text,
          Optional.of(new LlmResponse.Usage(1357, request.maxTokens(), 0, 0)),
          config.modelFor(this),
          enoughRoom ? "stop" : "length");
    }
  }

  private static LlmConfig config(Map<String, String> settings) {
    return new LlmConfig(settings::get);
  }

  private static LlmService service(LlmBackend backend, Map<String, String> settings) {
    return new LlmService(backend, config(settings));
  }

  @Test
  void aTruncatedReplyRaisesTheCeilingAndAsksAgain() throws Exception {
    ThinkingBackend backend = new ThinkingBackend(4000);
    LlmService service = service(backend, Map.of());

    QueryProposal proposal = service.ask("why slow?", "jfr", List.of());

    assertTrue(proposal.hasQuery(), "the retry at a higher ceiling should have produced a query");
    assertEquals(
        List.of(LlmConfig.DEFAULT_MAX_TOKENS, LlmConfig.MAX_TOKENS_WHEN_THINKING),
        backend.ceilingsSeen,
        "expected one cheap attempt, then one at the raised ceiling");
  }

  @Test
  void theRaiseIsReportedRatherThanAppliedSilently() throws Exception {
    ThinkingBackend backend = new ThinkingBackend(4000);
    LlmService service = service(backend, Map.of());

    service.ask("why slow?", "jfr", List.of());

    assertEquals(Optional.of(LlmConfig.MAX_TOKENS_WHEN_THINKING), service.autoRaisedTo());
  }

  @Test
  void theDiscoveryIsRememberedSoTheNextAskDoesNotPayForItAgain() throws Exception {
    ThinkingBackend backend = new ThinkingBackend(4000);
    LlmService service = service(backend, Map.of());

    service.ask("first question", "jfr", List.of());
    backend.ceilingsSeen.clear();
    service.ask("second question", "jfr", List.of());

    assertEquals(
        List.of(LlmConfig.MAX_TOKENS_WHEN_THINKING),
        backend.ceilingsSeen,
        "the second ask should start at the discovered ceiling, with no truncated attempt");
    assertFalse(service.autoRaisedTo().isPresent(), "nothing was raised on the second call");
  }

  @Test
  void anOrdinaryModelNeverPaysForTheRaise() throws Exception {
    ThinkingBackend backend = new ThinkingBackend(0); // answers immediately, whatever the ceiling
    LlmService service = service(backend, Map.of());

    service.ask("why slow?", "jfr", List.of());
    service.ask("and again?", "jfr", List.of());

    assertEquals(
        List.of(LlmConfig.DEFAULT_MAX_TOKENS, LlmConfig.DEFAULT_MAX_TOKENS),
        backend.ceilingsSeen,
        "a model that answers straight away must stay on the cheap ceiling");
  }

  @Test
  void anExplicitlyConfiguredCeilingAboveTheThinkingOneIsNotLowered() throws Exception {
    ThinkingBackend backend = new ThinkingBackend(4000);
    LlmService service = service(backend, Map.of("llm.max-tokens", "32768"));

    service.ask("why slow?", "jfr", List.of());

    assertEquals(
        List.of(32768),
        backend.ceilingsSeen,
        "a user who set a bigger ceiling should get it, and no second round trip");
    assertFalse(service.autoRaisedTo().isPresent());
  }
}
