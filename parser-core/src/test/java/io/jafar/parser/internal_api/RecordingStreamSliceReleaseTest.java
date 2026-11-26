package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A value resolved after its chunk was closed, on a thread that outlives the parse, must not keep
 * the recording mapped once the value is gone (#227). The thread-local slice that resolution caches
 * lives in that thread's {@code ThreadLocalMap}, which {@link RecordingStream#close()} cannot
 * reach; it used to pin the mapped buffer until the thread exited, and on Windows a mapped file
 * cannot be deleted. Linux only: the mappings are read from {@code /proc/self/maps}.
 */
class RecordingStreamSliceReleaseTest {

  private static final Path MAPS = Paths.get("/proc/self/maps");

  @TempDir Path tempDir;

  @Test
  void valuesResolvedOnALongLivedThreadDoNotPinTheFile() throws Exception {
    assumeTrue(Files.isReadable(MAPS), "needs /proc/self/maps (Linux)");
    Path recording =
        Files.copy(
            Paths.get("src/test/resources/test-dd.jfr"),
            tempDir.resolve("pinned.jfr"),
            StandardCopyOption.REPLACE_EXISTING);
    ExecutorService longLived = Executors.newSingleThreadExecutor();
    try {
      List<Map<String, Object>> kept = new ArrayList<>();
      try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(recording)) {
        parser.handle(
            (type, value, ctl) -> {
              if (kept.size() < 50 && value.get("stackTrace") != null) {
                kept.add(value);
              }
            });
        parser.run();
      }
      assertEquals(50, kept.size());
      // Constant-pool references are decoded here, after every chunk stream was closed.
      longLived
          .submit(
              () -> {
                for (Map<String, Object> value : kept) {
                  Values.resolvedDeep(value);
                }
                return null;
              })
          .get();
      kept.clear();

      long left = mappingsAfterCollection(recording);
      assertTrue(longLived.submit(() -> true).get(), "the resolving thread is still alive");
      assertEquals(0, left, "mappings of the recording after its values were dropped");
    } finally {
      longLived.shutdown();
      longLived.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /** The recording's mappings once collections have had a chance to unmap what is unreachable. */
  private static long mappingsAfterCollection(Path recording) throws Exception {
    long count = mappings(recording);
    for (int i = 0; i < 50 && count > 0; i++) {
      System.gc();
      Thread.sleep(100);
      count = mappings(recording);
    }
    return count;
  }

  private static long mappings(Path file) throws IOException {
    String suffix = " " + file.toRealPath();
    long count = 0;
    for (String line : Files.readAllLines(MAPS)) {
      if (line.endsWith(suffix)) {
        count++;
      }
    }
    return count;
  }
}
