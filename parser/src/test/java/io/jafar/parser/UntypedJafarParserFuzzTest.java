package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;

public class UntypedJafarParserFuzzTest {

  @RepeatedTest(5)
  void randomSmallRecordingDoesNotCrash() throws Exception {
    // Build a tiny JFR with random payload inside a valid envelope using the writer
    ByteArrayOutputStream recordingStream = new ByteArrayOutputStream();
    try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording =
        org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(recordingStream)) {
      io.jafar.TestJfrRecorder rec = new io.jafar.TestJfrRecorder(recording);
      rec.registerEventType(ParserEvent.class);
      java.util.Random rnd = new java.util.Random();
      for (int i = 0; i < 3; i++) {
        rec.writeEvent(new ParserEvent(rnd.nextInt(100)));
      }
    }
    Path tmp = Files.createTempFile("recording", ".jfr");
    Files.write(tmp, recordingStream.toByteArray());

    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser p = ctx.newUntypedParser(tmp)) {
      AtomicInteger count = new AtomicInteger();
      p.handle((t, m, ctl) -> count.incrementAndGet());
      p.run();
      assertTrue(count.get() > 0);
    }
  }
}
