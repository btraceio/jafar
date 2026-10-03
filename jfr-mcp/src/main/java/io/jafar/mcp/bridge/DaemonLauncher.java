package io.jafar.mcp.bridge;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts the shared SSE daemon as a detached background process, running the same jar and JVM as
 * the bridge.
 *
 * <p>It runs {@code java -jar} directly, never through jbang: a launchd or systemd environment (and
 * some GUI-launched shells) has no {@code jbang} on its PATH, and that is exactly how the
 * supervised unit ended up crash-looping on some machines.
 *
 * <p>The daemon has to outlive the bridge that started it, and Claude Code may signal the bridge's
 * whole process group on exit. So the shell enables job control ({@code set -m}, which puts the
 * background job in its own process group) and {@code nohup}s it.
 */
final class DaemonLauncher implements DaemonLocator.Launcher {

  private static final String AUTOSTARTED = "mcp.daemon.autostarted";

  private static final String SCRIPT =
      "out=$1; err=$2; shift 2; set -m; nohup \"$@\" >>\"$out\" 2>>\"$err\" </dev/null & echo $!";

  private final Path stateDir;

  DaemonLauncher(Path stateDir) {
    this.stateDir = stateDir;
  }

  @Override
  public void launch() throws IOException {
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      throw new IOException(
          "starting the daemon automatically is not supported on Windows yet; start it yourself"
              + " with `jfr-mcp` (see doc/mcp/Daemon.md) and the bridge will attach to it");
    }
    Files.createDirectories(stateDir);
    Path jar = ownJar();
    AotPlan aot =
        aotPlan(
            stateDir,
            jarVersion(jar),
            Runtime.version(),
            pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    List<String> daemon =
        daemonCommand(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            jar,
            forwardedProperties(),
            aot.flags());
    List<String> command =
        detachedShellCommand(
            daemon, stateDir.resolve("mcp-sse.log"), stateDir.resolve("mcp-sse.err.log"));

    Process shell = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!shell.waitFor(10, TimeUnit.SECONDS) || shell.exitValue() != 0) {
        shell.destroyForcibly();
        throw new IOException("could not start the jfr-mcp daemon: " + String.join(" ", command));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while starting the jfr-mcp daemon", e);
    }
    if (aot.training()) {
      // The cache is written when this daemon exits, which takes a few seconds. Record who is
      // doing it so a bridge that starts in that window does not start a second writer.
      long pid = lastPid(new String(shell.getInputStream().readAllBytes()));
      if (pid > 0) {
        Files.writeString(aot.marker(), Long.toString(pid));
      }
    }
  }

  private static final Pattern PID_LINE = Pattern.compile("(?m)^(\\d+)\\s*$");

  /** The pid the launch script echoed: the last line that is only digits. */
  static long lastPid(String shellOutput) {
    Matcher m = PID_LINE.matcher(shellOutput);
    long pid = -1;
    while (m.find()) {
      pid = Long.parseLong(m.group(1));
    }
    return pid;
  }

  private static String jarVersion(Path jar) throws IOException {
    String version = DaemonLauncher.class.getPackage().getImplementationVersion();
    if (version == null || version.isBlank()) {
      return null;
    }
    // A rebuilt snapshot has the same version but a different jar, which the JVM would reject.
    return version + "-" + Files.getLastModifiedTime(jar).toMillis() / 1000;
  }

  /**
   * What AOT cache flags the daemon gets, and whether it is the run that trains the cache.
   *
   * @param flags JVM flags to add, empty for none
   * @param training whether this daemon will write the cache when it exits
   * @param marker file naming the training daemon's pid, while one is running
   */
  record AotPlan(List<String> flags, boolean training, Path marker) {}

  /**
   * Decides how the daemon uses an ahead-of-time cache of its loaded classes and profiles, which
   * cuts its start from about a second to about a third of that.
   *
   * <p>With no cache yet, the first daemon is the training run ({@code -XX:AOTCacheOutput}): it
   * records what it loads and writes the cache as it exits, which an auto-started daemon does once
   * idle. Afterwards every daemon starts from it ({@code -XX:AOTCache}). The cache is only valid
   * for the exact jar and JVM that made it, so both are in its name, and caches for anything else
   * are deleted.
   *
   * <p>Skipped when the jar version is unknown (an IDE or test run) or the JVM predates the
   * one-step flags, since an unrecognised {@code -XX} flag stops the JVM from starting.
   */
  static AotPlan aotPlan(Path stateDir, String version, Runtime.Version jvm, LongPredicate alive) {
    if (version == null
        || version.isBlank()
        || version.startsWith("unknown")
        || jvm.feature() < 25) {
      return new AotPlan(List.of(), false, null);
    }
    String name = "jfr-mcp-" + safe(version) + "-jdk" + safe(jvm.toString()) + ".aot";
    Path cache = stateDir.resolve(name);
    Path marker = stateDir.resolve(name + ".training");
    removeOtherCaches(stateDir, name);

    try {
      if (Files.isRegularFile(cache) && Files.size(cache) > 0) {
        Files.deleteIfExists(marker);
        return new AotPlan(List.of("-XX:AOTCache=" + cache), false, marker);
      }
      if (Files.isRegularFile(marker)) {
        long pid = Long.parseLong(Files.readString(marker).trim());
        if (alive.test(pid)) {
          return new AotPlan(List.of(), false, marker); // someone else is writing it
        }
      }
    } catch (IOException | NumberFormatException e) {
      // an unreadable marker is as good as none: fall through and train
    }
    return new AotPlan(List.of("-XX:AOTCacheOutput=" + cache), true, marker);
  }

  private static String safe(String s) {
    return s.replaceAll("[^A-Za-z0-9._+-]", "_");
  }

  private static void removeOtherCaches(Path stateDir, String keepName) {
    try (Stream<Path> files = Files.list(stateDir)) {
      files
          .filter(f -> f.getFileName().toString().startsWith("jfr-mcp-"))
          .filter(
              f -> {
                String n = f.getFileName().toString();
                return (n.endsWith(".aot")
                        || n.endsWith(".aot.training")
                        || n.endsWith(".aot.config"))
                    && !n.equals(keepName)
                    && !n.equals(keepName + ".training")
                    && !n.equals(keepName + ".config");
              })
          .forEach(
              f -> {
                try {
                  Files.deleteIfExists(f);
                } catch (IOException ignored) {
                  // best effort: a leftover cache costs disk, not correctness
                }
              });
    } catch (IOException ignored) {
      // no state dir yet, nothing to clean
    }
  }

  static List<String> daemonCommand(String javaBinary, Path jar, Map<String, String> properties) {
    return daemonCommand(javaBinary, jar, properties, List.of());
  }

  static List<String> daemonCommand(
      String javaBinary, Path jar, Map<String, String> properties, List<String> jvmFlags) {
    List<String> cmd = new ArrayList<>();
    cmd.add(javaBinary);
    cmd.addAll(jvmFlags);
    // Sorted, and never forwarding the marker itself: the caller must not be able to switch off the
    // property that tells the daemon it may exit when idle.
    for (Map.Entry<String, String> e : new TreeMap<>(properties).entrySet()) {
      if (!e.getKey().equals(AUTOSTARTED)) {
        cmd.add("-D" + e.getKey() + "=" + e.getValue());
      }
    }
    cmd.add("-D" + AUTOSTARTED + "=true");
    cmd.add("-jar");
    cmd.add(jar.toString());
    return cmd;
  }

  static List<String> detachedShellCommand(List<String> daemon, Path outLog, Path errLog) {
    List<String> cmd = new ArrayList<>(List.of("/bin/sh", "-c", SCRIPT, "sh"));
    cmd.add(outLog.toString());
    cmd.add(errLog.toString());
    cmd.addAll(daemon);
    return cmd;
  }

  /**
   * The settings that must reach the daemon for it to share this bridge's state, port and policy.
   */
  static Map<String, String> forwarded(Map<String, String> all) {
    Map<String, String> forwarded = new TreeMap<>();
    for (Map.Entry<String, String> e : all.entrySet()) {
      String name = e.getKey();
      if (name.startsWith("mcp.")
          || name.equals("jafar.state.dir")
          || name.equals("jafar.mcp.sessions.file")) {
        forwarded.put(name, e.getValue());
      }
    }
    return forwarded;
  }

  private static Map<String, String> forwardedProperties() {
    Map<String, String> all = new TreeMap<>();
    for (String name : System.getProperties().stringPropertyNames()) {
      all.put(name, System.getProperty(name));
    }
    return forwarded(all);
  }

  private static Path ownJar() throws IOException {
    try {
      Path location =
          Path.of(DaemonLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(location)) {
        throw new IOException("cannot start the daemon: not running from a jar (" + location + ")");
      }
      return location;
    } catch (URISyntaxException | RuntimeException e) {
      throw new IOException("cannot locate the jfr-mcp jar to start the daemon from", e);
    }
  }
}
