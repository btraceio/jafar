package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.TestPluginLlmBackend;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Binding the real {@link PluginManager} (the singleton, as the shell runs it) so the LLM SPI can
 * be installed from the plugin catalog: a local install must accept an LlmBackend provider jar, and
 * discovery must then find it. Runs via the {@code :shell-core:pluginSystemTest} task — its own JVM
 * — because the singleton binds plugin storage and the Maven repository at class-load time; the
 * task provides fixture directories as task-level system properties.
 */
class PluginManagerLlmInstallTest {

  @TempDir static Path tempDir;

  private static String originalUserHome;

  /**
   * Writes the local Maven repository fixture BEFORE the PluginManager singleton is constructed
   * (the first getInstance() call constructs the registry, which scans user.home for plugins).
   */
  @BeforeAll
  static void buildFixtureRepository() throws IOException {
    originalUserHome = System.getProperty("user.home");
    Path fixtureRepo = tempDir.resolve(".m2/repository");
    System.setProperty("user.home", tempDir.toString());

    Path artifactDir = fixtureRepo.resolve("io/btrace/llm-anthropic/0.29.0-SNAPSHOT");
    Files.createDirectories(artifactDir);
    Files.writeString(artifactDir.resolve("llm-anthropic-0.29.0-SNAPSHOT.pom"), pluginPom());
  }

  @AfterAll
  static void restoreUserHome() {
    if (originalUserHome != null) {
      System.setProperty("user.home", originalUserHome);
    }
  }

  @Test
  void localInstallAcceptsLlmProviderAndDiscoveryFindsItThroughThePluginLoader() throws Exception {
    PluginManager manager = PluginManager.getInstance();
    manager.initialize();

    // --install-plugin style install: the provider jar is an LlmBackend one, not JfrBackend —
    // the installer must accept both SPIs.
    Path fixtureJar = tempDir.resolve("llm-anthropic-0.29.0-SNAPSHOT.jar");
    writeServiceJar(fixtureJar, LlmBackend.class.getName(), TestPluginLlmBackend.class.getName());
    manager.installLocalPlugin(fixtureJar);

    // The install recorded the provider jar, and the plugin-aware classloader — the branch the
    // release artifacts rely on once an LLM backend ships as a plugin instead of being bundled —
    // discovers it after the (no-restart) reload. The OLD discovery (fixed to the caller's
    // classloader) cannot see it.
    PluginStorageManager storage = new PluginStorageManager();
    assertTrue(
        storage.loadInstalled().containsKey("llm-anthropic"),
        "the provider jar recorded as installed");

    PluginManager.reinitialize();
    List<LlmBackend> backends = LlmBackend.discover();
    assertTrue(
        backends.stream().anyMatch(b -> TestPluginLlmBackend.ID.equals(b.id())),
        "an installed LlmBackend provider is discovered: "
            + backends.stream().map(LlmBackend::id).toList());
  }

  private static String pluginPom() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.btrace</groupId>
          <artifactId>llm-anthropic</artifactId>
          <version>0.29.0-SNAPSHOT</version>
          <packaging>jar</packaging>
        </project>
        """;
  }

  /** A jar with one ServiceLoader entry and one marker resource. */
  private static void writeServiceJar(Path target, String serviceInterface, String providerClass)
      throws IOException {
    Files.createDirectories(target.getParent());
    try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(target.toFile()))) {
      jar.putNextEntry(new ZipEntry("META-INF/services/" + serviceInterface));
      jar.write((providerClass + "\n").getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
      jar.putNextEntry(new ZipEntry("plugin-fixture-marker.txt"));
      jar.write(new byte[] {0});
      jar.closeEntry();
    }
  }
}
