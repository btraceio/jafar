package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Command-level behaviour with a fake host.
 *
 * <p>These cover the degraded paths a user actually hits — no module, no credentials, feature
 * disabled — which are the ones most likely to be wrong and least likely to be noticed.
 *
 * <p>{@code llm-core} <em>is</em> on this module's test runtime classpath, so the real Anthropic
 * backend is discoverable here. Every test therefore pins {@code llm.backend} to an id that does
 * not exist, so no test can ever reach a real backend — without that, running the suite on a
 * machine with {@code ANTHROPIC_API_KEY} set would issue live, billable API calls.
 */
class LlmCommandsTest {

  private static final class FakeHost implements LlmCommands.Host {
    final List<String> output = new ArrayList<>();
    final Map<String, String> settings = new HashMap<>();
    final List<String> queriesRun = new ArrayList<>();
    String moduleId = "jfr";
    List<String> types = List.of("jdk.ExecutionSample", "jdk.FileRead");

    FakeHost() {
      // Never resolve a real backend from a unit test. See the class comment.
      settings.put("llm.backend", "test-nonexistent");
    }

    @Override
    public void println(String line) {
      output.add(line);
    }

    @Override
    public Optional<String> currentModuleId() {
      return Optional.ofNullable(moduleId);
    }

    @Override
    public List<String> availableTypes() {
      return types;
    }

    @Override
    public List<Map<String, Object>> runQuery(String query) {
      queriesRun.add(query);
      return List.of(Map.of("count", 42));
    }

    @Override
    public void renderRows(List<Map<String, Object>> rows) {
      output.add("[rows: " + rows.size() + "]");
    }

    @Override
    public String setting(String name) {
      return settings.get(name);
    }

    String text() {
      return String.join("\n", output);
    }
  }

  @Test
  void askWithoutAQuestionShowsUsage() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).ask("   ");
    assertTrue(host.text().contains("Usage: ask <question>"));
    assertTrue(host.queriesRun.isEmpty());
  }

  @Test
  void askReportsWhenDisabledRatherThanFailingObscurely() {
    FakeHost host = new FakeHost();
    host.settings.put("llm.enabled", "false");
    new LlmCommands(host).ask("why slow?");
    assertTrue(host.text().contains("disabled"));
    assertTrue(host.text().contains("set llm.enabled = true"));
    assertTrue(host.queriesRun.isEmpty(), "nothing may run when the feature is off");
  }

  @Test
  void askReportsAnUnknownBackendIdWithTheAvailableOnes() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).ask("why slow?");
    String text = host.text();
    assertTrue(text.contains("No LLM backend with id 'test-nonexistent'"), text);
    assertTrue(text.contains("Available:"), text);
    assertTrue(host.queriesRun.isEmpty());
  }

  @Test
  void theAnthropicBackendIsDiscoverableOnTheShellClasspath() {
    // Proves the ServiceLoader registration in llm-core is wired correctly, without making a
    // request: discovery is metadata only.
    assertTrue(
        io.jafar.shell.core.llm.LlmBackend.discover().stream()
            .anyMatch(b -> "anthropic".equals(b.id())),
        "llm-core should contribute the anthropic backend");
  }

  @Test
  void explainWithoutAPriorResultSaysSo() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).explain();
    assertTrue(host.text().contains("Nothing to explain yet"));
  }

  @Test
  void statusShowsConfigurationAndEveryDiscoveredBackend() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).status();
    String text = host.text();
    assertTrue(text.contains("Configuration"));
    assertTrue(text.contains("claude-opus-5"), "default model should be shown");
    assertTrue(text.contains("Backends"));
    // status lists what is installed regardless of the configured id, so a typo is visible.
    assertTrue(text.contains("anthropic"), text);
    // Readiness depends on the machine's credentials, so assert only that a verdict was printed.
    assertTrue(text.contains("READY"), text);
  }

  @Test
  void statusShowsRedactionOffProminently() {
    FakeHost host = new FakeHost();
    host.settings.put("llm.redact", "false");
    new LlmCommands(host).status();
    assertTrue(host.text().contains("OFF"));
  }

  @Test
  void dryRunWithoutAQuestionShowsUsage() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).llm(List.of("dry-run"));
    assertTrue(host.text().contains("Usage: llm dry-run"));
  }

  @Test
  void unknownSubcommandIsReported() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).llm(List.of("frobnicate"));
    assertTrue(host.text().contains("Unknown: llm frobnicate"));
  }

  @Test
  void bareLlmDefaultsToStatus() {
    FakeHost host = new FakeHost();
    new LlmCommands(host).llm(List.of());
    assertTrue(host.text().contains("Configuration"));
  }

  @Test
  void noteResultEnablesExplain() {
    FakeHost host = new FakeHost();
    LlmCommands commands = new LlmCommands(host);
    commands.noteResult("events/jdk.FileRead | count()", List.of(Map.of("count", 1)));
    commands.explain();
    // It cannot explain without a resolvable backend, but it must get past the guard.
    assertFalse(host.text().contains("Nothing to explain yet"));
    assertTrue(host.text().contains("No LLM backend with id"));
  }

  @Test
  void helpTextNamesTheCommandsAndTheAuthModes() {
    String help = LlmCommands.helpText();
    assertTrue(help.contains("ask <question>"));
    assertTrue(help.contains("llm dry-run"));
    // Provider-neutral: naming one vendor's environment variable here would go stale the moment a
    // second backend shipped, which is exactly what happened. 'llm status' is the live answer.
    assertTrue(help.contains("llm status"), help);
    assertTrue(help.contains("llm.backend"), help);
    assertTrue(help.contains("llm.base-url"), help);
    assertFalse(help.contains("%s"), "the template placeholder was never formatted: " + help);
  }
}
