package io.jafar.shell.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Remembers how many events of each type a recording holds, between sessions.
 *
 * <p>Counting means one pass over the recording. That is affordable once — and is the same pass the
 * query answering the question will make anyway — but paying it again every time the file is opened
 * is waste, because the answer cannot change: a recording is a finished artifact.
 *
 * <p>The cache lives under {@code $XDG_CACHE_HOME/jafar/event-counts} (else {@code
 * ~/.cache/jafar/event-counts}) rather than beside the recording. A recording often sits in a
 * directory that is read-only, shared, or simply not the shell's to litter — someone analysing a
 * customer's recording should not find new files next to it afterwards.
 *
 * <p>The key is the recording's absolute path, size and modification time, so a file replaced in
 * place misses rather than answering from a stale count. Every failure here is silent by design:
 * this is an optimisation, and a broken cache must degrade to counting, never to an error or to a
 * wrong number.
 */
final class EventCountCache {

  private EventCountCache() {}

  /** Counts for this recording, if they were computed in an earlier session. */
  static Optional<Map<String, Long>> read(Path recording) {
    try {
      Path file = fileFor(recording);
      if (file == null || !Files.isReadable(file)) {
        return Optional.empty();
      }
      Properties properties = new Properties();
      try (InputStream in = Files.newInputStream(file)) {
        properties.load(in);
      }
      Map<String, Long> counts = new HashMap<>();
      for (String name : properties.stringPropertyNames()) {
        try {
          counts.put(name, Long.parseLong(properties.getProperty(name)));
        } catch (NumberFormatException e) {
          // A corrupt entry makes the whole file untrustworthy: a count that is wrong is worse
          // than a count that is missing, because the model acts on it.
          return Optional.empty();
        }
      }
      return counts.isEmpty() ? Optional.empty() : Optional.of(counts);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Stores counts for this recording. Best-effort: an unwritable cache directory is not an error.
   */
  static void write(Path recording, Map<String, Long> counts) {
    if (counts == null || counts.isEmpty()) {
      return;
    }
    try {
      Path file = fileFor(recording);
      if (file == null) {
        return;
      }
      Files.createDirectories(file.getParent());
      Properties properties = new Properties();
      counts.forEach((type, count) -> properties.setProperty(type, String.valueOf(count)));
      try (OutputStream out = Files.newOutputStream(file)) {
        properties.store(out, "jafar event counts for " + recording.toAbsolutePath());
      }
    } catch (Exception e) {
      // Nothing to report: the counts are already in hand, this only saves the next session.
    }
  }

  /**
   * Where this recording's counts live.
   *
   * <p>Keyed by path, size and modification time together. Path alone would answer from a stale
   * count after a file is replaced in place, which is the one failure mode that would be worse than
   * having no cache at all.
   */
  private static Path fileFor(Path recording) throws IOException {
    Path directory = cacheDirectory();
    if (directory == null) {
      return null;
    }
    String identity =
        recording.toAbsolutePath().normalize()
            + ":"
            + Files.size(recording)
            + ":"
            + Files.getLastModifiedTime(recording).toMillis();
    return directory.resolve(digest(identity) + ".properties");
  }

  private static Path cacheDirectory() {
    String xdg = System.getenv("XDG_CACHE_HOME");
    if (xdg != null && !xdg.isBlank()) {
      return Paths.get(xdg.trim(), "jafar", "event-counts");
    }
    String home = System.getProperty("user.home");
    if (home == null || home.isBlank()) {
      return null;
    }
    return Paths.get(home, ".cache", "jafar", "event-counts");
  }

  private static String digest(String identity) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] hash = sha.digest(identity.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (Exception e) {
      // A JRE without SHA-256 is not a thing, but a cache is never worth an exception.
      return Integer.toHexString(identity.hashCode());
    }
  }
}
