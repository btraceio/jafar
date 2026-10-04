package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  void resolvesDependenciesWithMavenScopeAndManagementSemantics()
      throws IOException, PluginInstallException {
    // A thin plugin jar whose POM exercises the semantics transitive resolution must honor: a
    // BOM-managed version (lib-a is declared without a version), a runtime-scoped dep, a test
    // and a provided dep that must NOT be copied, and a same-depth version conflict (both lib-a
    // and lib-b depend on lib-e) that resolves nearest-first to a single lib-e.
    writeFixturePom(
        "plugin-thin",
        "plugin-thin",
        "1.0.0",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.test</groupId>
          <artifactId>plugin-thin</artifactId>
          <version>1.0.0</version>
          <packaging>jar</packaging>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>io.test</groupId>
                <artifactId>versions-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-a</artifactId>
            </dependency>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-b</artifactId>
              <version>1.0.0</version>
              <scope>runtime</scope>
            </dependency>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-c</artifactId>
              <version>1.0.0</version>
              <scope>test</scope>
            </dependency>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-d</artifactId>
              <version>1.0.0</version>
              <scope>provided</scope>
            </dependency>
          </dependencies>
        </project>
        """);
    writeFixturePom("versions-bom", "versions-bom", "1.0.0", bomPom());
    writeFixturePom("lib-a", "lib-a", "1.2.0", depPom("lib-a", "1.2.0", "lib-e", "1.0.0"));
    writeFixturePom("lib-b", "lib-b", "1.0.0", depPom("lib-b", "1.0.0", "lib-e", "2.0.0"));
    writeFixturePom("lib-c", "lib-c", "1.0.0", depPom("lib-c", "1.0.0", null, null));
    writeFixturePom("lib-d", "lib-d", "1.0.0", depPom("lib-d", "1.0.0", null, null));
    writeFixturePom("lib-e", "lib-e", "1.0.0", depPom("lib-e", "1.0.0", null, null));
    writeFixturePom("lib-e", "lib-e", "2.0.0", depPom("lib-e", "2.0.0", null, null));
    createMockArtifact("io.test", "plugin-thin", "1.0.0");
    createMockArtifact("io.test", "lib-a", "1.2.0");
    createMockArtifact("io.test", "lib-b", "1.0.0");
    createMockArtifact("io.test", "lib-c", "1.0.0");
    createMockArtifact("io.test", "lib-d", "1.0.0");
    createMockArtifact("io.test", "lib-e", "1.0.0");
    createMockArtifact("io.test", "lib-e", "2.0.0");

    MavenResolver.ResolvedPlugin resolved =
        resolver.resolveWithDependencies("io.test", "plugin-thin", "1.0.0");

    assertEquals(
        mockLocalRepo.resolve("io/test/plugin-thin/1.0.0/plugin-thin-1.0.0.jar"),
        resolved.rootJar(),
        "root jar resolves from the local repository");
    List<String> depIds =
        resolved.dependencies().stream()
            .map(a -> a.getArtifactId() + ":" + a.getBaseVersion())
            .sorted()
            .toList();
    assertEquals(List.of("lib-a:1.2.0", "lib-b:1.0.0", "lib-e:1.0.0"), depIds);
  }

  private String bomPom() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.test</groupId>
          <artifactId>versions-bom</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>io.test</groupId>
                <artifactId>lib-a</artifactId>
                <version>1.2.0</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """;
  }

  private String depPom(String artifactId, String version, String depArtifact, String depVersion) {
    String dependency =
        depArtifact == null
            ? ""
            : """
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </dependency>
            """
                .formatted(depArtifact, depVersion);
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.test</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
          <packaging>jar</packaging>
          <dependencies>%s</dependencies>
        </project>
        """
        .formatted(artifactId, version, dependency);
  }

  private void writeFixturePom(String group, String artifactId, String version, String content)
      throws IOException {
    Path versionDir =
        mockLocalRepo.resolve("io.test".replace('.', '/')).resolve(artifactId).resolve(version);
    Files.createDirectories(versionDir);
    Files.writeString(versionDir.resolve(artifactId + "-" + version + ".pom"), content);
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
