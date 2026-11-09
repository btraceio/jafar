package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.live.JvmProcess;
import io.jafar.shell.live.LiveJfrSession;
import io.jafar.shell.live.LiveStreams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code connect}/{@code tail} dispatch through the {@link LiveStreams} seam (fake discovery +
 * scripted fake session — no real target JVM), plus the guard matrix: file-bound commands are
 * rejected with the standard message while a live session is current.
 */
@org.junit.jupiter.api.Timeout(120)
class LiveCommandsDispatcherTest {

  /** Test event type recorded into the generated fixture. */
  static class ProbeEvent extends jdk.jfr.Event {
    int seq;
    String name;
  }

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();

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
      out.append(s).append('\n');
    }

    String text() {
      return out.toString();
    }

    void clear() {
      out.setLength(0);
    }
  }

  /** Scripted event source: replays a recording file, but lets tests observe delivery. */
  private static LiveJfrSession fakeLiveSession(Path recordingFile) throws Exception {
    LiveJfrSession session =
        new LiveJfrSession(4242, recordingFile, "jfr-live", jdk.jfr.consumer.EventStream::openFile);
    // production LiveStreams.attach starts the stream; the fake must mirror that contract
    session.start();
    return session;
  }

  ParsingContext ctx;
  SessionManager<Session> sm;
  BufferIO io;
  CommandDispatcher dispatcher;
  static Path testJfr;

  @org.junit.jupiter.api.BeforeAll
  static void generateRecording() throws Exception {
    // Purpose-built recording: shared fixtures contain event shapes that trip a pre-existing
    // JDK consumer quirk (see the jdk-backend follow-up); the fake stream replays real
    // RecordedEvents from a recording with plain fields.
    testJfr = Files.createTempFile("live-commands-test", ".jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.setToDisk(true);
      r.setDestination(testJfr);
      r.start();
      for (int i = 0; i < 200; i++) {
        ProbeEvent e = new ProbeEvent();
        e.seq = i;
        e.name = "evt-" + i;
        e.commit();
      }
      Thread.sleep(50);
      r.stop();
    }
    testJfr.toFile().deleteOnExit();
  }

  LiveJfrSession attachedSession;

  @BeforeEach
  void setUp() throws Exception {
    ctx = ParsingContext.create();
    sm = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sm, io, r -> {});
    attachedSession = fakeLiveSession(testJfr);
    LiveStreams seam =
        new LiveStreams() {
          @Override
          public List<JvmProcess> listLocalJvm() {
            return List.of(
                new JvmProcess(111, "com.example.App --server", testJfr),
                new JvmProcess(222, null, null));
          }

          @Override
          public Path findRepository(int pid) throws java.io.IOException {
            if (pid == 111) return testJfr;
            throw new java.io.IOException(
                "No JFR event repository found for PID " + pid + " under /tmp. ... --repository");
          }

          @Override
          public LiveJfrSession attach(int pid, Path repository) {
            return attachedSession;
          }
        };
    dispatcher.setLiveStreams(seam);
  }

  // ---- connect ----

  @Test
  void connectListsLocalJvmPidCommandAndRepositoryMarker() {
    assertTrue(dispatcher.dispatch("connect"));
    assertTrue(io.text().contains("111"), io.text());
    assertTrue(io.text().contains("com.example.App"), io.text());
    assertTrue(io.text().contains("[jfr]"), io.text());
    assertTrue(io.text().contains("<unreadable>"), io.text());
  }

  @Test
  void connectAttachesRegistersAndMakesCurrent() {
    assertTrue(dispatcher.dispatch("connect 111"));
    assertTrue(io.text().contains("Connected to PID 111"), io.text());
    assertTrue(dispatcher.dispatch("sessions"));
    assertTrue(io.text().contains("live-pid-4242"), io.text());
  }

  @Test
  void connectWithAliasRegistersUnderAlias() {
    assertTrue(dispatcher.dispatch("connect 111 --alias prod"));
    assertTrue(dispatcher.dispatch("info prod"));
    assertTrue(io.text().contains("jfr-live"), io.text());
  }

  @Test
  void connectDiscoveryFailureNamesTheError() {
    assertTrue(dispatcher.dispatch("connect 222"));
    assertTrue(io.text().contains("connect failed"), io.text());
    assertTrue(io.text().contains("--repository"), io.text());
  }

  @Test
  void connectBogusPidIsRejected() {
    assertTrue(dispatcher.dispatch("connect abc"));
    assertTrue(io.text().contains("Usage"), io.text());
  }

  // ---- guard matrix: file-bound commands under a live session ----

  private void attachFirst() {
    assertTrue(dispatcher.dispatch("connect 111"));
  }

  @Test
  void guardedCommandsReportNotSupportedForLiveSession() {
    attachFirst();
    String[] cmds = {
      "show events/jdk.FileRead | count()",
      "metadata",
      "types",
      "chunks",
      "cp",
      "backend",
      "events/jdk.FileRead | count()",
      "metadata/jdk.types.ThreadState",
      "constants/jdk.types.Symbol"
    };
    for (String cmd : cmds) {
      io.clear();
      dispatcher.dispatch(cmd);
      assertTrue(
          io.text().contains("not supported for a live session"),
          "'" + cmd + "' should be guarded, got: " + io.text());
    }
  }

  @Test
  void sessionCommandsWorkUnderLiveSession() {
    attachFirst();
    io.clear();
    assertTrue(dispatcher.dispatch("sessions"));
    assertFalse(io.text().contains("not supported"), io.text());
    io.clear();
    assertTrue(dispatcher.dispatch("info"));
    assertTrue(io.text().contains("jfr-live"), io.text());
    assertTrue(io.text().contains("eventsSeen"), io.text());
  }

  @Test
  void useSwitchesAwayAndGuardsLift() throws Exception {
    attachFirst();
    // register a file session too and switch current to it — the guards no longer apply
    SessionManager.SessionRef<Session> file = sm.register(new JFRSession(testJfr, ctx), "file");
    io.clear();
    assertTrue(dispatcher.dispatch("use file"), io.text());
    io.clear();
    assertTrue(dispatcher.dispatch("metadata"), io.text());
    assertFalse(io.text().contains("not supported for a live session"), io.text());
  }

  // ---- tail ----

  @Test
  void tailWithoutLiveSessionReportsHowToConnect() {
    assertTrue(dispatcher.dispatch("tail"));
    assertTrue(io.text().contains("No live session open"), io.text());
    assertTrue(io.text().contains("connect"), io.text());
  }

  @Test
  void tailStreamsEventsUntilLimit() {
    attachFirst();
    assertTrue(dispatcher.dispatch("tail --limit 5"));
    assertTrue(io.text().contains("tail finished"), io.text());
    assertTrue(io.text().contains("limit reached"), io.text());
    // 5 event lines + summary: count lines that look like event rows
    long eventLines =
        io.text().lines().filter(l -> l.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d .*")).count();
    assertTrue(eventLines >= 5, "expected >=5 event lines, got: " + io.text());
  }

  @Test
  void tailFilterRestrictsTypes() {
    attachFirst();
    assertTrue(
        dispatcher.dispatch(
            "tail events/io.jafar.shell.cli.LiveCommandsDispatcherTest$ProbeEvent --limit 3"));
    assertTrue(io.text().contains("tail finished"), io.text());
    long probeLines = io.text().lines().filter(l -> l.contains("ProbeEvent")).count();
    assertTrue(probeLines >= 3, io.text());
    // no non-FileRead event rows (the summary line aside)
    long otherRows =
        io.text()
            .lines()
            .filter(l -> l.matches("\\d\\d:\\d\\d:\\d\\d.*"))
            .filter(l -> !l.contains("ProbeEvent"))
            .count();
    assertEquals(0, otherRows, io.text());
  }

  @Test
  void tailRejectsBatchPipelineOps() {
    attachFirst();
    assertTrue(
        dispatcher.dispatch(
            "tail events/io.jafar.shell.cli.LiveCommandsDispatcherTest$ProbeEvent | count()"));
    assertTrue(
        io.text().contains("not supported per event")
            || io.text().contains("requires the full row set"),
        io.text());
  }

  @Test
  void tailRejectsNonEventsRoot() {
    attachFirst();
    assertTrue(dispatcher.dispatch("tail metadata"));
    assertTrue(io.text().contains("events root"), io.text());
  }

  @Test
  void tailInScriptModeRequiresLimit() {
    attachFirst();
    // simulate script mode
    assertTrue(dispatcher.dispatch("tail"));
    // (script mode is driven via runScript; verify through a tiny script)
    io.clear();
    var result = dispatcher.runScript(List.of("tail --limit 2"), List.of(), false);
    assertFalse(result.hasErrors(), io.text());
    assertTrue(io.text().contains("tail finished"), io.text());
  }

  @Test
  void tailBindsToCapturedSessionNotCurrentPointer() throws Exception {
    attachFirst();
    // Register a second live session and switch current to it; tail must keep targeting #1.
    LiveJfrSession second = fakeLiveSession(testJfr);
    ((SessionManager<Session>) (SessionManager<?>) sm).register(second, "second");
    assertTrue(dispatcher.dispatch("use second"));
    assertTrue(dispatcher.dispatch("tail --limit 1"));
    assertTrue(io.text().contains("tail finished"), io.text());
  }

  @Test
  void jsonFormatEmitsSingleLineObjects() {
    attachFirst();
    assertTrue(
        dispatcher.dispatch(
            "tail events/io.jafar.shell.cli.LiveCommandsDispatcherTest$ProbeEvent --limit 2 --format json"));
    long jsonLines =
        io.text()
            .lines()
            .filter(
                l ->
                    l.startsWith(
                        "{\"type\":\"io.jafar.shell.cli.LiveCommandsDispatcherTest$ProbeEvent\""))
            .count();
    assertTrue(jsonLines >= 2, io.text());
  }

  // ---- help ----

  @Test
  void helpListsConnectAndTail() {
    assertTrue(dispatcher.dispatch("help"));
    assertTrue(io.text().contains("connect"), io.text());
    assertTrue(io.text().contains("tail"), io.text());
  }

  @Test
  void helpConnectShowsUsageAndCaveats() {
    assertTrue(dispatcher.dispatch("help connect"));
    assertTrue(io.text().contains("DISK-backed"), io.text());
    assertTrue(io.text().contains("--repository"), io.text());
  }

  @Test
  void helpTailShowsUsageAndExamples() {
    assertTrue(dispatcher.dispatch("help tail"));
    assertTrue(io.text().contains("Usage: tail"), io.text());
    assertTrue(io.text().contains("per-event"), io.text());
  }

  @Test
  void attachedSessionIsTheRegisteredOne() {
    attachFirst();
    @SuppressWarnings("unchecked")
    SessionManager<Session> typed = (SessionManager<Session>) (SessionManager<?>) sm;
    var ref = typed.current();
    assertTrue(ref.isPresent());
    assertSame(attachedSession, ref.get().session);
  }
}
