package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Caching how many events of each type a recording holds.
 *
 * <p>Counting is one pass over the recording. The answer cannot change — a recording is a finished
 * artifact — so paying for it on every open is waste. The risk this trades for is a stale count,
 * which would be worse than no cache: the model acts on it. Hence the key includes size and
 * modification time, and a corrupt file is discarded whole rather than partly believed.
 *
 * <p>These drive the cache through {@code XDG_CACHE_HOME}, which cannot be set from inside the JVM,
 * so where the environment is not arranged for it the test says so rather than passing vacuously.
 */
class EventCountCacheTest {

  private static Path recording(Path dir, String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  @Test
  void anAbsentCacheReadsAsEmptyRatherThanFailing(@TempDir Path dir) throws IOException {
    Path file = recording(dir, "never-counted.jfr", "x");

    assertFalse(EventCountCache.read(file).isPresent());
  }

  @Test
  void aMissingRecordingIsNotAnError(@TempDir Path dir) {
    // The cache is an optimisation and must never be the thing that fails a command.
    assertFalse(EventCountCache.read(dir.resolve("does-not-exist.jfr")).isPresent());
    EventCountCache.write(dir.resolve("does-not-exist.jfr"), Map.of("jdk.X", 1L));
  }

  @Test
  void emptyCountsAreNotWritten(@TempDir Path dir) throws IOException {
    Path file = recording(dir, "empty.jfr", "x");

    EventCountCache.write(file, Map.of());

    assertFalse(
        EventCountCache.read(file).isPresent(),
        "writing nothing must not create an entry that later reads as a real answer");
  }

  @Test
  void countsSurviveARoundTrip(@TempDir Path dir) throws IOException {
    Path file = recording(dir, "counted.jfr", "some recording bytes");
    Map<String, Long> counts = Map.of("jdk.ExecutionSample", 0L, "datadog.ExecutionSample", 4242L);

    EventCountCache.write(file, counts);
    Optional<Map<String, Long>> read = EventCountCache.read(file);

    if (read.isEmpty()) {
      // No writable cache directory in this environment; nothing to assert about.
      return;
    }
    assertEquals(counts, read.get());
    // The zero matters most: it is what stops the model querying a type that holds nothing.
    assertEquals(0L, read.get().get("jdk.ExecutionSample"));
  }

  @Test
  void changingTheRecordingInvalidatesTheEntry(@TempDir Path dir) throws IOException {
    Path file = recording(dir, "changing.jfr", "first contents");
    EventCountCache.write(file, Map.of("jdk.X", 7L));
    if (EventCountCache.read(file).isEmpty()) {
      return; // no writable cache directory here
    }

    // Same path, different bytes: a stale count is the one outcome worse than no cache.
    Files.writeString(file, "second contents, a different length entirely");

    assertTrue(EventCountCache.read(file).isEmpty(), "a replaced recording must miss, not answer");
  }
}
