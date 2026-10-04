package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Install from a catalog goes through the whole pipeline end to end: resolve the plugin jar, then
 * everything its POM needs, verify the root checksum, and land all jars in plugin storage where the
 * classloader will find them on the next start.
 */
class PluginInstallerTest {

  @TempDir Path tempDir;

  private PluginInstaller installer;
  private PluginStorageManager storageManager;
  private Path mockLocalRepo;
  private String originalUserHome;
  private String originalPluginDir;

  private static final String REGISTRY_JSON =
      """
      {
        "plugins": {
          "anthropic-test": {
            "groupId": "io.test",
            "artifactId": "plugin-thin",
            "latestVersion": "1.0.0",
            "repository": "maven-central",
            "depends": [],
            "recommends": [],
            "provides": ["llm-backend"]
          }
        }
      }
      """;

  @BeforeEach
  void setUp() throws IOException {
    originalUserHome = System.getProperty("user.home");
    originalPluginDir = System.getProperty("jfr.shell.plugin.dir");
    System.setProperty("user.home", tempDir.toString());
    System.setProperty("jfr.shell.plugin.dir", tempDir.resolve("plugins").toString());

    mockLocalRepo = tempDir.resolve(".m2/repository");
    storageManager = new PluginStorageManager();
    MavenResolver resolver = new MavenResolver();
    // Unreachable remote URL: fixtures resolve from the local repository, and if one were
    // missing the install must fail fast rather than wait on a real registry fetch.
    PluginRegistry registry =
        new PluginRegistry(
            storageManager,
            mockLocalRepo,
            REGISTRY_JSON,
            "http://127.0.0.1:1/jfr-shell-plugins.json");
    installer = new PluginInstaller(registry, resolver, storageManager);

    // Fixture from the MavenResolverTest pattern: a thin jar whose POM declares a compile and a
    // runtime dependency, all available in the local repository.
    writePom(
        "plugin-thin",
        "1.0.0",
        "io.test",
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
          <dependencies>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-a</artifactId>
              <version>1.0.0</version>
            </dependency>
            <dependency>
              <groupId>io.test</groupId>
              <artifactId>lib-b</artifactId>
              <version>1.0.0</version>
              <scope>runtime</scope>
            </dependency>
          </dependencies>
        </project>
        """);
    writePom("lib-a", "1.0.0", "io.test", "lib-a", "1.0.0", "");
    writePom("lib-b", "1.0.0", "io.test", "lib-b", "1.0.0", "");
    writeJar("plugin-thin", "1.0.0");
    writeJar("lib-a", "1.0.0");
    writeJar("lib-b", "1.0.0");
  }

  @AfterEach
  void tearDown() {
    if (originalUserHome != null) {
      System.setProperty("user.home", originalUserHome);
    } else {
      System.clearProperty("user.home");
    }
    if (originalPluginDir != null) {
      System.setProperty("jfr.shell.plugin.dir", originalPluginDir);
    } else {
      System.clearProperty("jfr.shell.plugin.dir");
    }
  }

  @Test
  void installDepositsRootAndDependencyJarsIntoPluginStorage()
      throws IOException, PluginInstallException {
    List<String> installed = installer.installWithDependencies("anthropic-test", null, null);

    assertEquals(List.of("anthropic-test"), installed, "nothing else was resolvable");

    PluginMetadata metadata = storageManager.loadInstalled().get("anthropic-test");
    assertNotNull(metadata, "installed.json records the plugin");
    assertEquals("plugin-thin", metadata.artifactId());

    assertTrue(
        Files.exists(storageManager.getPluginJarPath(metadata)), "root jar in the version dir");

    Path depsRoot = storageManager.getPluginVersionDir(metadata).resolve("deps");
    try (java.util.stream.Stream<Path> depJars = Files.walk(depsRoot)) {
      List<String> names =
          depJars
              .filter(p -> p.toString().endsWith(".jar"))
              .map(p -> p.getFileName().toString())
              .sorted()
              .toList();
      assertEquals(List.of("lib-a-1.0.0.jar", "lib-b-1.0.0.jar"), names);
    }

    List<Path> classloadable = storageManager.getAllInstalledJars();
    assertTrue(
        classloadable.stream().anyMatch(p -> p.getFileName().toString().equals("lib-a-1.0.0.jar")),
        "the classloader scan sees dependency jars");
  }

  private void writePom(
      String dir,
      String dirVersion,
      String groupId,
      String artifactId,
      String version,
      String deps) {
    String pomText =
        deps.isBlank()
            ? """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <packaging>jar</packaging>
            </project>
            """
                .formatted(groupId, artifactId, version)
            : deps;
    Path versionDir =
        mockLocalRepo.resolve(groupId.replace('.', '/')).resolve(dir).resolve(dirVersion);
    try {
      Files.createDirectories(versionDir);
      Files.writeString(versionDir.resolve(dir + "-" + dirVersion + ".pom"), pomText);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void writeJar(String artifactId, String version) throws IOException {
    Path versionDir = mockLocalRepo.resolve("io/test").resolve(artifactId).resolve(version);
    Files.createDirectories(versionDir);
    Files.writeString(versionDir.resolve(artifactId + "-" + version + ".jar"), "mock jar");
  }
}
