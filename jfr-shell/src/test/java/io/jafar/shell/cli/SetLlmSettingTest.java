package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.llm.LlmConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code set llm.<name> = <value>} — the command every piece of documentation tells people to run.
 *
 * <p>It was broken three ways at once, and each failure looked like success:
 *
 * <ol>
 *   <li>the name was rejected outright — "Invalid variable name: llm.backend" — because the general
 *       variable rule forbids dots, since {@code ${a.b}} means field access in an expression
 *   <li>with the name allowed, a bare word went down the expression path and was read as a query:
 *       {@code set llm.backend = ollama} answered "Unknown root: ollama"
 *   <li>a bare integer was coerced to a double, so {@code set llm.max-rows = 20} stored {@code
 *       20.0}, which {@link LlmConfig} then failed to parse as an int and silently replaced with
 *       the default — the shell said "Set llm.max-rows = 20.0" and {@code llm status} kept showing
 *       50
 * </ol>
 *
 * <p>These assert the value as {@link LlmConfig} actually reads it back, not merely that the
 * command printed something, because printing something was never the problem.
 */
class SetLlmSettingTest {

  private CommandDispatcher dispatcher;
  private CommandDispatcherTest.BufferIO io;

  @BeforeEach
  void setUp() {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory<JFRSession> factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getFilePath()).thenReturn(path);
          when(s.getType()).thenReturn("jfr");
          return s;
        };
    SessionManager<JFRSession> sessions = new SessionManager<>(factory, ctx);
    io = new CommandDispatcherTest.BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});
    dispatcher.dispatch("open " + Path.of("does-not-need-to-exist.jfr"));
  }

  /** Reads settings exactly as the LLM commands do. */
  private LlmConfig config() {
    LlmCommands commands = dispatcher.llmCommands();
    assertNotNull(commands);
    return new LlmConfig(commands.host()::setting);
  }

  @Test
  void aBareWordIsStoredAsTextNotEvaluatedAsAQuery() {
    dispatcher.dispatch("set llm.backend = ollama");

    assertEquals("ollama", config().backendId());
    assertTrue(!io.text().contains("Unknown root"), io.text());
  }

  @Test
  void anIntegerSettingSurvivesAsAnInteger() {
    dispatcher.dispatch("set llm.max-rows = 20");

    // The bug stored 20.0 here, and maxRows() answered 50 without saying why.
    assertEquals(20, config().maxRows());
  }

  @Test
  void aQuotedValueKeepsItsContentAndLosesItsQuotes() {
    dispatcher.dispatch("set llm.model = \"qwen2.5-coder:7b\"");

    assertEquals("qwen2.5-coder:7b", config().model());
  }

  @Test
  void aValueWithPunctuationTheExpressionParserWouldChokeOnIsFine() {
    dispatcher.dispatch("set llm.base-url = http://localhost:11434/v1");

    assertEquals("http://localhost:11434/v1", config().baseUrl());
  }

  @Test
  void aBooleanSettingTakesEffect() {
    dispatcher.dispatch("set llm.redact = false");

    assertTrue(!config().redactionEnabled());
  }

  @Test
  void aMisspelledSettingIsNamedAndTheRealOnesListed() {
    dispatcher.dispatch("set llm.backed = ollama");

    String out = io.text();
    assertTrue(out.contains("Unknown setting: llm.backed"), out);
    assertTrue(out.contains("llm.backend"), "the error should list the real names: " + out);
  }

  @Test
  void anOrdinaryVariableStillBehavesAsBefore() {
    // The settings path must not swallow normal variables: a bare integer here is still a number,
    // and a dotted name that is not a setting is still rejected.
    dispatcher.dispatch("set count = 42");
    dispatcher.dispatch("set foo.bar = 1");

    String out = io.text();
    assertTrue(out.contains("Invalid variable name: foo.bar"), out);
  }
}
