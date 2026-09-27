package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code ?} as shorthand for {@code ask}, and the command names around it.
 *
 * <p>The prefix is taken before the line is split into words, so that {@code ?why is this slow} and
 * {@code ? why is this slow} are one command rather than two spellings of which only the second
 * works. No query can be shadowed by it: every JfrPath root is a bare word.
 *
 * <p>These assert on the usage text each command prints for an empty argument, which is the one
 * response that needs no backend — enough to prove the line reached the right handler.
 */
class QuestionPrefixTest {

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

  private String run(String line) {
    io.out.setLength(0);
    dispatcher.dispatch(line);
    return io.text();
  }

  @Test
  void bareQuestionMarkReachesAsk() {
    assertTrue(run("?").contains("Usage: ask [--dry-run] <question>"), run("?"));
  }

  @Test
  void questionMarkWithNoSpaceIsTheSameCommand() {
    // '?why is this slow' must not be read as a command called '?why'.
    String withSpace = run("? --dry-run");
    String withoutSpace = run("?--dry-run");
    assertEquals(withSpace, withoutSpace);
    assertFalse(withoutSpace.contains("Unknown command"), withoutSpace);
  }

  @Test
  void askIsTheInvestigation() {
    assertTrue(run("ask").contains("Usage: ask [--dry-run] <question>"), run("ask"));
  }

  @Test
  void analyzeAndInvestigateStillReachIt() {
    assertTrue(run("analyze").contains("Usage: ask [--dry-run] <question>"));
    assertTrue(run("investigate").contains("Usage: ask [--dry-run] <question>"));
  }

  @Test
  void asQueryIsTheOneShot() {
    String text = run("as-query");
    assertTrue(text.contains("Usage: as-query [--dry-run] <question>"), text);
    assertTrue(text.contains("use 'ask <question>'"), text);
  }

  @Test
  void aQueryIsUnaffected() {
    // No JfrPath root is spelled with a leading '?', so nothing legal is shadowed.
    String text = run("events/jdk.ExecutionSample | count()");
    assertFalse(text.contains("Usage: ask"), text);
  }

  @Test
  void helpRoutesForBothNamesAndTheShortcut() {
    assertTrue(run("help ask").contains("as-query"), run("help ask"));
    assertTrue(run("help as-query").contains("as-query"));
    assertTrue(run("help ?").contains("as-query"));
  }
}
