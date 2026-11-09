package io.jafar.shell.live;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local JVM discovery without the attach API, in two layers:
 *
 * <ul>
 *   <li>Process listing: scans the HotSpot perf-data tree ({@code <temp base>/hsperfdata_<user>/})
 *       for PID files. The temp base follows HotSpot {@code os::get_temp_directory()} semantics —
 *       hardcoded {@code /tmp} on POSIX, {@code %TEMP%}/{@code %TMP%} on Windows — NOT {@code
 *       java.io.tmpdir}, which a {@code -Djava.io.tmpdir} override moves independently while
 *       hsperfdata and the JFR repository stay put. Only a minimal subset of the perf-data format
 *       is parsed (prologue + entry walk for the {@code java.command} string counter), mirroring
 *       the reference layout in {@code sun.management.counter.perf}; any parse or permission
 *       failure degrades that row to PID-only and never aborts the listing.
 *   <li>Repository lookup: HotSpot's JFR event repository lives at {@code <temp
 *       base>/<yyyy_MM_dd_HH_mm_ss>_<pid>} on all three platforms. {@code openRepository(Path)}'s
 *       built-in base-dir discovery picks the latest directory by mtime, which breaks on hosts
 *       running more than one JVM — so we match the trailing PID ourselves and harden every
 *       candidate (symlink rejection, owner check, liveness, start-time cross-check) before
 *       accepting it.
 * </ul>
 */
public final class JvmProcesses {
  private static final int PERF_MAGIC = 0xcafec0c0; // stored big-endian, always
  private static final int PERF_VERSION_MAJOR_2 = 2;
  // prologue offsets (perfMemory.hpp / Prologue.java layout), after the 1-byte byte-order,
  // major, minor, accessible fields: used(int)@8, overflow(int)@12, mod_timestamp(long)@16,
  // entry_offset(int)@24, num_entries(int)@28
  private static final int OFF_USED = 8;
  private static final int OFF_ENTRY_OFFSET = 24;
  private static final int OFF_NUM_ENTRIES = 28;
  private static final int PROLOGUE_SIZE = 32;

  private static final Pattern REPO_DIR_PATTERN = Pattern.compile(".*_(\\d{1,10})$");

  private JvmProcesses() {}

