package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.live.JvmProcess;
import io.jafar.shell.live.LiveStreamView;
import io.jafar.shell.live.LiveStreams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * TUI bounded-output wiring (issue #92 hardening): a dispatcher built through the CANONICAL
 * constructor with a RAW {@link CommandDispatcher.TuiOutput}-marked IO must select the bounded-ring
 * {@code TailRunner.TuiSink} for {@code tail}; an unmarked IO must keep the straight-through {@code
 * TailRunner.PrintSink} (one println per event). Proves the marker survives the dispatcher's
 * error-counting wrapper.
 */
@Timeout(120)
class TuiTailOutputTest {

  /** Test event type recorded into the generated fixture. */
  static class RingEvent extends jdk.jfr.Event {
    int seq;
  }

  static Path testJfr;

  @BeforeAll
  static void generateRecording() throws Exception {
    testJfr = Files.createTempFile("tui-tail-test", ".jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.setToDisk(true);
      r.setDestination(testJfr);
      r.start();
      for (int i = 0; i < 50; i++) {
        RingEvent e = new RingEvent();
        e.seq = i;
        e.commit();
      }
      Thread.sleep(50);
      r.stop();
    }
    testJfr.toFile().deleteOnExit();
  }

  /** Recording IO that separates stdout lines from error-channel lines. */
  static class RecordingIO implements CommandDispatcher.TuiOutput {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }
  }

  static class PlainIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }
  }

  SessionManager<Session> sm;
  LiveStreamView attachedSession;
  io.jafar.parser.api.ParsingContext ctx;

  private LiveStreamView fakeLiveSession() throws Exception {
    io.jafar.shell.live.LiveJfrSession session =
        new io.jafar.shell.live.LiveJfrSession(
            4242, testJfr, "jfr-live", jdk.jfr.consumer.EventStream::openFile);
    session.start();
    return session;
  }

  private CommandDispatcher dispatcherWith(CommandDispatcher.IO io) throws Exception {
    ctx = ParsingContext.create();
    sm = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    CommandDispatcher dispatcher = new CommandDispatcher(sm, io, r -> {});
    attachedSession = fakeLiveSession();
    dispatcher.setLiveStreams(
        new LiveStreams() {
          @Override
          public List<JvmProcess> listLocalJvm() {
            return List.of(new JvmProcess(111, "com.example.App", testJfr));
          }

          @Override
          public Path findRepository(int pid) {
            return testJfr;
          }

          @Override
          public LiveStreamView attach(int pid, Path repository) {
            return attachedSession;
          }

          @Override
          public Path searchedBase() {
            return Path.of("/fake-base");
          }
        });
    return dispatcher;
  }

  @AfterEach
  void tearDown() throws Exception {
    if (sm != null) {
      sm.closeAll();
    }
    if (attachedSession != null) {
      attachedSession.close();
    }
  }

  @Test
  void markedIoStreamsThroughTheBoundedRing() throws Exception {
    RecordingIO io = new RecordingIO();
    CommandDispatcher dispatcher = dispatcherWith(io);
    assertTrue(dispatcher.dispatch("connect 111"), io.out.toString());
    io.out.setLength(0);
    io.err.setLength(0);
    assertTrue(dispatcher.dispatch("tail --limit 3"), io.out.toString());
    String lines = io.out.toString();
    // the ring flush at end: at most the limit's worth of event lines + the summary
    long eventLines =
        lines.lines().filter(l -> l.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d .*")).count();
    assertTrue(eventLines <= 3, "ring must bound output, got: " + lines);
    assertTrue(lines.contains("tail finished"), lines);
    assertTrue(lines.contains("limit reached"), lines);
  }

  @Test
  void unmarkedIoPrintsStraightThrough() throws Exception {
    PlainIO io = new PlainIO();
    CommandDispatcher dispatcher = dispatcherWith(io);
    assertTrue(dispatcher.dispatch("connect 111"), io.out.toString());
    io.out.setLength(0);
    assertTrue(dispatcher.dispatch("tail --limit 3"), io.out.toString());
    long eventLines =
        io.out
            .toString()
            .lines()
            .filter(l -> l.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d .*"))
            .count();
    assertEquals(3, eventLines, "PrintSink emits one line per event: " + io.out.toString());
  }
}
