package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRegistryTest {

  @TempDir Path tempDir;

  private static final String EMPTY_REGISTRY = "{\"plugins\": {}}";

  private PluginStorageManager storageManager;
  private PluginRegistry registry;
  private Path mockMavenRepo;
  private Path mockMavenRepoRoot;

  @BeforeEach
  void setUp() throws IOException {
    // Create mock Maven repository structure
    mockMavenRepoRoot = tempDir.resolve(".m2/repository");
    mockMavenRepo = mockMavenRepoRoot.resolve("io/btrace");
    Files.createDirectories(mockMavenRepo);

    storageManager = mock(PluginStorageManager.class);

    // Mock storage manager to return empty remote registry (prevents GitHub fetch)
    when(storageManager.loadRegistryCache()).thenReturn(EMPTY_REGISTRY);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    // Use 3-arg constructor with empty bundled JSON to bypass classpath resource loading
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);
  }

  @AfterEach
  void tearDown() {
    // Cleanup handled by @TempDir
  }

  @Test
  void discoversPluginFromLocalMaven() throws IOException {
    // Create mock artifact structure: <mockRepo>/io/btrace/jfr-shell-jdk/0.9.0-SNAPSHOT/
    Path artifactDir = mockMavenRepo.resolve("jfr-shell-jdk");
    Path versionDir = artifactDir.resolve("0.9.0-SNAPSHOT");
    Files.createDirectories(versionDir);

    // Create new registry to pick up the artifact
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Verify plugin is discovered
    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals("jfr-shell-jdk", result.get().artifactId());
    assertEquals("0.9.0-SNAPSHOT", result.get().version());
    assertEquals("io.btrace", result.get().groupId());
  }

  @Test
  void discoversLatestVersionWhenMultipleExist() throws IOException {
    // Create multiple versions
    Path artifactDir = mockMavenRepo.resolve("jfr-shell-jdk");
    Files.createDirectories(artifactDir.resolve("0.9.0-SNAPSHOT"));
    Files.createDirectories(artifactDir.resolve("0.9.1-SNAPSHOT"));
    Files.createDirectories(artifactDir.resolve("0.8.0"));

    // Create new registry to discover artifacts
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    // String comparison: "0.9.1-SNAPSHOT" > "0.9.0-SNAPSHOT" > "0.8.0"
    assertEquals("0.9.1-SNAPSHOT", result.get().version());
  }

  @Test
  void returnsEmptyWhenPluginNotFound() {
    Optional<PluginRegistry.PluginDefinition> result = registry.get("nonexistent");
    assertFalse(result.isPresent());
  }

  @Test
  void ignoresNonPluginArtifacts() throws IOException {
    // Create non-plugin artifacts (don't start with "jfr-shell-")
    Files.createDirectories(mockMavenRepo.resolve("some-other-artifact/1.0.0"));
    Files.createDirectories(mockMavenRepo.resolve("jafar-parser/0.9.0"));

    // Create new registry to scan artifacts
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Should only find jfr-shell-* artifacts
    assertEquals(Optional.empty(), registry.get("some-other-artifact"));
    assertEquals(Optional.empty(), registry.get("jafar-parser"));
  }

  @Test
  void extractsPluginIdFromArtifactId() throws IOException {
    // Create various plugin artifacts
    Files.createDirectories(mockMavenRepo.resolve("jfr-shell-jdk/1.0.0"));
    Files.createDirectories(mockMavenRepo.resolve("jfr-shell-jafar/1.0.0"));
    Files.createDirectories(mockMavenRepo.resolve("jfr-shell-custom-backend/1.0.0"));

    // Create new registry to discover artifacts
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Verify plugin IDs are extracted correctly (strip "jfr-shell-" prefix)
    assertTrue(registry.get("jdk").isPresent());
    assertTrue(registry.get("jafar").isPresent());
    assertTrue(registry.get("custom-backend").isPresent());
  }

  @Test
  void handlesEmptyMavenRepository() {
    // Registry should work even with no local Maven artifacts
    Optional<PluginRegistry.PluginDefinition> result = registry.get("anything");
    assertFalse(result.isPresent());
  }

  @Test
  void handlesMissingMavenRepository() throws IOException {
    // Use non-existent path
    Path nonExistentPath = tempDir.resolve("nonexistent");

    PluginRegistry emptyRegistry =
        new PluginRegistry(storageManager, nonExistentPath, EMPTY_REGISTRY);

    Optional<PluginRegistry.PluginDefinition> result = emptyRegistry.get("jdk");
    assertFalse(result.isPresent());
  }

  @Test
  void parsesRemoteRegistryJson() throws IOException {
    // Mock valid JSON registry
    String validJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.2.0",
              "repository": "central"
            },
            "jafar": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jafar",
              "latestVersion": "0.3.0",
              "repository": "sonatype-snapshots"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(validJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    // Create new registry to load cached JSON (empty bundled to avoid classpath interference)
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Verify remote plugins are parsed correctly
    Optional<PluginRegistry.PluginDefinition> jdk = registry.get("jdk");
    assertTrue(jdk.isPresent());
    assertEquals("jfr-shell-jdk", jdk.get().artifactId());
    assertEquals("0.2.0", jdk.get().version());
    assertEquals("central", jdk.get().repository());

    Optional<PluginRegistry.PluginDefinition> jafar = registry.get("jafar");
    assertTrue(jafar.isPresent());
    assertEquals("jfr-shell-jafar", jafar.get().artifactId());
    assertEquals("0.3.0", jafar.get().version());
    assertEquals("sonatype-snapshots", jafar.get().repository());
  }

  @Test
  void remotePluginsTakePriorityOverLocal() throws IOException {
    // Create local Maven artifact
    Path artifactDir = mockMavenRepo.resolve("jfr-shell-jdk");
    Files.createDirectories(artifactDir.resolve("0.1.0-SNAPSHOT"));

    // Mock remote registry with newer version
    String remoteJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.2.0",
              "repository": "central"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(remoteJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    // Create new registry (empty bundled to avoid classpath interference)
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Remote should take priority
    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals("0.2.0", result.get().version(), "Remote version should take priority");
  }

  @Test
  void handlesInvalidRemoteJson() throws IOException {
    // Mock invalid JSON
    when(storageManager.loadRegistryCache()).thenReturn("not valid json {]");
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    // Should not crash, just skip remote plugins
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Should still work with local Maven discovery
    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertFalse(result.isPresent());
  }

  @Test
  void handlesEmptyRemoteRegistry() throws IOException {
    // Mock empty but valid JSON
    String emptyJson =
        """
        {
          "plugins": {}
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(emptyJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    // Should work but find no plugins
    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertFalse(result.isPresent());
  }

  // --- Bundled plugin and compatibility tests ---

  @Test
  void compatibleRemoteOverridesBundled() throws IOException {
    String bundledJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.11.0",
              "repository": "maven-central"
            }
          }
        }
        """;
    // Remote has same minor but newer patch - should be accepted
    String remoteJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.11.4",
              "repository": "maven-central"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(remoteJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, bundledJson);

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals("0.11.4", result.get().version(), "Compatible remote should override bundled");
  }

  @Test
  void incompatibleRemoteMinorFallsToBundled() throws IOException {
    String bundledJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.11.0",
              "repository": "maven-central"
            }
          }
        }
        """;
    // Remote has different minor (0.12 vs 0.11) - should fall back to bundled
    String remoteJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.12.0",
              "repository": "maven-central"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(remoteJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, bundledJson);

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals(
        "0.11.0", result.get().version(), "Incompatible remote should fall back to bundled");
  }

  @Test
  void incompatibleRemoteMajorFallsToBundled() throws IOException {
    String bundledJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.11.0",
              "repository": "maven-central"
            }
          }
        }
        """;
    // Remote has major bump (1.x vs 0.x) - should fall back to bundled
    String remoteJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "1.0.0",
              "repository": "maven-central"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(remoteJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, bundledJson);

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals(
        "0.11.0", result.get().version(), "Major version bump should fall back to bundled");
  }

  @Test
  void networkFailureWithNoCacheFallsToBundled() throws IOException {
    String bundledJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "0.11.0",
              "repository": "maven-central"
            }
          }
        }
        """;

    // No remote cache, no network (use unreachable URL to simulate network failure)
    when(storageManager.loadRegistryCache()).thenReturn(null);
    when(storageManager.getRegistryCacheTime()).thenReturn(null);

    registry =
        new PluginRegistry(
            storageManager, mockMavenRepoRoot, bundledJson, "http://192.0.2.1:1/fail");

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals("0.11.0", result.get().version(), "Should fall back to bundled on total failure");
  }

  @Test
  void noBundledAcceptsAnyRemote() throws IOException {
    // Remote with any version should be accepted when no bundled plugins
    String remoteJson =
        """
        {
          "plugins": {
            "jdk": {
              "groupId": "io.btrace",
              "artifactId": "jfr-shell-jdk",
              "latestVersion": "5.0.0",
              "repository": "maven-central"
            }
          }
        }
        """;

    when(storageManager.loadRegistryCache()).thenReturn(remoteJson);
    when(storageManager.getRegistryCacheTime()).thenReturn(java.time.Instant.now());

    // Empty bundled JSON - backward compat mode
    registry = new PluginRegistry(storageManager, mockMavenRepoRoot, EMPTY_REGISTRY);

    Optional<PluginRegistry.PluginDefinition> result = registry.get("jdk");
    assertTrue(result.isPresent());
    assertEquals("5.0.0", result.get().version(), "No bundled should accept any remote");
  }

  @Test
  void isRemoteCompatibleWithEmptyBundled() {
    Map<String, PluginRegistry.PluginDefinition> remote =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "9.9.9", "central"));
    Map<String, PluginRegistry.PluginDefinition> bundled = Map.of();

    assertTrue(PluginRegistry.isRemoteCompatible(remote, bundled));
  }

  @Test
  void isRemoteCompatibleMatchingMinorPreV1() {
    Map<String, PluginRegistry.PluginDefinition> remote =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "0.11.4", "central"));
    Map<String, PluginRegistry.PluginDefinition> bundled =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "0.11.0", "central"));

    assertTrue(PluginRegistry.isRemoteCompatible(remote, bundled));
  }

  @Test
  void isRemoteIncompatibleDifferentMinorPreV1() {
    Map<String, PluginRegistry.PluginDefinition> remote =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "0.12.0", "central"));
    Map<String, PluginRegistry.PluginDefinition> bundled =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "0.11.0", "central"));

    assertFalse(PluginRegistry.isRemoteCompatible(remote, bundled));
  }

  @Test
  void isRemoteCompatibleMatchingMajorPostV1() {
    Map<String, PluginRegistry.PluginDefinition> remote =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "1.3.0", "central"));
    Map<String, PluginRegistry.PluginDefinition> bundled =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "1.0.0", "central"));

    assertTrue(PluginRegistry.isRemoteCompatible(remote, bundled));
  }

  @Test
  void isRemoteIncompatibleDifferentMajorPostV1() {
    Map<String, PluginRegistry.PluginDefinition> remote =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "2.0.0", "central"));
    Map<String, PluginRegistry.PluginDefinition> bundled =
        Map.of(
            "jdk",
            new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "1.0.0", "central"));

    assertFalse(PluginRegistry.isRemoteCompatible(remote, bundled));
  }
}
