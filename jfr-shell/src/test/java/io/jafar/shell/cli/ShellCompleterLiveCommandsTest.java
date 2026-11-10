package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

/**
 * The live-commands completion default (issue #92 hardening, F5): a {@code ShellCompleter} built
 * with a REAL unflagged dispatcher — or with the {@code null} dispatcher every existing completer
 * test constructs — keeps offering {@code connect}/{@code tail}; only an explicitly flagged-off
 * dispatcher suppresses them, by EXACT name ({@code constants} still completes for {@code const}).
 */
class ShellCompleterLiveCommandsTest {

  private static List<String> firstWords(ShellCompleter completer) {
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, new DefaultParser().parse("", 0), candidates);
    List<String> words = new ArrayList<>();
    for (Candidate c : candidates) {
      if (!words.contains(c.value())) {
        words.add(c.value());
      }
    }
    return words;
  }

  private static ShellCompleter completerFor(CommandDispatcher dispatcher) {
    // SessionManager requires a non-null factory by contract; these tests only exercise command
    // completion, which never opens a session.
    return new ShellCompleter(new SessionManager<>((path, c) -> null, null), dispatcher);
  }

  @Test
  void nullDispatcherDefaultsToLiveCommandsEnabled() {
    List<String> words = firstWords(completerFor(null));
    assertTrue(words.contains("connect"), words.toString());
    assertTrue(words.contains("tail"), words.toString());
  }

  @Test
  void realUnflaggedDispatcherKeepsLiveCommands() {
    CommandDispatcher dispatcher =
        new CommandDispatcher(new SessionManager<Session>((path, c) -> null, null), null, r -> {});
    assertTrue(dispatcher.supportsLiveStreaming());
    List<String> words = firstWords(completerFor(dispatcher));
    assertTrue(words.contains("connect"), words.toString());
    assertTrue(words.contains("tail"), words.toString());
  }

  @Test
  void flaggedOffDispatcherSuppressesOnlyTheExactNames() {
    CommandDispatcher dispatcher =
        new CommandDispatcher(new SessionManager<Session>((path, c) -> null, null), null, r -> {});
    dispatcher.setLiveStreamingEnabled(false);
    List<String> words = firstWords(completerFor(dispatcher));
    assertFalse(words.contains("connect"), words.toString());
    assertFalse(words.contains("tail"), words.toString());
    // exact-name skip, never prefix filtering
    assertTrue(words.contains("constants"), words.toString());
    assertTrue(words.contains("close"), words.toString());
  }
}
