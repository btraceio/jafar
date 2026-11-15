package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Discovery tests for {@link JvmProcesses}: perf-data parsing against synthetic fixtures,
 * repository-directory matching and hardening against synthetic temp trees. Live-process discovery
 * itself is covered by the {@code LiveTailIT} integration test.
 */
class JvmProcessesTest {

  @TempDir Path tmp;

  // ---- hsperfdata parsing ----

  /**
   * Builds a version-2.0 perf-data buffer in the given byte order with one string counter ({@code
   * sun.rt.javaCommand}) and one long counter, following the entry layout of {@code
   * sun.management.counter.perf.PerfDataEntry}.
   */
  private static byte[] perfFile(String javaCommand, ByteOrder order) {
    String[] names = {"sun.rt.javaCommand", "java.threads.live"};
    byte[][] values = {
      javaCommand.getBytes(StandardCharsets.UTF_8), new byte[] {0, 0, 0, 0, 0, 0, 0, 7}
    };
    int[] types = {'B', 'J'}; // PerfDataType byte codes: BYTE='B', LONG='J'

    int prologue = 32;
    int pos = prologue;
    int[][] layout = new int[names.length][]; // {entryStart, nameOffset, dataOffset}
    for (int i = 0; i < names.length; i++) {
      int nameLen = names[i].length() + 1; // + NUL
      int dataLen = values[i].length;
      int headerLen = 20; // entry fields through data_offset
      layout[i] = new int[] {pos, pos + headerLen, pos + headerLen + nameLen};
      pos = pos + headerLen + nameLen + dataLen;
      pos = (pos + 3) & ~3; // entries are 4-byte aligned
    }

    ByteBuffer b = ByteBuffer.allocate(pos).order(order);
    // prologue: magic is always big-endian
    int save = b.position();
    b.order(ByteOrder.BIG_ENDIAN).putInt(0, 0xcafec0c0).order(order);
    b.put(4, (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 0));
    b.put(5, (byte) 2); // major
    b.put(6, (byte) 0); // minor
    b.put(7, (byte) 1); // accessible
    b.putInt(8, pos); // used
    b.putLong(16, 0L); // mod timestamp
    b.putInt(24, prologue); // entry offset
    b.putInt(28, names.length); // num entries

    for (int i = 0; i < names.length; i++) {
      int entryStart = layout[i][0];
      b.position(entryStart);
      b.putInt(pos - entryStart); // entry length (through alignment)
      b.putInt(layout[i][1] - entryStart); // name offset
      b.putInt(values[i].length); // vector length
      b.put((byte) types[i]); // data type
      b.put((byte) 0); // flags
      b.put((byte) 3); // units (STRING for i=0; value irrelevant to the parser)
      b.put((byte) 1); // variability
      b.putInt(layout[i][2] - entryStart); // data offset
      // name (NUL-terminated)
      b.position(layout[i][1]).put(names[i].getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
      // data
      b.position(layout[i][2]).put(values[i]);
    }
    return b.array();
  }

  @Test
  void parsesJavaCommandLittleAndBigEndian() throws Exception {
    for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
      Path f = tmp.resolve("perf-le-" + order);
      Files.write(f, perfFile("com.example.Main --flag", order));
      assertEquals("com.example.Main --flag", JvmProcesses.readJavaCommand(f), order.toString());
    }
  }

  @Test
  void garbageAndTruncatedFilesDegradeToNull() throws Exception {
    Path garbage = tmp.resolve("perf-garbage");
    Files.write(garbage, new byte[] {1, 2, 3, 4, 5});
    assertNull(JvmProcesses.readJavaCommand(garbage));

    Path truncated = tmp.resolve("perf-truncated");
    byte[] full = perfFile("x", ByteOrder.LITTLE_ENDIAN);
    Files.write(truncated, java.util.Arrays.copyOf(full, full.length / 2));
    assertNull(JvmProcesses.readJavaCommand(truncated));

    Path missing = tmp.resolve("perf-missing");
    assertNull(JvmProcesses.readJavaCommand(missing));
  }

  // ---- repository discovery ----

  private Path syntheticRepo(Path base, int pid, String name) throws IOException {
    Path repo = Files.createDirectories(base.resolve(name));
    Files.write(repo.resolve("0_0.jfr"), new byte[] {'c', 'h', 'u', 'n', 'k'}); // chunk-like file
    return repo;
  }

  private Path syntheticBase(int pid) throws IOException {
    Path base = Files.createDirectories(tmp.resolve("base" + pid));
    Path hs =
        Files.createDirectories(base.resolve("hsperfdata_" + System.getProperty("user.name")));
    Files.write(hs.resolve(String.valueOf(pid)), new byte[] {'x'});
    return base;
  }

