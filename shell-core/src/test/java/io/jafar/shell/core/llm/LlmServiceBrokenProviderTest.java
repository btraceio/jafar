package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A backend that loads and reports itself ready can still fail inside {@code complete()} — a
 * half-installed plugin whose SDK classes are missing fails with {@code NoClassDefFoundError}, not
 * with an {@code LlmException}. The shell must surface that as a reported failure naming the
 * backend, never as a raw provider exception or error escaping the command layer. Virtual-machine
 * errors still propagate.
 */
class LlmServiceBrokenProviderTest {

  /** Fails in {@code complete()} with the given throwable. */
  private static final class BrokenBackend implements LlmBackend {
    private final RuntimeException runtime;
    private final Error error;

    private BrokenBackend(RuntimeException runtime, Error error) {
      this.runtime = runtime;
      this.error = error;
    }

    static BrokenBackend runtime(String message) {
      return new BrokenBackend(new IllegalStateException(message), null);
    }

    static BrokenBackend linkage() {
      return new BrokenBackend(null, new NoClassDefFoundError("com/example/MissingSdk"));
    }

    static BrokenBackend fatal() {
      return new BrokenBackend(null, new ThreadDeath());
    }

    @Override
    public String id() {
      return "broken-provider";
    }

    @Override
    public String displayName() {
      return "Broken Provider";
    }

    @Override
    public String defaultModel() {
      return "broken-model";
    }

    @Override
    public Readiness readiness(LlmConfig config) {
      return Readiness.ready("ready");
    }

    @Override
    public LlmResponse complete(LlmRequest request, LlmConfig config) {
      if (runtime != null) {
        throw runtime;
      }
      throw error;
    }
  }

  /** Reports nothing for its model name — the {@code llm status}/{@code ask} display path. */
  private static final class NoModelBackend implements LlmBackend {
    @Override
    public String id() {
      return "no-model";
    }

    @Override
    public String displayName() {
      return "No Model";
    }

    @Override
    public String defaultModel() {
      throw new IllegalStateException("no default model configured");
    }

    @Override
    public Readiness readiness(LlmConfig config) {
      return Readiness.ready("ready");
    }

    @Override
    public LlmResponse complete(LlmRequest request, LlmConfig config) {
      return new LlmResponse(
          "QUERY: events/jdk.FileRead | count()\nWHY: counts reads",
          Optional.empty(),
          config.modelFor(this),
          "end_turn");
    }
  }

  private static LlmConfig config() {
    return new LlmConfig(k -> null);
  }

  @Test
  void providerRuntimeFailureBecomesLlmException() {
    LlmService service = new LlmService(BrokenBackend.runtime("sdk exploded"), config());

    LlmException e = assertThrows(LlmException.class, () -> service.ask("hi", "jfr", List.of()));
    assertTrue(e.getMessage().contains("broken-provider"), e.getMessage());
    assertTrue(e.getMessage().contains("sdk exploded"), e.getMessage());
    assertTrue(e.remedy() != null && !e.remedy().isBlank(), "a remedy is attached");
  }

  @Test
  void providerLinkageErrorBecomesLlmException() {
    LlmService service = new LlmService(BrokenBackend.linkage(), config());

    LlmException e =
        assertThrows(
            LlmException.class, () -> service.explain("events/x | count()", List.of(), "jfr"));
    assertTrue(e.getMessage().contains("broken-provider"), e.getMessage());
  }

  @Test
  void virtualMachineErrorsStillPropagate() {
    LlmService service = new LlmService(BrokenBackend.fatal(), config());

    assertThrows(ThreadDeath.class, () -> service.ask("hi", "jfr", List.of()));
  }

  @Test
  void unusableDefaultModelFallsBackInsteadOfThrowing() {
    // The dry-run and request-building paths call defaultModel() with no way to report failure;
    // they get a placeholder instead of an exception.
    assertEquals(LlmConfig.FALLBACK_MODEL, config().modelFor(new NoModelBackend()));
  }

  @Test
  void askStillWorksWhenDefaultModelFails() throws Exception {
    LlmService service = new LlmService(new NoModelBackend(), config());

    QueryProposal proposal =
        service.ask(
            "how many file reads?", "jfr", List.of(PromptBuilder.TypeEntry.of("jdk.FileRead")));

    assertTrue(proposal.hasQuery());
  }
}
