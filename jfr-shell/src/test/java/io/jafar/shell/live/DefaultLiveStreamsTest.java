package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production {@link LiveStreams} seam: discovery failure carries the searched base and the
 * {@code --repository} suggestion; {@code attach} hardens the repository (including explicitly
 * user-supplied paths) BEFORE any stream is opened — a rejected attach never constructs a session.
 */
class DefaultLiveStreamsTest {

  @TempDir Path tmp;

  @Test
  void findRepositoryFailureNamesBaseAndSuggestsFlag() {
    var e =
        assertThrows(IOException.class, () -> DefaultLiveStreams.INSTANCE.findRepository(99999999));
    assertTrue(e.getMessage().contains(JvmProcesses.tempBase().toString()), e.getMessage());
    assertTrue(e.getMessage().contains("--repository"), e.getMessage());
  }

  @Test
  void attachRejectsMissingRepositoryBeforeOpeningAnyStream() {
    Path missing = tmp.resolve("no-such-repository");
    var e =
        assertThrows(
            IOException.class, () -> DefaultLiveStreams.INSTANCE.attach(99999999, missing));
    assertTrue(e.getMessage().contains("plausible"), e.getMessage());
    assertTrue(e.getMessage().contains("--repository"), e.getMessage());
  }

  @Test
  void attachRejectsDeadPidRepositoryBeforeOpeningAnyStream() throws Exception {
    // A repository-shaped dir for a PID that cannot be alive: hardening (liveness) rejects it
    // before openRepository is ever called — observable as the IOException arriving without a
    // stream having started.
    Path repo = Files.createDirectories(tmp.resolve("2026_11_09_10_00_00_99999999"));
    Files.write(repo.resolve("0_0.jfr"), new byte[] {'c', 'h', 'u', 'n', 'k'});
    var e =
        assertThrows(IOException.class, () -> DefaultLiveStreams.INSTANCE.attach(99999999, repo));
    assertTrue(e.getMessage().contains("plausible"), e.getMessage());
  }

  @Test
  void searchedBaseIsTheDiscoveryTempBase() {
    assertEquals(JvmProcesses.tempBase(), DefaultLiveStreams.INSTANCE.searchedBase());
  }
}
