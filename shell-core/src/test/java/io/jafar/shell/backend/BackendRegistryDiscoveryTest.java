package io.jafar.shell.backend;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A broken installed provider jar must not take JFR backend discovery down: the healthy provider is
 * kept and the broken one is reported, rather than killing every later caller of the registry.
 */
class BackendRegistryDiscoveryTest {

  @TempDir Path tempDir;

  @Test
  void brokenProviderIsSkippedAndHealthyOneIsKept() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "broken-and-healthy",
            List.of(BrokenJfrBackend.class.getName(), ReadyJfrBackend.class.getName()))) {
      BackendRegistry.LoadedBackends loaded = BackendRegistry.loadBackends(loader);
      List<String> ids = loaded.providers().stream().map(JfrBackend::getId).toList();
      assertTrue(ids.contains("ready-jfr-backend"), "healthy provider kept: " + ids);
      assertTrue(!ids.contains("broken-jfr-backend"), "broken provider skipped: " + ids);
      assertEquals(1, loaded.failures().size());
      assertTrue(
          loaded.failures().get(0).provider().contains("BrokenJfrBackend"),
          "failure names the broken provider: " + loaded.failures());
    }
  }

  @Test
  void missingProviderClassIsReportedNotFatal() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "missing-class",
            List.of("com.example.DoesNotExist", ReadyJfrBackend.class.getName()))) {
      BackendRegistry.LoadedBackends loaded = BackendRegistry.loadBackends(loader);
      List<String> ids = loaded.providers().stream().map(JfrBackend::getId).toList();
      assertTrue(ids.contains("ready-jfr-backend"), "healthy provider kept: " + ids);
      assertTrue(!ids.contains("broken-jfr-backend"), "broken provider skipped: " + ids);
      assertEquals(1, loaded.failures().size());
    }
  }

  @Test
  void providerWithBlankIdIsSkippedAndReported() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "blank-id",
            List.of(BlankIdJfrBackend.class.getName(), ReadyJfrBackend.class.getName()))) {
      BackendRegistry.LoadedBackends loaded = BackendRegistry.loadBackends(loader);
      List<String> ids = loaded.providers().stream().map(JfrBackend::getId).toList();
      assertTrue(ids.contains("ready-jfr-backend"), "healthy provider kept: " + ids);
      assertTrue(
          loaded.providers().stream().noneMatch(b -> b instanceof BlankIdJfrBackend),
          "the blank-id provider is skipped: " + ids);
      assertTrue(
          loaded.failures().stream().anyMatch(f -> f.detail().contains("blank id")),
          "the blank-id failure is recorded: " + loaded.failures());
    }
  }

  private URLClassLoader serviceJar(String name, List<String> providerClasses) throws IOException {
    Files.createDirectories(tempDir);
    Path jar = tempDir.resolve(name + ".jar");
    String services = String.join("\n", providerClasses) + "\n";
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new FileOutputStream(jar.toFile()))) {
      out.putNextEntry(
          new java.util.zip.ZipEntry("META-INF/services/" + JfrBackend.class.getName()));
      out.write(services.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
  }
}
