package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;
import io.jafar.shell.core.llm.LlmService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the {@code ask} command does with a result, driven against a scripted backend.
 *
 * <p>Named for {@link LlmCommands#analyze}, which implements it: the method is named after what it
 * does and the command after what a user is doing.
 *
 * <p>{@link LlmCommandsTest} covers the paths that stop before a backend is reached. These are the
 * ones after: an investigation that ran a query has rows in hand, and used to print only how many
 * there were and then forget them.
 */
class AnalyzeCommandTest {

  /** Replies in order; the last reply repeats if the loop asks again. */
  private static final class ScriptedBackend implements LlmBackend {
    private final List<String> replies;
    int calls;

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
      String reply = replies.get(Math.min(calls++, replies.size() - 1));
      return new LlmResponse(
          reply, Optional.of(new LlmResponse.Usage(10, 5, 0, 0)), "scripted-v1", "stop");
    }
  }

  private static final class Host implements LlmCommands.Host {
    final List<String> output = new ArrayList<>();
    final Map<String, String> settings = new HashMap<>();
    final List<String> queriesRun = new ArrayList<>();
    final List<List<Map<String, Object>>> rendered = new ArrayList<>();
    String rememberedQuery;
    List<Map<String, Object>> rememberedRows;
    int rowsPerQuery = 3;

    @Override
    public void println(String line) {
      output.add(line);
    }

    @Override
    public Optional<String> currentModuleId() {
      return Optional.of("jfr");
    }

    @Override
    public List<String> availableTypes() {
      return List.of("jdk.GarbageCollection");
    }

    @Override
    public List<Map<String, Object>> runQuery(String query) {
      queriesRun.add(query);
      List<Map<String, Object>> rows = new ArrayList<>();
      for (int i = 0; i < rowsPerQuery; i++) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", "g" + i);
        row.put("sum", i * 100);
        rows.add(row);
      }
      return rows;
    }

    @Override
    public void renderRows(List<Map<String, Object>> rows) {
      rendered.add(rows);
      output.add("[rows: " + rows.size() + "]");
    }

    @Override
    public void rememberResult(String query, List<Map<String, Object>> rows) {
      rememberedQuery = query;
      rememberedRows = rows;
    }

    @Override
    public String setting(String name) {
      return settings.get(name);
    }

    String text() {
      return String.join("\n", output);
    }
  }

  private static LlmCommands commands(Host host, ScriptedBackend backend) {
    LlmCommands commands = new LlmCommands(host);
    commands.pinService(new LlmService(backend, new LlmConfig(host.settings::get)));
    return commands;
  }

  @Test
  void everyStepShowsTheRowsItGot() {
    Host host = new Host();
    ScriptedBackend backend =
        new ScriptedBackend(
            "QUERY: events/jdk.GarbageCollection | groupBy(name, agg=sum, value=sumOfPauses)",
            "ANSWER: G1New dominates.");

    commands(host, backend).analyze("gc behaviour");

    assertEquals(1, host.queriesRun.size());
    // The conclusion is the model's; the rows underneath it are the evidence, and used to be
    // reported only as a count.
    assertEquals(1, host.rendered.size(), host.text());
    assertEquals(3, host.rendered.get(0).size());
    assertTrue(host.text().contains("3 rows"), host.text());
    assertTrue(host.text().contains("G1New dominates."), host.text());
  }

  @Test
  void rowsAreCappedAtLlmMaxRows() {
    Host host = new Host();
    host.settings.put("llm.max-rows", "2");
    host.rowsPerQuery = 5;
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.GarbageCollection | count()", "ANSWER: done.");

    commands(host, backend).analyze("gc behaviour");

    assertEquals(2, host.rendered.get(0).size());
    assertTrue(host.text().contains("(2 of 5 rows shown)"), host.text());
  }

  @Test
  void theLastResultIsHandedBackSoExplainDescribesIt() {
    Host host = new Host();
    ScriptedBackend backend =
        new ScriptedBackend(
            "QUERY: events/jdk.GarbageCollection | count()", "ANSWER: nothing much.");

    commands(host, backend).analyze("gc behaviour");

    // Without this, 'explain' after an 'analyze' described whichever query the user had typed
    // before it — older, unrelated, and presented as the one just run.
    assertEquals("events/jdk.GarbageCollection | count()", host.rememberedQuery);
    assertEquals(3, host.rememberedRows.size());
  }

  @Test
  void aStepThatReturnedNothingRendersNoTable() {
    Host host = new Host();
    host.rowsPerQuery = 0;
    ScriptedBackend backend =
        new ScriptedBackend("QUERY: events/jdk.GarbageCollection | count()", "ANSWER: empty.");

    commands(host, backend).analyze("gc behaviour");

    assertTrue(host.rendered.isEmpty(), host.text());
    assertTrue(host.text().contains("0 rows"), host.text());
  }

  @Test
  void asQueryAlsoHandsBackTheResultItRan() {
    Host host = new Host();
    ScriptedBackend backend = new ScriptedBackend("QUERY: events/jdk.GarbageCollection | count()");

    commands(host, backend).asQuery("how many collections?");

    // 'ask' kept the result only on its own instance, while 'explain' was primed from the shell's
    // memory — so an 'explain' after an 'ask' described the last query the user had typed.
    assertEquals("events/jdk.GarbageCollection | count()", host.rememberedQuery);
    assertEquals(3, host.rememberedRows.size());
  }

  @Test
  void confirmModeRefusesBeforeAnythingIsSent() {
    Host host = new Host();
    host.settings.put("llm.confirm", "true");
    ScriptedBackend backend = new ScriptedBackend("QUERY: events/jdk.GarbageCollection | count()");

    commands(host, backend).analyze("gc behaviour");

    assertEquals(0, backend.calls, "llm.confirm means nothing leaves the machine unapproved");
    assertTrue(host.queriesRun.isEmpty());
    assertTrue(host.text().contains("llm.confirm is on"), host.text());
    assertTrue(host.text().contains("Nothing was sent"), host.text());
  }

  @Test
  void confirmModeStillAllowsDryRun() {
    Host host = new Host();
    host.settings.put("llm.confirm", "true");
    ScriptedBackend backend = new ScriptedBackend("ANSWER: unused");

    commands(host, backend).analyze("--dry-run gc behaviour");

    assertEquals(0, backend.calls);
    assertFalse(host.text().contains("llm.confirm is on"), host.text());
    assertTrue(host.text().contains("Nothing was sent."), host.text());
  }
}
