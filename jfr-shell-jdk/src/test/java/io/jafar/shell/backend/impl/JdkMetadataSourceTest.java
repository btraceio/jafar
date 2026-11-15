package io.jafar.shell.backend.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for multi-chunk recordings whose chunks redefine type ids (e.g. produced by the
 * Datadog profiler). {@code RecordingFile.readEventTypes()} merges such chunks into a single
 * id-to-name mapping, dropping chunk-local types such as {@code datadog.ExecutionSample}.
 */
class JdkMetadataSourceTest {

  private static final Path RECORDING =
      Path.of(
          System.getProperty("jfr.test.dd.recording", "../demo/src/test/resources/test-dd.jfr"));

  private final JdkJfrBackend.JdkMetadataSource source = new JdkJfrBackend.JdkMetadataSource();

  private static void assumeRecording() {
    // The fixture is a git-ignored recording downloaded by ./get_resources.sh (CI fetches it).
    // Without it, skip rather than fail; the same behavior the other recording-dependent suites
    // get from a missing parser-core fixture.
    Assumptions.assumeTrue(
        isJfrRecording(RECORDING),
        "recording not available or not a JFR file (missing 'FLR\\0' magic), "
            + "run ./get_resources.sh first: "
            + RECORDING);
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

  @Test
  @DisplayName("chunk-local type hidden by the merged metadata view is still resolvable")
  void resolvesChunkLocalType() throws Exception {
    assumeRecording();
    Map<String, Object> meta = source.loadClass(RECORDING, "datadog.ExecutionSample");
    assertNotNull(
        meta, "datadog.ExecutionSample must resolve even though readEventTypes() hides it");
    assertEquals("datadog.ExecutionSample", meta.get("name"));
    @SuppressWarnings("unchecked")
    Map<String, Object> fields = (Map<String, Object>) meta.get("fieldsByName");
    assertTrue(
        fields.containsKey("spanId"),
        "expected datadog.ExecutionSample fields, got: " + fields.keySet());
  }

  @Test
  @DisplayName("type defined in every chunk with a stable id resolves via the merged view")
  void resolvesUbiquitousType() throws Exception {
    assumeRecording();
    Map<String, Object> meta = source.loadClass(RECORDING, "jdk.VirtualizationInformation");
    assertNotNull(meta);
    assertEquals("jdk.VirtualizationInformation", meta.get("name"));
  }

  @Test
  @DisplayName("unknown type returns null")
  void unknownTypeReturnsNull() throws Exception {
    assumeRecording();
    assertNull(source.loadClass(RECORDING, "does.not.Exist"));
  }

  @Test
  @DisplayName("loadAllClasses includes chunk-local types missing from the merged view")
  void loadAllClassesIncludesChunkLocalTypes() throws Exception {
    assumeRecording();
    List<Map<String, Object>> all = source.loadAllClasses(RECORDING);
    List<String> names = all.stream().map(m -> String.valueOf(m.get("name"))).toList();
    assertTrue(names.contains("jdk.VirtualizationInformation"), "merged view types must stay");
    assertTrue(
        names.contains("datadog.ExecutionSample"),
        "chunk-local datadog.ExecutionSample must be recovered, got: " + names);
  }
}
