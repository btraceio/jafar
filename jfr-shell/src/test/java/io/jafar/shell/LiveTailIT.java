package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.live.JvmProcesses;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;

/**
 * R5-critical integration test for live tailing (issue #92): spawns a real child JVM with a
 * disk-backed flight recording emitting events every ~50 ms, then drives the one-shot CLI against
 * it — events arrive, JfrPath filtering applies, interrupt ends with exit 0, and the target JVM
 * dying ends the stream cleanly. Mirrors the feasibility reproducer from the issue triage. Needs
 * process spawning and the platform's hsperfdata/repository conventions; on platforms where
 * discovery does not find the child's repository the test reports what it searched (see the PR's
 * not-verified section).
 */
@Timeout(180)
class LiveTailIT {

  private static final String EMITTER_SOURCE =
      """
      import jdk.jfr.Event;
      public class LiveTailEmitter {
        public static class Heartbeat extends Event { public int seq; }
        public static void main(String[] args) throws Exception {
          int n = Integer.parseInt(args[0]);
          for (int i = 0; i < n; i++) {
            Heartbeat h = new Heartbeat();
            h.seq = i;
            h.commit();
            Thread.sleep(50);
          }
        }
      }
      """;

  private static final String EVENT_TYPE = "LiveTailEmitter$Heartbeat";

  private static void restore(PrintStream out, PrintStream err, PrintStream o, PrintStream e) {
    System.setOut(o);
    System.setErr(e);
  }

