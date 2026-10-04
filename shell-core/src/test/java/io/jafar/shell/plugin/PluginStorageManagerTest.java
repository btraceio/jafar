package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plugin classloader must see every jar the installer put in plugin storage: the root jar
 * beside it and the POM-resolved dependency jars the installer writes under deps/.
 */
class PluginStorageManagerTest {

  @TempDir Path tempDir;

  private PluginStorageManager storageManager;
  private String originalPluginDir;

  @BeforeEach
  void setUp() {
    originalPluginDir = System.getProperty("jfr.shell.plugin.dir");
    System.setProperty("jfr.shell.plugin.dir", tempDir.resolve("plugins").toString());
    storageManager = new PluginStorageManager();
  }

  @AfterEach
  void tearDown() {
    if (originalPluginDir != null) {
      System.setProperty("jfr.shell.plugin.dir", originalPluginDir);
    } else {
      System.clearProperty("jfr.shell.plugin.dir");
    }
  }

  @Test
  void listsDependencyJarsBesideThePluginJar() throws IOException {
    PluginMetadata metadata =
        PluginMetadata.installed("io.test", "test-plugin", "1.0.0", "maven-central", Instant.now());

    storageManager.saveInstalled(Map.of("test-plugin", metadata));

    Path versionDir = storageManager.getPluginVersionDir(metadata);
    Files.createDirectories(versionDir.resolve("deps/io/test/some-lib/1.0.0"));
    Files.createDirectories(versionDir.resolve("deps/io/test/other-lib/2.0.0"));
    Files.writeString(storageManager.getPluginJarPath(metadata), "root jar");
    Files.writeString(
        versionDir.resolve("deps/io/test/some-lib/1.0.0/some-lib-1.0.0.jar"), "dep jar");
    Files.writeString(
        versionDir.resolve("deps/io/test/other-lib/2.0.0/other-lib-2.0.0.jar"), "dep jar");

    List<Path> jars = storageManager.getAllInstalledJars();

    assertTrue(jars.contains(storageManager.getPluginJarPath(metadata)), "root jar listed");
    assertTrue(
        jars.stream().anyMatch(p -> p.getFileName().toString().equals("some-lib-1.0.0.jar")),
        "dependency jars beside the root jar listed");
    assertEquals(3, jars.size(), "root plus two dependency jars, no stray entries");
  }
}
