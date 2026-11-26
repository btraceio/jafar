package io.jafar.shell.backend.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edges of {@link JdkJfrBackend.JdkMetadataSource}'s chunk-local type recovery (#198): the scan
 * cap and the once-per-recording warning.
 *
 * <p>Each test works on its own copy of the recording: the warning state is one process-wide slot
 * keyed by path, which other tests also touch.
 */
class JdkMetadataSourceRecoveryTest {

  private static final Path RECORDING =
      Path.of(
          System.getProperty("jfr.test.dd.recording", "../demo/src/test/resources/test-dd.jfr"));

  /** In test-dd.jfr only events carry this type: the merged view drops it on an id collision. */
  private static final String CHUNK_LOCAL = "datadog.ExecutionSample";

  private static final String CAP_WARNING = "Stopped the chunk-local type recovery scan";
  private static final String CHUNK_LOCAL_WARNING = "redefines type ids across chunks";

  @TempDir Path tempDir;

  private final List<String> warnings = new ArrayList<>();

  @BeforeEach
  void assumeRecording() {
    // A git-ignored fixture from ./get_resources.sh (CI fetches it): skip without it.
    Assumptions.assumeTrue(isJfrRecording(RECORDING), "recording not available: " + RECORDING);
  }

  @Test
  void capReachedWhileLookingUpATypeReturnsNullAndWarnsOnce() throws Exception {
    Path recording = copy("cap-lookup.jfr");
    JdkJfrBackend.JdkMetadataSource capped = new JdkJfrBackend.JdkMetadataSource(0, warnings::add);

    assertNull(capped.loadClass(recording, CHUNK_LOCAL));

    assertEquals(1, count(CAP_WARNING), warnings.toString());
    assertTrue(warnings.get(0).contains("'" + CHUNK_LOCAL + "'"), warnings.get(0));
    assertNotNull(
        new JdkJfrBackend.JdkMetadataSource(Long.MAX_VALUE, warnings::add)
            .loadClass(recording, CHUNK_LOCAL),
        "the same lookup without a cap finds the type");
  }

  @Test
  void capReachedWhileListingReturnsTheTypesFoundSoFarAndWarnsOnce() throws Exception {
    Path recording = copy("cap-listing.jfr");
    Set<String> full =
        names(
            new JdkJfrBackend.JdkMetadataSource(Long.MAX_VALUE, s -> {}).loadAllClasses(recording));

    Set<String> partial =
        names(new JdkJfrBackend.JdkMetadataSource(0, warnings::add).loadAllClasses(recording));

    assertEquals(1, count(CAP_WARNING), warnings.toString());
    assertFalse(partial.isEmpty(), "the merged view is still listed");
    assertTrue(full.containsAll(partial), "nothing is listed that the full scan does not list");
    assertTrue(full.contains(CHUNK_LOCAL) && !partial.contains(CHUNK_LOCAL), partial.toString());
  }

  @Test
  void chunkLocalTypesWarningRepeatsOnlyWhenTheRecordingChanges() throws Exception {
    Path a = copy("a.jfr");
    Path b = copy("b.jfr");
    JdkJfrBackend.JdkMetadataSource source =
        new JdkJfrBackend.JdkMetadataSource(Long.MAX_VALUE, warnings::add);

    source.loadAllClasses(a);
    assertEquals(1, count(CHUNK_LOCAL_WARNING), "first listing of A: " + warnings);
    source.loadAllClasses(a);
    source.loadClass(a, CHUNK_LOCAL);
    assertEquals(1, count(CHUNK_LOCAL_WARNING), "A again, listing and lookup: " + warnings);
    source.loadAllClasses(b);
    assertEquals(2, count(CHUNK_LOCAL_WARNING), "then B: " + warnings);
    source.loadAllClasses(a);
    assertEquals(3, count(CHUNK_LOCAL_WARNING), "back to A: " + warnings);
  }

  private long count(String warning) {
    return warnings.stream().filter(w -> w.contains(warning)).count();
  }

  private static Set<String> names(List<Map<String, Object>> types) {
    return types.stream().map(t -> (String) t.get("name")).collect(Collectors.toSet());
  }

  private Path copy(String name) throws IOException {
    return Files.copy(RECORDING, tempDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
  }

  private static boolean isJfrRecording(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    try (InputStream in = Files.newInputStream(path)) {
      byte[] magic = in.readNBytes(4);
      return magic.length == 4
          && magic[0] == 'F'
          && magic[1] == 'L'
          && magic[2] == 'R'
          && magic[3] == 0;
    } catch (IOException e) {
      return false;
    }
  }
}