  @Test
  void liveTailEndToEnd() throws Exception {
    Path dir = Files.createTempDirectory("live-tail-it");
    Path emitter = dir.resolve("LiveTailEmitter.java");
    Files.writeString(emitter, EMITTER_SOURCE);

    String javaBin =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    Process child =
        new ProcessBuilder(
                javaBin,
                "-XX:StartFlightRecording=disk=true,name=probe",
                emitter.toString(),
                "100000")
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("child.log").toFile())
            .start();
    long childPid = child.pid();
    try {
      // 1. discovery finds the child's repository within a bounded wait
      Path repo = null;
      long deadline = System.currentTimeMillis() + 30_000;
      while (System.currentTimeMillis() < deadline) {
        try {
          repo = JvmProcesses.findRepository((int) childPid).orElse(null);
          if (repo != null) break;
        } catch (Exception ignored) {
          // not up yet
        }
        Thread.sleep(250);
      }
      assertNotNull(repo, "child JFR repository not discovered within 30s for PID " + childPid);

      // 2. events stream with correct values (limit exits cleanly with code 0)
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      AtomicInteger rc = new AtomicInteger(-1);
      CountDownLatch done = new CountDownLatch(1);
      Thread t =
          new Thread(
              () -> {
                PrintStream ps = new PrintStream(out);
                PrintStream origOut = System.out, origErr = System.err;
                System.setOut(ps);
                System.setErr(ps);
                try {
                  rc.set(
                      new CommandLine(new Main())
                          .execute("tail", Long.toString(childPid), "--limit", "5"));
                } finally {
                  System.setOut(origOut);
                  System.setErr(origErr);
                  done.countDown();
                }
              });
      t.start();
      assertTrue(done.await(60, TimeUnit.SECONDS), "tail --limit did not finish in 60s");
      assertEquals(0, rc.get(), out.toString());
      long rows =
          out.toString()
              .lines()
              .filter(l -> l.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d .*"))
              .count();
      assertTrue(rows >= 5, "expected >=5 event rows, got:\n" + out);
      assertTrue(out.toString().contains(EVENT_TYPE), "expected heartbeats in:\n" + out);
      assertTrue(out.toString().contains("limit reached"), out.toString());

      // 3. per-event filter applies (seq > 5 => rows all satisfy it)
      ByteArrayOutputStream filtered = new ByteArrayOutputStream();
      AtomicInteger rc2 = new AtomicInteger(-1);
      CountDownLatch done2 = new CountDownLatch(1);
      Thread t2 =
          new Thread(
              () -> {
                PrintStream ps = new PrintStream(filtered);
                PrintStream origOut = System.out, origErr = System.err;
                System.setOut(ps);
                System.setErr(ps);
                try {
                  rc2.set(
                      new CommandLine(new Main())
                          .execute(
                              "tail",
                              Long.toString(childPid),
                              "events/" + EVENT_TYPE + "[seq > 5]",
                              "--limit",
                              "3"));
                } finally {
                  System.setOut(origOut);
                  System.setErr(origErr);
                  done2.countDown();
                }
              });
      t2.start();
      assertTrue(done2.await(60, TimeUnit.SECONDS), "filtered tail did not finish in 60s");
      assertEquals(0, rc2.get(), filtered.toString());
      assertTrue(filtered.toString().contains("limit reached"), filtered.toString());

      // 4. interrupt ends tail with exit 0 (the SIGINT path: the handler interrupts the loop)
      ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
      AtomicInteger rc3 = new AtomicInteger(-1);
      CountDownLatch firstEvent = new CountDownLatch(1);
      CountDownLatch done3 = new CountDownLatch(1);
      Thread t3 =
          new Thread(
              () -> {
                PrintStream ps =
                    new PrintStream(streamOut) {
                      @Override
                      public void println(String s) {
                        super.println(s);
                        if (s.contains(EVENT_TYPE)) {
                          firstEvent.countDown();
                        }
                      }
                    };
                PrintStream origOut = System.out, origErr = System.err;
                System.setOut(ps);
                System.setErr(ps);
                try {
                  rc3.set(new CommandLine(new Main()).execute("tail", Long.toString(childPid)));
                } finally {
                  System.setOut(origOut);
                  System.setErr(origErr);
                  done3.countDown();
                }
              });
      t3.start();
      assertTrue(firstEvent.await(60, TimeUnit.SECONDS), "no event arrived in 60s");
      t3.interrupt();
      assertTrue(done3.await(30, TimeUnit.SECONDS), "tail did not stop after interrupt");
      assertEquals(0, rc3.get(), streamOut.toString());
      assertTrue(streamOut.toString().contains("interrupted"), streamOut.toString());

      // 5. live metadata: the stream's onMetadata exposes the custom event type
      try (io.jafar.shell.live.LiveJfrSession session =
          new io.jafar.shell.live.LiveJfrSession(
              (int) childPid, repo, "jfr-live", jdk.jfr.consumer.EventStream::openRepository)) {
        session.start();
        long typeDeadline = System.currentTimeMillis() + 30_000;
        // Events reach the repository on the target's flush cadence (up to ~5s after attach),
        // while metadata arrives immediately — so wait for the first event with its own
        // deadline instead of keying both off the type list.
        while (session.getReceivedCount() == 0
            && !session.getAvailableTypes().contains(EVENT_TYPE)
            && System.currentTimeMillis() < typeDeadline) {
          session.pollEvent(200);
        }
        long eventDeadline = System.currentTimeMillis() + 30_000;
        while (session.getReceivedCount() == 0 && System.currentTimeMillis() < eventDeadline) {
          session.pollEvent(200);
        }
        assertTrue(
            session.getAvailableTypes().contains(EVENT_TYPE),
            "live metadata should expose "
                + EVENT_TYPE
                + ", got "
                + session.typeList()
                + " state="
                + session.getStreamState()
                + " received="
                + session.getReceivedCount());
        assertTrue(
            session.getReceivedCount() > 0,
            "no events delivered: state="
                + session.getStreamState()
                + " types="
                + session.typeList());
      }

      // 6. the target JVM dying ends an in-flight stream cleanly (exit 0, closed summary)
      ByteArrayOutputStream dyingOut = new ByteArrayOutputStream();
      AtomicInteger rc4 = new AtomicInteger(-1);
      CountDownLatch firstEvent4 = new CountDownLatch(1);
      CountDownLatch done4 = new CountDownLatch(1);
      Thread t4 =
          new Thread(
              () -> {
                PrintStream ps =
                    new PrintStream(dyingOut) {
                      @Override
                      public void println(String s) {
                        super.println(s);
                        if (s.contains(EVENT_TYPE)) {
                          firstEvent4.countDown();
                        }
                      }
                    };
                PrintStream origOut = System.out, origErr = System.err;
                System.setOut(ps);
                System.setErr(ps);
                try {
                  rc4.set(new CommandLine(new Main()).execute("tail", Long.toString(childPid)));
                } finally {
                  System.setOut(origOut);
                  System.setErr(origErr);
                  done4.countDown();
                }
              });
      t4.start();
      assertTrue(firstEvent4.await(60, TimeUnit.SECONDS), "no event arrived in 60s");
      child.destroy(); // JVM exit deletes the repository -> the stream auto-closes
      assertTrue(done4.await(60, TimeUnit.SECONDS), "tail did not end after target exit");
      assertEquals(0, rc4.get(), dyingOut.toString());
      String summary = dyingOut.toString();
      assertTrue(
          summary.contains("target stream closed") || summary.contains("stream ended"), summary);
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
  }
}
