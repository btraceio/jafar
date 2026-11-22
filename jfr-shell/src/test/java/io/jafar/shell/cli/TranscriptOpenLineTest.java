package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.OpenArguments;
import org.junit.jupiter.api.Test;

/**
 * The line {@code ask}'s transcript writes to reopen the recording: it must open that recording
 * when the script is replayed, or say plainly that it cannot (#233).
 */
class TranscriptOpenLineTest {

  private static void opens(String recording) {
    String line = CommandDispatcher.transcriptOpenLine(recording);
    assertTrue(line.startsWith("open "), line);
    assertEquals(recording, OpenArguments.parse(line.substring("open ".length())).path(), line);
  }

  @Test
  void theLineOpensTheRecording() {
    opens("/tmp/rec.jfr");
    opens("/tmp/dir with spaces/rec 1.jfr");
    opens("/tmp/a --alias b.jfr");
    // whitespace and both quote characters: written bare, which reads back
    opens("/tmp/Bob's \"prod\" run.jfr");
    assertEquals(
        "open \"/tmp/dir with spaces/rec.jfr\"",
        CommandDispatcher.transcriptOpenLine("/tmp/dir with spaces/rec.jfr"));
  }

  @Test
  void aPathNoOpenLineExpressesBecomesAComment() {
    String line = CommandDispatcher.transcriptOpenLine(" a'b\"c.jfr");
    assertTrue(line.startsWith("# "), line);
    assertTrue(line.endsWith(" a'b\"c.jfr"), line);
  }
}
