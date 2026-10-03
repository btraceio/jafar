package io.jafar.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Owns the only process state that can make a rendered guidance contract non-deterministic.
 *
 * <p>The sessions resource is deliberately part of the public contract. Rendering it against a
 * developer's {@code ~/.jafar} state would leak paths and aliases into the snapshot, so callers
 * must create this context before constructing {@link JafarMcpServer}.
 */
final class GuidanceContractContext implements AutoCloseable {

  private static final String SESSIONS_PROPERTY = "jafar.mcp.sessions.file";

  private final Path root;
  private final Path sessionsFile;
  private final Path home;
  private final String priorSessionsFile;
  private final String priorUserHome;

  private GuidanceContractContext(
      Path root, Path sessionsFile, Path home, String priorSessionsFile, String priorUserHome) {
    this.root = root;
    this.sessionsFile = sessionsFile;
    this.home = home;
    this.priorSessionsFile = priorSessionsFile;
    this.priorUserHome = priorUserHome;
  }

  static GuidanceContractContext create() throws IOException {
    Path parent = Path.of("build", "mcp-contract").toAbsolutePath().normalize();
    Files.createDirectories(parent);
    Path root = Files.createTempDirectory(parent, "guidance-").toRealPath();
    Path sessionsFile = root.resolve("sessions.json").normalize();
    Path home = root.resolve("home").normalize();
    assertOwned(root, sessionsFile, home);
    Files.createDirectories(home);
    Files.writeString(sessionsFile, "[]");

    String priorSessionsFile = System.getProperty(SESSIONS_PROPERTY);
    String priorUserHome = System.getProperty("user.home");
    System.setProperty(SESSIONS_PROPERTY, sessionsFile.toString());
    System.setProperty("user.home", home.toString());
    return new GuidanceContractContext(root, sessionsFile, home, priorSessionsFile, priorUserHome);
  }

  Path root() {
    return root;
  }

  Path sessionsFile() {
    return sessionsFile;
  }

  Path home() {
    return home;
  }

  static void assertOwned(Path root, Path sessionsFile, Path home) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedSessions = sessionsFile.toAbsolutePath().normalize();
    Path normalizedHome = home.toAbsolutePath().normalize();
    if (!normalizedSessions.startsWith(normalizedRoot)
        || !normalizedHome.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException(
          "contract session/home path escapes owned root: root="
              + normalizedRoot
              + ", sessions="
              + normalizedSessions
              + ", home="
              + normalizedHome);
    }
  }

  @Override
  public void close() throws IOException {
    restore(SESSIONS_PROPERTY, priorSessionsFile);
    restore("user.home", priorUserHome);
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(GuidanceContractContext::delete);
    }
    if (Files.exists(root)) {
      throw new IOException("could not remove owned guidance-contract directory: " + root);
    }
  }

  private static void restore(String property, String priorValue) {
    if (priorValue == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, priorValue);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new IllegalStateException("could not remove owned guidance-contract path: " + path, e);
    }
  }
}
