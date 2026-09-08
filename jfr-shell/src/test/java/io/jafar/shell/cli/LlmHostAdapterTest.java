package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Guards the {@link LlmCommands.Host} adapter that {@link CommandDispatcher} supplies.
 *
 * <p>The reason this exists: the dispatcher has two ways to run a JfrPath query — through a {@code
 * JfrSelector} when one was supplied, and through {@code JfrPathEvaluator} directly when one was
 * not. The interactive shell builds it the second way, so an adapter that only knew about the
 * selector made {@code ask} unusable in the shell people actually type into, while every unit test
 * (which uses a fake host, not this adapter) stayed green. These tests drive the adapter itself.
 */
class LlmHostAdapterTest {

  private CommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory<JFRSession> factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          when(s.getFilePath()).thenReturn(path);
          when(s.getType()).thenReturn("jfr");
          when(s.getAvailableTypes()).thenReturn(java.util.Set.of("jdk.ExecutionSample"));
          return s;
        };
    SessionManager<JFRSession> sessions = new SessionManager<>(factory, ctx);
    CommandDispatcherTest.BufferIO io = new CommandDispatcherTest.BufferIO();
    // Three-arg constructor: no JfrSelector, exactly as io.jafar.shell.Shell builds it.
    dispatcher = new CommandDispatcher(sessions, io, r -> {});
    dispatcher.dispatch("open " + Path.of("does-not-need-to-exist.jfr"));
  }

  private LlmCommands.Host host() {
    LlmCommands commands = dispatcher.llmCommands();
    assertNotNull(commands);
    return commands.host();
  }

  @Test
  void runQueryDoesNotDeadEndWhenTheDispatcherHasNoSelector() {
    // The evaluator will fail on a mock session with no readable file, and that is fine: what must
    // not happen is the adapter refusing to try because no selector was supplied.
    Exception thrown =
        assertThrows(
            Exception.class, () -> host().runQuery("events/jdk.ExecutionSample | count()"));
    assertFalse(
        String.valueOf(thrown.getMessage()).contains("No query evaluator available"),
        "adapter fell through to the dead end instead of using JfrPathEvaluator: " + thrown);
  }

  @Test
  void validateQueryUsesTheRealParser() {
    assertTrue(host().validateQuery("events/jdk.ExecutionSample | count()").isEmpty());

    Optional<String> error = host().validateQuery("SELECT * FROM jdk.ExecutionSample");
    assertTrue(error.isPresent(), "a query the parser rejects must be reported");
    assertTrue(error.get().contains("SELECT"), error.get());
  }

  @Test
  void moduleIdAndTypesComeFromTheCurrentSession() {
    assertTrue(host().currentModuleId().isPresent());
    assertTrue(host().availableTypes().contains("jdk.ExecutionSample"));
  }
}
