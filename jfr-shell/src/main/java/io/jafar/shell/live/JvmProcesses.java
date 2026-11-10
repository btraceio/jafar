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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local JVM discovery without the attach API, in two layers:
 *
 * <ul>
 *   <li>Process listing: scans the HotSpot perf-data tree ({@code <temp base>/hsperfdata_<user>/})
 *       for PID files. The temp base follows HotSpot {@code os::get_temp_directory()} semantics —
 *       hardcoded {@code /tmp} on Linux, the per-user Darwin temp dir on macOS, {@code
 *       %TEMP%}/{@code %TMP%} on Windows — NOT {@code java.io.tmpdir} in general, which a {@code
 *       -Djava.io.tmpdir} override moves independently while hsperfdata and the JFR repository stay
 *       put. Symlinked {@code hsperfdata_*} directories and symlinked PID files are skipped like
 *       repository candidates (planted-entry defense). Only a minimal subset of the perf-data
 *       format is parsed (prologue + entry walk for the {@code java.command} string counter),
 *       mirroring the reference layout in {@code sun.management.counter.perf}; any parse or
 *       permission failure degrades that row to PID-only and never aborts the listing.
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

  /** Perf-data files above this size are rejected, never parsed from a truncated buffer. */
  private static final long MAX_PERF_FILE_SIZE = 1024 * 1024;

  private JvmProcesses() {}

  /**
   * The HotSpot perf-data / JFR-repository temp base for this platform, mirroring {@code
   * os::get_temp_directory()} per platform:
   *
   * <ul>
   *   <li>Linux: hardcoded {@code /tmp} (HotSpot hardcodes it; a target-side {@code -XX:AltTempDir}
   *       is not discoverable from outside — covered by the {@code --repository} flag);
   *   <li>macOS: {@code $TMPDIR} primary; when unset, the fallback is this JVM's {@code
   *       java.io.tmpdir} default (initialized by HotSpot from the same {@code
   *       confstr(_CS_DARWIN_USER_TEMP_DIR)} per-user value, gated to the Darwin {@code
   *       /var/folders/} shape so a launcher-side {@code -Djava.io.tmpdir} override cannot move the
   *       scan base), with {@code /tmp} as a final best-effort guess — a degraded environment may
   *       miss discovery and needs {@code --repository};
   *   <li>Windows: {@code %TEMP%}, then {@code %TMP%}, then {@code java.io.tmpdir} (the per-user
   *       temp default), with {@code C:\\Temp} as a last-resort blank-property default.
   * </ul>
   *
   * NOT {@code java.io.tmpdir} in general — a {@code -Djava.io.tmpdir} override moves that property
   * while hsperfdata and the JFR repository stay put.
   */
  public static Path tempBase() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("windows")) {
      String t = System.getenv("TEMP");
      if (t == null || t.isBlank()) t = System.getenv("TMP");
      if (t == null || t.isBlank()) t = System.getProperty("java.io.tmpdir", "C:\\Temp");
      return Path.of(t);
    }
    if (os.contains("mac") || os.contains("darwin")) {
      String t = System.getenv("TMPDIR");
      if (t != null && !t.isBlank()) return Path.of(t);
      // $TMPDIR unset (cron, non-login ssh, ...): HotSpot never consults $TMPDIR on macOS —
      // os_bsd.cpp os::get_temp_directory() always uses confstr(_CS_DARWIN_USER_TEMP_DIR),
      // whose value the JVM copies into the default java.io.tmpdir. Gate the fallback to the
      // Darwin per-user shape so a -Djava.io.tmpdir override of THIS launcher cannot move the
      // scan base; a degraded environment may miss discovery and needs --repository.
      String tmpdir = System.getProperty("java.io.tmpdir");
      if (tmpdir != null && tmpdir.startsWith("/var/folders/")) return Path.of(tmpdir);
    }
    // last resort (HotSpot's own confstr-failure fallback on macOS); may miss JVMs —
    // use --repository
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
    // One walk of the repository tree per call, shared across all rows (bucketed by PID) —
    // never re-walk the base per row.
    Map<Long, List<Path>> repoIndex;
    try {
      repoIndex = indexRepositoryDirs(base);
    } catch (IOException e) {
      repoIndex = Map.of();
    }
    try (DirectoryStream<Path> hsdirs = Files.newDirectoryStream(base, "hsperfdata_*")) {
      for (Path hsdir : hsdirs) {
        if (!Files.isDirectory(hsdir) || Files.isSymbolicLink(hsdir)) continue;
        try (DirectoryStream<Path> pids = Files.newDirectoryStream(hsdir)) {
          for (Path pidFile : pids) {
            Integer pid = parsePid(pidFile.getFileName().toString());
            if (pid == null) continue;
            // Symlinked or non-regular PID file: skip the command read (a planted FIFO would
            // block the listing; a symlink may point outside the temp tree) but still list
            // the row PID-only, matching the repository-path posture.
            String command = null;
            if (!Files.isSymbolicLink(pidFile) && Files.isRegularFile(pidFile)) {
              command = readJavaCommand(pidFile);
            }
            Path repo = null;
            try {
              repo =
                  resolveRepository(
                      pid,
                      repoIndex.getOrDefault((long) pid, List.of()),
                      base,
                      System.getProperty("user.name"));
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
   * Walks {@code base} once and buckets every {@code yyyy_MM_dd_HH_mm_ss_<pid>} candidate by its
   * trailing PID. Non-directory or symlinked entries are not candidates. The PID is parsed
   * defensively ({@code Long.parseLong}) — a trailing number beyond any real PID is simply not a
   * candidate, never an error.
   */
  static Map<Long, List<Path>> indexRepositoryDirs(Path base) throws IOException {
    Map<Long, List<Path>> index = new java.util.HashMap<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
      for (Path dir : dirs) {
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
        Matcher m = REPO_DIR_PATTERN.matcher(dir.getFileName().toString());
        if (!m.matches()) continue;
        long entryPid;
        try {
          entryPid = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
          continue;
        }
        index.computeIfAbsent(entryPid, k -> new ArrayList<>()).add(dir);
      }
    } catch (IOException e) {
      throw new IOException("Cannot scan " + base + " for JFR repositories: " + e.getMessage(), e);
    }
    return index;
  }

  /**
   * Hardens a PID's candidate bucket (from {@link #indexRepositoryDirs}) down to one repository.
   * Empty bucket → {@link IOException} naming the searched base; several surviving candidates →
   * ambiguous {@link IOException}; exactly one → the path.
   */
  private static Path resolveRepository(int pid, List<Path> bucket, Path base, String currentUser)
      throws IOException {
    List<Path> candidates = new ArrayList<>();
    for (Path dir : bucket) {
      if (isPlausibleRepository(dir, pid, currentUser)) candidates.add(dir);
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
    return candidates.get(0);
  }

  /**
   * Finds and hardens the JFR repository directory for {@code pid}. Rejections and failure modes:
   *
   * <ul>
   *   <li>no matching directory → {@link IOException} (message names the searched base and suggests
   *       {@code --repository})
   *   <li>directory PID tail larger than an int → skipped, not an error
   *   <li>current user name unavailable ({@code user.name} null/blank) → candidate rejected
   *       (fail-closed)
   *   <li>symlinked directory, directory not owned by the current user, or no regular file inside →
   *       candidate rejected (planted-directory defense: the JDK JFR parser would run over
   *       attacker-controlled bytes)
   *   <li>directory mtime predates the process start instant (stale repository of a crashed JVM
   *       under a reused PID) → rejected; a dead PID is rejected outright
   *   <li>several surviving candidates → {@link IOException} naming them; ambiguous is safer than
   *       wrong
   * </ul>
   *
   * @throws IOException when no repository was found (message names the searched base and suggests
   *     {@code --repository})
   */
  public static Path findRepository(int pid) throws IOException {
    return findRepository(pid, tempBase());
  }

  /**
   * {@link #findRepository(int)} over an explicit base — the test seam. The current user is read
   * from {@code user.name} here (production single source of the real user); the 3-arg overload
   * takes it explicitly.
   */
  static Path findRepository(int pid, Path base) throws IOException {
    return findRepository(pid, base, System.getProperty("user.name"));
  }

  /** Full resolution with an explicit current-user parameter (the hardening seam for tests). */
  static Path findRepository(int pid, Path base, String currentUser) throws IOException {
    Map<Long, List<Path>> index = indexRepositoryDirs(base);
    return resolveRepository(pid, index.getOrDefault((long) pid, List.of()), base, currentUser);
  }

  /**
   * Planted-directory defense, parameterized by the expected current user (the hardening seam:
   * tests inject a synthetic user; production passes {@code user.name}). Fail-closed: a missing or
   * blank {@code currentUser} rejects every candidate. Package-visible — re-run at attach time in
   * {@link DefaultLiveStreams} to narrow the discovery-to-open TOCTOU window.
   */
  static boolean isPlausibleRepository(Path dir, int pid, String currentUser) {
    try {
      var attrs = Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class);
      if (attrs.isSymbolicLink() || !attrs.isDirectory()) return false;
      // owner must be the current user — fail closed when it cannot be determined
      String owner = Files.getOwner(dir).getName();
      if (currentUser == null || currentUser.isBlank()) return false;
      if (!ownerMatches(owner, currentUser)) return false;

      // liveness: a crashed JVM leaves its repository behind and PIDs are reused; a PID with
      // no live process at all means the repository is stale
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.get().isAlive()) return false;

      // at least one regular file inside (chunk or metadata file); a symlinked inner entry
      // rejects the candidate outright (do not follow links out of the repository); the first
      // accepted regular file must also be owned by the current user
      boolean hasFile = false;
      try (DirectoryStream<Path> inner = Files.newDirectoryStream(dir)) {
        for (Path f : inner) {
          if (Files.isSymbolicLink(f)) return false;
          if (Files.isRegularFile(f)) {
            if (!ownerMatches(Files.getOwner(f).getName(), currentUser)) return false;
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

  /**
   * Owner comparison tolerant of Windows domain-qualified names ({@code MACHINE\\user} vs the bare
   * {@code user.name}); exact case-sensitive match elsewhere.
   */
  private static boolean ownerMatches(String owner, String user) {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("windows")) {
      if (owner.equalsIgnoreCase(user)) return true;
      int slash = owner.lastIndexOf('\\');
      return slash >= 0 && owner.substring(slash + 1).equalsIgnoreCase(user);
    }
    return user.equals(owner);
  }

  /** The listing legend printed under the shared {@link #renderRow} rows. */
  public static final String JFR_LEGEND = "[jfr] = disk-backed JFR event repository found";

  /**
   * The shared per-row listing line: 90-char command truncation + the {@code [jfr]} repository
   * marker, byte-identical across the interactive dispatcher and the one-shot {@code ps}.
   */
  public static String renderRow(JvmProcess p) {
    String cmd = p.command() == null ? "<unreadable>" : p.command();
    if (cmd.length() > 90) {
      cmd = cmd.substring(0, 89) + "…";
    }
    return "  " + p.pid() + "  " + (p.hasRepository() ? "[jfr] " : "      ") + cmd;
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
   * on any parse failure, permission denial, torn read, file larger than 1 MiB, or non-UTF-8
   * command bytes.
   */
  static String readJavaCommand(Path perfFile) {
    ByteBuffer buf;
    try (FileChannel ch = FileChannel.open(perfFile, StandardOpenOption.READ)) {
      // An oversize file is a reject boundary, not a truncation: parsing a capped buffer
      // would validate offsets against bytes that were never read.
      if (ch.size() > MAX_PERF_FILE_SIZE) return null;
      buf = ByteBuffer.allocate((int) ch.size());
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
      byte dataType =
          buf.get(); // PerfDataType character code: 'B' = byte/string counter, 'J' = long
      buf.get(); // flags
      buf.get(); // units
      buf.get(); // variability
      int dataOffset = buf.getInt();
      // File-supplied offsets: all sums in long arithmetic — int sums can overflow and wrap
      // negative, passing the bounds check on a planted file.
      if (entryLength <= 0 || (long) entryStart + entryLength > buf.limit()) return null;
      if (nameOffset > 0 && (long) entryStart + nameOffset < buf.limit()) {
        String name = readCString(buf, entryStart + nameOffset);
        if ("sun.rt.javaCommand".equals(name)
            && dataType == 'B' // PerfDataType.BYTE: string counters are byte arrays
            && vectorLength > 0
            && vectorLength <= MAX_PERF_FILE_SIZE
            && dataOffset > 0
            && (long) entryStart + dataOffset + vectorLength <= buf.limit()) {
          byte[] bytes = new byte[vectorLength];
          buf.position(entryStart + dataOffset);
          buf.get(bytes);
          // Strict UTF-8: malformed bytes degrade the row to PID-only instead of a silent
          // mojibake replacement.
          String s;
          try {
            s =
                java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
          } catch (java.nio.charset.CharacterCodingException e) {
            return null;
          }
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
