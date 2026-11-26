package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.impl.TypedParserContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stream keeps one slot per thread that used its thread-local slice, so that {@link
 * RecordingStream#close()} can empty them (#227). Slots of threads that have exited must not
 * accumulate while the stream is open.
 */
class RecordingStreamSlotPruningTest {

  @TempDir Path tempDir;

  @Test
  void slotsOfExitedThreadsArePrunedWhenANewThreadArrives() throws Exception {
    Path file = Files.write(tempDir.resolve("bytes.bin"), new byte[64]);
    try (RecordingStream stream =
        new RecordingStream(RecordingStreamReader.mapped(file), new TypedParserContext())) {
      for (int i = 0; i < 200; i++) {
        Thread thread = new Thread(stream::threadLocalStreamSlice);
        thread.start();
        thread.join();
      }

      stream.threadLocalStreamSlice();

      assertEquals(1, stream.cachedSlotCount(), "slots held after 200 threads used and left");
    }
  }
}
