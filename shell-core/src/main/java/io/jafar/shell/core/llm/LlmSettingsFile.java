package io.jafar.shell.core.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings read from a file on disk, so a credential need not live in an environment variable.
 *
 * <p>An environment variable is the wrong place for a long-lived secret: it is inherited by every
 * child process the shell starts, it shows up in a crash dump or a CI log, and exporting it inline
 * puts it in shell history. A file the owner alone can read has none of those properties, and it
 * survives a new terminal without being re-exported.
 *
 * <p>Format is {@code java.util.Properties} — {@code llm.api-key=sk-...}, one per line, {@code #}
 * for comments. Keys are the same names {@code set} uses, so a file and a {@code set} command are
 * interchangeable.
 *
 * <p>Location, first that exists:
 *
 * <ol>
 *   <li>{@code $JAFAR_LLM_CONFIG}, for anyone who keeps secrets somewhere specific
 *   <li>{@code $XDG_CONFIG_HOME/jafar/llm.properties}
 *   <li>{@code ~/.config/jafar/llm.properties}
 * </ol>
 */
public final class LlmSettingsFile {

  /** Permissions that mean someone other than the owner can read the file. */
  private static final Set<PosixFilePermission> TOO_OPEN =
      Set.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE);

  // Read once per path: a shell session re-reads settings on every command, and a file that has
  // not changed does not need re-parsing on each keystroke-driven completion.
  private static final Map<Path, LlmSettingsFile> CACHE = new ConcurrentHashMap<>();

  private final Path path;
  private final Properties values;
  private final String warning;

  private LlmSettingsFile(Path path, Properties values, String warning) {
    this.path = path;
    this.values = values;
    this.warning = warning;
  }

  /** Loads the settings file, or empty when there is none. */
  public static Optional<LlmSettingsFile> find() {
    Path path = locate();
    if (path == null) {
      return Optional.empty();
    }
    return Optional.of(CACHE.computeIfAbsent(path, LlmSettingsFile::read));
  }

  /** Loads a specific file. Package-private: the seam tests use instead of setting env vars. */
  static LlmSettingsFile of(Path path) {
    return read(path);
  }

  private static Path locate() {
    String explicit = System.getenv("JAFAR_LLM_CONFIG");
    if (explicit != null && !explicit.isBlank()) {
      Path p = Paths.get(explicit.trim());
      // An explicit path that does not exist is a mistake worth surfacing rather than ignoring,
      // so it is returned and reported as unreadable instead of silently falling through.
      return p;
    }
    String xdg = System.getenv("XDG_CONFIG_HOME");
    if (xdg != null && !xdg.isBlank()) {
      Path p = Paths.get(xdg.trim(), "jafar", "llm.properties");
      if (Files.isReadable(p)) {
        return p;
      }
    }
    String home = System.getProperty("user.home");
    if (home != null && !home.isBlank()) {
      Path p = Paths.get(home, ".config", "jafar", "llm.properties");
      if (Files.isReadable(p)) {
        return p;
      }
    }
    return null;
  }

  private static LlmSettingsFile read(Path path) {
    Properties props = new Properties();
    if (!Files.isReadable(path)) {
      return new LlmSettingsFile(path, props, path + " is not readable");
    }
    try (InputStream in = Files.newInputStream(path)) {
      props.load(in);
    } catch (IOException e) {
      return new LlmSettingsFile(path, new Properties(), "could not read " + path + ": " + e);
    }
    return new LlmSettingsFile(path, props, permissionWarning(path));
  }

  /** Returns a warning when the file is readable by anyone but its owner, else {@code null}. */
  private static String permissionWarning(Path path) {
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
      if (perms.stream().anyMatch(TOO_OPEN::contains)) {
        return path + " is readable by others — chmod 600 it";
      }
    } catch (UnsupportedOperationException | IOException e) {
      // Not a POSIX filesystem (Windows). Nothing to check, and nothing worth saying.
    }
    return null;
  }

  /** The value for a setting name, or {@code null}. */
  public String get(String setting) {
    String value = values.getProperty(setting);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Where this came from, for {@code llm status}. */
  public Path path() {
    return path;
  }

  /** A problem worth telling the user about — bad permissions or an unreadable file — or empty. */
  public Optional<String> warning() {
    return Optional.ofNullable(warning);
  }

  /** Clears the cache. For tests, which write a file and expect it to be seen. */
  public static void invalidateCache() {
    CACHE.clear();
  }
}
