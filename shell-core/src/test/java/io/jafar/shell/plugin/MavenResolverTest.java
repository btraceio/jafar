package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenResolverTest {

  @TempDir Path tempDir;

  private MavenResolver resolver;
  private Path mockLocalRepo;
  private String originalUserHome;

  @BeforeEach
  void setUp() {
    // Save original user.home
    originalUserHome = System.getProperty("user.home");

    // Set up mock local Maven repository
    mockLocalRepo = tempDir.resolve(".m2/repository");
    System.setProperty("user.home", tempDir.toString());

    resolver = new MavenResolver();
  }

  @AfterEach
  void tearDown() {
    // Restore original user.home
    if (originalUserHome != null) {
      System.setProperty("user.home", originalUserHome);
    } else {
      System.clearProperty("user.home");
    }
  }

  @Test
  void resolvesArtifactFromLocalRepository() throws IOException, PluginInstallException {
    // Create mock artifact in local repository
    String groupId = "io.btrace";
    String artifactId = "jfr-shell-jdk";
    String version = "0.9.0-SNAPSHOT";

    Path artifactPath = createMockArtifact(groupId, artifactId, version);

    // Resolve artifact - should find it locally without hitting remote repos
    Path resolved = resolver.resolveArtifact(groupId, artifactId, version);

    assertNotNull(resolved);
    assertEquals(artifactPath, resolved);
    assertTrue(Files.exists(resolved));
  }

  @Test
  void throwsExceptionWhenArtifactNotFoundLocally() {
    // Attempt to resolve non-existent artifact
    assertThrows(
        PluginInstallException.class,
        () -> resolver.resolveArtifact("io.btrace", "nonexistent-plugin", "1.0.0"));
  }

  @Test
  void returnsNullWhenChecksumNotAvailable() {
    // Checksum files are optional - should return null gracefully
    Path checksum = resolver.resolveChecksum("io.btrace", "jfr-shell-jdk", "0.9.0-SNAPSHOT");
    assertNull(checksum);
  }

  @Test
  void buildsCorrectLocalArtifactPath() throws IOException, PluginInstallException {
    // Create artifact
    String groupId = "io.test";
    String artifactId = "test-plugin";
    String version = "1.2.3";

    Path artifactPath = createMockArtifact(groupId, artifactId, version);

    // Verify path structure: groupId/artifactId/version/artifactId-version.jar
    Path resolved = resolver.resolveArtifact(groupId, artifactId, version);

    String expectedPath = mockLocalRepo + "/io/test/test-plugin/1.2.3/test-plugin-1.2.3.jar";
    assertEquals(expectedPath, resolved.toString());
  }

  /**
   * Helper to create a mock artifact in the local Maven repository structure.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @return Path to the created JAR file
   */
  private Path createMockArtifact(String groupId, String artifactId, String version)
      throws IOException {
    String groupPath = groupId.replace('.', '/');
    Path versionDir = mockLocalRepo.resolve(groupPath).resolve(artifactId).resolve(version);
    Files.createDirectories(versionDir);

    Path jarFile = versionDir.resolve(artifactId + "-" + version + ".jar");
    Files.writeString(jarFile, "mock jar content");

    return jarFile;
  }
}