  /**
   * The HotSpot perf-data / JFR-repository temp base for this platform, mirroring {@code
   * os::get_temp_directory()} per platform: Linux hardcodes {@code /tmp} (a target-side {@code
   * -XX:AltTempDir} is not discoverable from outside — covered by the {@code --repository} flag);
   * macOS uses the per-user Darwin temp dir (HotSpot {@code confstr(_CS_DARWIN_USER_TEMP_DIR)},
   * which is what {@code $TMPDIR} points at); Windows uses {@code %TEMP%}/{@code %TMP%}. NOT {@code
   * java.io.tmpdir} in general — a {@code -Djava.io.tmpdir} override moves that property while
   * hsperfdata and the JFR repository stay put.
   */
  public static Path tempBase() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("windows")) {
      String t = System.getenv("TEMP");
      if (t == null || t.isBlank()) t = System.getenv("TMP");
      if (t == null || t.isBlank()) t = "C:\\Temp";
      return Path.of(t);
    }
    if (os.contains("mac") || os.contains("darwin")) {
      String t = System.getenv("TMPDIR");
      if (t != null && !t.isBlank()) return Path.of(t);
      // fall through to the Darwin default, which is also java.io.tmpdir's default
    }
    return Path.of("/tmp");
  }

  /**
   * Lists candidate local JVMs: every readable {@code hsperfdata_<user>} entry, with the {@code
   * java.command} snippet when the perf-data file parsed and the JFR repository when one was found.
   * Unreadable or unparseable entries degrade to PID-only.
   */
  public static List<JvmProcess> listLocalJvm() {
    return listLocalJvm(tempBase());
  }

  /** {@link #listLocalJvm()} over an explicit base — the test seam. */
  static List<JvmProcess> listLocalJvm(Path base) {
    List<JvmProcess> out = new ArrayList<>();
    try (DirectoryStream<Path> hsdirs = Files.newDirectoryStream(base, "hsperfdata_*")) {
      for (Path hsdir : hsdirs) {
        if (!Files.isDirectory(hsdir)) continue;
        try (DirectoryStream<Path> pids = Files.newDirectoryStream(hsdir)) {
          for (Path pidFile : pids) {
            Integer pid = parsePid(pidFile.getFileName().toString());
            if (pid == null) continue;
            String command = readJavaCommand(pidFile);
            Path repo = null;
            try {
              repo = findRepository(pid, base).orElse(null);
            } catch (RuntimeException | IOException ignored) {
              // listing must survive discovery failures per entry
            }
            out.add(new JvmProcess(pid, command, repo));
          }
        } catch (IOException ignored) {
          // unreadable hsperfdata_<user> dir (EACCES): skip the whole user silently
        }
      }
    } catch (IOException ignored) {
      // no temp base access at all: empty listing
    }
    out.sort((a, b) -> Integer.compare(a.pid(), b.pid()));
    return out;
  }

  /**
   * Finds and hardens the JFR repository directory for {@code pid}. Rejections and failure modes:
   *
   * <ul>
   *   <li>no matching directory → empty
   *   <li>symlinked directory, directory not owned by the current user, or no regular file inside →
   *       candidate rejected (planted-directory defense: the JDK JFR parser would run over
   *       attacker-controlled bytes)
   *   <li>directory mtime predates the process start instant (stale repository of a crashed JVM
   *       under a reused PID) → rejected; a dead PID is rejected outright
   *   <li>several surviving candidates → {@link IllegalArgumentException} naming them; ambiguous is
   *       safer than wrong
   * </ul>
   *
   * @throws IOException when no repository was found (message names the searched base and suggests
   *     {@code --repository})
   */
  public static Optional<Path> findRepository(int pid) throws IOException {
    return findRepository(pid, tempBase());
  }

  /** {@link #findRepository(int)} over an explicit base — the test seam. */
  static Optional<Path> findRepository(int pid, Path base) throws IOException {
    List<Path> candidates = new ArrayList<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
      for (Path dir : dirs) {
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
        Matcher m = REPO_DIR_PATTERN.matcher(dir.getFileName().toString());
        if (!m.matches()) continue;
        if (Integer.parseInt(m.group(1)) != pid) continue;
        if (!isPlausibleRepository(dir, pid)) continue;
        candidates.add(dir);
      }
    } catch (IOException e) {
      throw new IOException("Cannot scan " + base + " for JFR repositories: " + e.getMessage(), e);
    }

    if (candidates.isEmpty()) {
      throw new IOException(
          "No JFR event repository found for PID "
              + pid
              + " under "
              + base
              + ". The target may not have a disk-backed recording running "
              + "(-XX:StartFlightRecording=disk=true), or uses a custom repository location: "
              + "pass it explicitly with --repository <dir>");
    }
    if (candidates.size() > 1) {
      throw new IOException(
          "Ambiguous JFR repositories for PID "
              + pid
              + ": "
              + candidates
              + "; pass the right one explicitly with --repository <dir>");
    }
    return Optional.of(candidates.get(0));
  }

  private static boolean isPlausibleRepository(Path dir, int pid) {
    try {
      var attrs = Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class);
      if (attrs.isSymbolicLink() || !attrs.isDirectory()) return false;
      // owner must be the current user
      String owner = Files.getOwner(dir).getName();
      String user = System.getProperty("user.name");
      if (user != null && !user.equals(owner)) return false;

      // liveness: a crashed JVM leaves its repository behind and PIDs are reused; a PID with
      // no live process at all means the repository is stale
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.get().isAlive()) return false;

      // at least one regular file inside (chunk or metadata file)
      boolean hasFile = false;
      try (DirectoryStream<Path> inner = Files.newDirectoryStream(dir)) {
        for (Path f : inner) {
          if (Files.isRegularFile(f)) {
            hasFile = true;
            break;
          }
        }
      }
      if (!hasFile) return false;

      // directory mtime must not predate the target's start time (TOCTOU remains; this only
      // filters the cheap stale-repository case)
      if (handle.isPresent()) {
        Instant start = handle.get().info().startInstant().orElse(null);
        FileTime mtime = attrs.lastModifiedTime();
        if (start != null && mtime.toInstant().isBefore(start.minusSeconds(2))) return false;
      }
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static Integer parsePid(String name) {
    try {
      int pid = Integer.parseInt(name);
      return pid > 0 ? pid : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Reads the {@code java.command} string counter from a hsperfdata file. Mirrors the entry walk of
   * {@code sun.management.counter.perf} (prologue: magic BE-int, byte-order byte, version bytes;
   * entries: 4-byte-aligned from {@code entry_offset} while {@code < used}). Returns {@code null}
   * on any parse failure, permission denial, or torn read.
   */
  static String readJavaCommand(Path perfFile) {
    ByteBuffer buf;
    try (FileChannel ch = FileChannel.open(perfFile, StandardOpenOption.READ)) {
      buf = ByteBuffer.allocate((int) Math.min(ch.size(), 1024 * 1024));
      while (ch.read(buf) != -1) {
        if (buf.hasRemaining() == false) break;
      }
    } catch (IOException | SecurityException e) {
      return null;
    }
    buf.flip();
    try {
      return parseJavaCommand(buf);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String parseJavaCommand(ByteBuffer buf) {
    if (buf.remaining() < PROLOGUE_SIZE) return null;
    buf.order(ByteOrder.BIG_ENDIAN);
    if (buf.getInt(0) != PERF_MAGIC) return null;
    byte orderByte = buf.get(4);
    buf.order(orderByte == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    int major = buf.get(5);
    if (major < PERF_VERSION_MAJOR_2) return null;
    int used = buf.getInt(OFF_USED);
    int entryOffset = buf.getInt(OFF_ENTRY_OFFSET);
    int numEntries = buf.getInt(OFF_NUM_ENTRIES);
    if (entryOffset <= 0 || entryOffset > buf.limit() || used > buf.limit() || numEntries < 0) {
      return null;
    }

    int pos = entryOffset;
    for (int i = 0; i < numEntries && pos < used; i++) {
      if (pos % 4 != 0) return null;
      buf.position(pos);
      if (buf.remaining() < 20) return null;
      int entryStart = pos;
      int entryLength = buf.getInt();
      int nameOffset = buf.getInt();
      int vectorLength = buf.getInt();
      byte dataType = buf.get(); // 0 = byte/string, 5 = long (PerfDataType ordinal)
      buf.get(); // flags
      buf.get(); // units
      buf.get(); // variability
      int dataOffset = buf.getInt();
      if (entryLength <= 0 || entryStart + entryLength > buf.limit()) return null;
      if (nameOffset > 0 && entryStart + nameOffset < buf.limit()) {
        String name = readCString(buf, entryStart + nameOffset);
        if ("sun.rt.javaCommand".equals(name)
            && dataType
                == 'B' /* PerfDataType.BYTE (string counters are byte arrays with units=STRING) */
            && vectorLength > 0
            && dataOffset > 0
            && entryStart + dataOffset + vectorLength <= buf.limit()) {
          byte[] bytes = new byte[vectorLength];
          buf.position(entryStart + dataOffset);
          buf.get(bytes);
          String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
          int nul = s.indexOf('\0');
          return nul >= 0 ? s.substring(0, nul) : s;
        }
      }
      pos = entryStart + entryLength;
    }
    return null;
  }

  private static String readCString(ByteBuffer buf, int at) {
    StringBuilder sb = new StringBuilder(64);
    for (int i = at; i < buf.limit(); i++) {
      char c = (char) buf.get(i);
      if (c == '\0') break;
      sb.append(c);
      if (sb.length() > 4096) return null; // runaway name: corrupted buffer
    }
    return sb.toString();
  }
}
