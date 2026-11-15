package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * One-shot CLI surface ({@code ps} / {@code tail <pid>}) — argument handling, exit codes, and error
 * paths that do not need a live target JVM (that is the {@code LiveTailIT} integration test's job).
 */
class MainTailCliTest {

  @TempDir Path tmp;

  private static class Captured {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final PrintStream origOut = System.out;
    final PrintStream origErr = System.err;

    PrintStream outStream() {
      return new PrintStream(out);
    }

    PrintStream errStream() {
      return new PrintStream(err);
    }

    String outText() {
      return out.toString();
    }

    String errText() {
      return err.toString();
    }
  }

  @Test
  void psListsLocalJvmRows() {
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      Integer rc = new CommandLine(new Main()).execute("ps");
      assertEquals(0, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.outText().contains("Local JVMs"), c.outText());
  }

  @Test
  void tailWithUnattachablePidFailsWithDiscoveryMessage() {
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      // 2^28 is beyond any pid_max: no process, no repository
      Integer rc = new CommandLine(new Main()).execute("tail", "99999999");
      assertEquals(1, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.errText().contains("Error:"), c.errText());
    assertTrue(
        c.errText().contains("--repository") || c.errText().contains("repository"), c.errText());
  }

  @Test
  void tailWithMissingRepositoryDirFails() {
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      Integer rc =
          new CommandLine(new Main())
              .execute("tail", "1", "--repository", tmp.resolve("nope").toString());
      assertEquals(1, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.errText().contains("repository directory not found"), c.errText());
  }

  @Test
  void tailRejectsNonEventsFilterBeforeOpeningTheStream() throws Exception {
    Path emptyRepo = Files.createDirectories(tmp.resolve("empty-repo"));
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      Integer rc =
          new CommandLine(new Main())
              .execute("tail", "1", "--repository", emptyRepo.toString(), "metadata");
      assertEquals(1, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.errText().contains("events root"), c.errText());
  }

  @Test
  void tailRejectsUnknownFormat() throws Exception {
    Path emptyRepo = Files.createDirectories(tmp.resolve("empty-repo2"));
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      Integer rc =
          new CommandLine(new Main())
              .execute("tail", "1", "--repository", emptyRepo.toString(), "--format", "xml");
      assertEquals(1, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.errText().contains("unknown format"), c.errText());
  }

  @Test
  void tailHelpMentionsRepositoryAndLimit() {
    Captured c = new Captured();
    System.setOut(c.outStream());
    System.setErr(c.errStream());
    try {
      Integer rc = new CommandLine(new Main()).execute("tail", "--help");
      assertEquals(0, rc);
    } finally {
      System.setOut(c.origOut);
      System.setErr(c.origErr);
    }
    assertTrue(c.outText().contains("--repository"), c.outText());
    assertTrue(c.outText().contains("--limit"), c.outText());
  }
}
