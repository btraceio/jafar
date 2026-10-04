package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LlmBackend#discover()} must find backends the plugin system installed — that is the seam
 * letting the release artifacts drop their bundled provider SDKs while users can still install a
 * backend at run time through the plugin catalog.
 *
 * <p>The fixture here goes through what the shell runs with in the "plugin system not initialized"
 * path: a classloader whose resources include only the fixture jar's ServiceLoader file, while the
 * provider class resolves through its parent (the test classpath). Running the OLD discovery —
 * which always used the caller's classloader — against this fixture finds nothing, so the test
 * fails without the discovery-classloader change.
 */
class LlmBackendPluginDiscoveryTest {

  @TempDir Path tempDir;

  @Test
  void discoverFindsBackendsThePluginClassLoaderCarries() throws IOException {
    Path jar = tempDir.resolve("llm-test-1.0.0.jar");
    writeServiceJar(jar, LlmBackend.class.getName(), TestPluginLlmBackend.class.getName());

    URLClassLoader fixtureLoader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, LlmBackend.class.getClassLoader());
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(fixtureLoader);
    try {
      List<LlmBackend> backends = LlmBackend.discover();
      assertTrue(
          backends.stream().anyMatch(b -> TestPluginLlmBackend.ID.equals(b.id())),
          "a backend whose service file lives in the plugin loader is discovered: "
              + backends.stream().map(LlmBackend::id).toList());

      long listed = backends.stream().filter(b -> TestPluginLlmBackend.ID.equals(b.id())).count();
      assertEquals(1, listed, "the provider is not listed twice");
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
      //noinspection unused
      try {
        fixtureLoader.close();
      } catch (IOException e) {
        // closing an already-closed loader is fine in a test teardown
      }
    }
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