  @Test
  void findsRepositoryForLivePid() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);

    var found = JvmProcesses.findRepository(pid, base);
    assertEquals("2026_11_09_10_00_00_" + pid, found.getFileName().toString());
  }

  @Test
  void missingRepositoryNamesBaseAndSuggestsFlag() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    var e = assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base));
    assertTrue(e.getMessage().contains(base.toString()), e.getMessage());
    assertTrue(e.getMessage().contains("--repository"), e.getMessage());
  }

  @Test
  void deadPidRepositoryIsRejected() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    // a directory named after a PID that cannot be alive (2^28 is well beyond any Linux
    // pid_max / macOS default); the stale repository must not be accepted
    Path dead = base.resolve("2026_11_09_10_00_00_99999999");
    Files.createDirectories(dead);
    var e = assertThrows(IOException.class, () -> JvmProcesses.findRepository(99999999, base));
    assertTrue(e.getMessage().contains("No JFR event repository"), e.getMessage());
  }

  @Test
  void ambiguousMatchesAreRejectedWithCandidates() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_01_" + pid);
    var e = assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base));
    assertTrue(e.getMessage().contains("Ambiguous"), e.getMessage());
    assertTrue(e.getMessage().contains("--repository"), e.getMessage());
  }

  @Test
  void symlinkedRepositoryIsRejected() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    Path real =
        syntheticRepo(
                Files.createDirectories(tmp.resolve("planted-base")),
                99999999,
                "2026_11_09_10_00_00_99999999")
            .getParent();
    try {
      Files.createSymbolicLink(base.resolve("2026_11_09_10_00_00_" + pid), real);
    } catch (UnsupportedOperationException | IOException e) {
      // filesystem without symlink support: nothing to test here
      return;
    }
    assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base));
  }

  /**
   * Exercises the owner-ACCEPT side of the {@code isPlausibleRepository} owner check (owner ==
   * user.name). The owner-REJECTION branch is NOT covered here: changing a file's owner requires
   * privileges, so a mismatch cannot be synthesized in this fixture — see {@link
   * #foreignOwnerRepositoryIsRejectedViaSeam} for the parameterized-seam coverage.
   */
  @Test
  void ownedRepositoryIsAccepted() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    Path repo = syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    try {
      Files.setPosixFilePermissions(repo, PosixFilePermissions.fromString("rwxrwxrwx"));
      assertNotNull(JvmProcesses.findRepository(pid, base));
    } catch (UnsupportedOperationException e) {
      // non-POSIX filesystem (Windows CI): permission model differs, skip
    }
  }

  /** True owner rejection, via the 3-arg hardening seam (synthetic current user). */
  @Test
  void foreignOwnerRepositoryIsRejectedViaSeam() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    var e =
        assertThrows(
            IOException.class, () -> JvmProcesses.findRepository(pid, base, "somebody-else"));
    assertTrue(e.getMessage().contains("No JFR event repository"), e.getMessage());
  }

  /** Fail-closed hardening: a blank current user rejects every candidate. */
  @Test
  void blankCurrentUserIsRejectedFailClosed() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base, "  "));
  }

  /** A symlinked INNER entry (chunk file) rejects the whole candidate. */
  @Test
  void symlinkedInnerChunkEntryIsRejected() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    Path repo = syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    Path outside = Files.write(tmp.resolve("outside.bin"), new byte[] {'x'});
    try {
      Files.delete(repo.resolve("0_0.jfr"));
      Files.createSymbolicLink(repo.resolve("planted.jfr"), outside);
    } catch (UnsupportedOperationException | IOException e) {
      // filesystem without symlink support: nothing to test here
      return;
    }
    assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base));
  }

  /** A 10-digit directory PID tail beyond int range is skipped, not a NumberFormatException. */
  @Test
  void overIntPidDirectoryNameIsSkippedNotFatal() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    Files.createDirectories(base.resolve("2026_11_09_10_00_00_9999999999"));
    var e = assertThrows(IOException.class, () -> JvmProcesses.findRepository(pid, base));
    assertTrue(e.getMessage().contains("No JFR event repository"), e.getMessage());
  }

  /** An oversize perf-data file is a reject boundary: null, never a truncated parse. */
  @Test
  void oversizePerfFileDegradesToNull() throws Exception {
    Path big = tmp.resolve("perf-oversize");
    byte[] valid = perfFile("x", ByteOrder.LITTLE_ENDIAN);
    byte[] oversized = new byte[1024 * 1024 + 1];
    System.arraycopy(valid, 0, oversized, 0, valid.length);
    Files.write(big, oversized);
    assertNull(JvmProcesses.readJavaCommand(big));
  }

  /** Invalid UTF-8 in the command vector degrades to null (strict decoder, no mojibake). */
  @Test
  void invalidUtf8CommandBytesDegradeToNull() throws Exception {
    for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
      byte[] bad = perfFile("ok", order);
      // corrupt the command vector's first byte into a lone 0xFF (never valid UTF-8)
      for (int i = 32; i < bad.length; i++) {
        if (bad[i] == 'o' && i + 1 < bad.length && bad[i + 1] == 'k') {
          bad[i] = (byte) 0xFF;
          break;
        }
      }
      Path f = tmp.resolve("perf-bad-utf8-" + order);
      Files.write(f, bad);
      assertNull(JvmProcesses.readJavaCommand(f), order.toString());
    }
  }

  /** An entryLength crafted to overflow int arithmetic must not throw out of the parser. */
  @Test
  void overflowingEntryLengthDegradesToNull() throws Exception {
    byte[] buf = new byte[64];
    ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
    b.order(ByteOrder.BIG_ENDIAN).putInt(0, 0xcafec0c0).order(ByteOrder.LITTLE_ENDIAN);
    b.put(4, (byte) 0);
    b.put(5, (byte) 2); // major
    b.putInt(8, 64); // used
    b.putInt(24, 32); // entry offset
    b.putInt(28, 1); // num entries
    b.position(32);
    b.putInt(0x7FFFFFF0); // entryLength: entryStart + entryLength overflows int
    b.putInt(4); // nameOffset
    b.putInt(4); // vectorLength
    b.put((byte) 'B');
    b.put((byte) 0);
    b.put((byte) 3);
    b.put((byte) 1);
    b.putInt(8); // dataOffset
    Path f = tmp.resolve("perf-overflow");
    Files.write(f, buf);
    assertDoesNotThrow(() -> assertNull(JvmProcesses.readJavaCommand(f)));
  }

  /** Symlinked hsperfdata dir / PID file are skipped (planted-entry defense). */
  @Test
  void symlinkedPerfDataEntriesAreSkipped() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    Path outsidePerf = tmp.resolve("outside-perf");
    Files.write(outsidePerf, perfFile("com.example.Outside", ByteOrder.LITTLE_ENDIAN));
    try {
      Files.createSymbolicLink(
          base.resolve("hsperfdata_" + System.getProperty("user.name"))
              .resolve(String.valueOf(pid)),
          outsidePerf);
    } catch (UnsupportedOperationException | IOException e) {
      return; // no symlink support
    }
    List<JvmProcess> list = JvmProcesses.listLocalJvm(base);
    // the symlinked PID file is skipped: any listed row for the pid is PID-only
    for (JvmProcess p : list) {
      if (p.pid() == pid) {
        assertNull(p.command(), "symlinked PID file must not be read: " + p);
      }
    }
  }

  /** Shared repository index: multiple PIDs in one base walk, one row per PID. */
  @Test
  void listingCoversMultiplePidsWithOneBaseWalk() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    // a second PID with no repository: row still lists, repository null
    Path hs = base.resolve("hsperfdata_" + System.getProperty("user.name"));
    Files.write(hs.resolve("99999998"), new byte[] {'x'});
    List<JvmProcess> list = JvmProcesses.listLocalJvm(base);
    assertEquals(2, list.size());
    assertTrue(list.get(0).hasRepository());
    assertFalse(list.get(1).hasRepository());
  }

  @Test
  void listingDegradesToPidOnlyOnUnreadablePerfFile() throws Exception {
    int pid = (int) ProcessHandle.current().pid();
    Path base = syntheticBase(pid);
    syntheticRepo(base, pid, "2026_11_09_10_00_00_" + pid);
    Files.write(
        base.resolve("hsperfdata_" + System.getProperty("user.name")).resolve(String.valueOf(pid)),
        new byte[] {1, 2, 3}); // not a perf file

    List<JvmProcess> list = JvmProcesses.listLocalJvm(base);
    assertEquals(1, list.size());
    assertEquals(pid, list.get(0).pid());
    assertNull(list.get(0).command()); // garbage perf file → PID-only row
    assertTrue(list.get(0).hasRepository());
  }

  @Test
  void nonNumericEntriesAreIgnored() throws Exception {
    Path base = Files.createDirectories(tmp.resolve("base-ign"));
    Path hs =
        Files.createDirectories(base.resolve("hsperfdata_" + System.getProperty("user.name")));
    Files.write(hs.resolve("not-a-pid"), new byte[] {'x'});
    assertTrue(JvmProcesses.listLocalJvm(base).isEmpty());
  }
}
