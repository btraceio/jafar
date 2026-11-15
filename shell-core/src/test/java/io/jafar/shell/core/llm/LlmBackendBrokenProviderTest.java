package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.discovery.ProviderDiscovery;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A broken installed provider jar must not take discovery and everything downstream of it down with
 * it: one bad provider is skipped and reported, healthy providers on the same classpath are still
 * found, and the commands that need a backend keep working.
 *
 * <p>The fixtures are jars whose service file names test-classpath-resident provider classes, or
 * classes that do not exist at all — the same shapes a broken installed plugin produces.
 */
class LlmBackendBrokenProviderTest {

  @TempDir Path tempDir;

  @Test
  void brokenProviderIsSkippedAndHealthyOneIsFound() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "one-broken",
            List.of(
                BrokenPluginLlmBackend.class.getName(), TestPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            ProviderDiscovery.Outcome<LlmBackend> outcome = LlmBackend.discoverWithFailures();
            assertEquals(
                List.of(TestPluginLlmBackend.ID),
                outcome.providers().stream().map(LlmBackend::id).toList(),
                "the healthy provider survives its broken sibling");
            assertEquals(1, outcome.failures().size());
            assertTrue(
                outcome.failures().get(0).provider().contains("BrokenPluginLlmBackend"),
                "failure names the broken provider: " + outcome.failures());
            // The plain path still works and stays consistent with the detailed one.
            assertEquals(
                outcome.providers().stream().map(LlmBackend::id).toList(),
                LlmBackend.discover().stream().map(LlmBackend::id).toList(),
                "discover() returns the usable ones");
          });
    }
  }

  @Test
  void missingProviderClassIsReportedNotFatal() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "missing-class",
            List.of("com.example.DoesNotExist", TestPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            ProviderDiscovery.Outcome<LlmBackend> outcome = LlmBackend.discoverWithFailures();
            assertEquals(
                List.of(TestPluginLlmBackend.ID),
                outcome.providers().stream().map(LlmBackend::id).toList());
            assertEquals(1, outcome.failures().size());
          });
    }
  }

  @Test
  void providerWhoseIdThrowsIsSkippedAndReported() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "id-throws",
            List.of(
                BrokenIdPluginLlmBackend.class.getName(), TestPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            ProviderDiscovery.Outcome<LlmBackend> outcome = LlmBackend.discoverWithFailures();
            assertEquals(
                List.of(TestPluginLlmBackend.ID),
                outcome.providers().stream().map(LlmBackend::id).toList());
            assertTrue(
                outcome.failures().stream()
                    .anyMatch(f -> f.provider().contains("BrokenIdPluginLlmBackend")),
                "the id() failure is recorded: " + outcome.failures());
          });
    }
  }

  @Test
  void autoSelectionSkipsTheReadinessThrowingProvider() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "readiness-throws",
            List.of(
                ThrowingReadinessPluginLlmBackend.class.getName(),
                ReadyPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            LlmConfig config = new LlmConfig(Map.<String, String>of()::get);
            var selected = LlmBackend.select("auto", config);
            assertTrue(selected.isPresent());
            assertEquals("ready-plugin-backend", selected.get().id());
            // The broken one reports not-ready with the cause, rather than blowing up llm status.
            LlmBackend throwing =
                LlmBackend.discover().stream()
                    .filter(b -> b.id().equals("throwing-readiness"))
                    .findFirst()
                    .orElseThrow();
            LlmBackend.Readiness readiness = LlmBackend.probeReadiness(throwing, config);
            assertFalse(readiness.ready());
            assertTrue(readiness.detail().contains("readiness check failed"), readiness.detail());
          });
    }
  }

  @Test
  void serviceCreationSucceedsWithAHealthyProviderAlongsideABrokenOne() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "create-with-broken-sibling",
            List.of(
                BrokenPluginLlmBackend.class.getName(), TestPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            LlmConfig config =
                new LlmConfig(Map.<String, String>of("llm.backend", TestPluginLlmBackend.ID)::get);
            var result = LlmService.create(config);
            assertTrue(result.isPresent(), "expected a service, got: " + result);
          });
    }
  }

  @Test
  void explicitSelectionNamesTheFailuresWhenNothingLoads() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "one-healthy-one-broken",
            List.of(
                BrokenPluginLlmBackend.class.getName(), ReadyPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            LlmConfig config =
                new LlmConfig(Map.<String, String>of("llm.backend", "anthropic")::get);
            var result = LlmService.create(config);
            assertFalse(result.isPresent());
            String message = result.detail() + " " + result.remedy();
            assertTrue(message.contains("failed to load"), message);
            assertTrue(message.contains("BrokenPluginLlmBackend"), message);
          });
    }
  }

  @Test
  void autoWithEveryProviderBrokenSaysSo() throws Exception {
    try (URLClassLoader loader =
        serviceJar("auto-all-broken", List.of(BrokenPluginLlmBackend.class.getName()))) {
      runWithLoader(
          loader,
          () -> {
            LlmConfig config = new LlmConfig(Map.<String, String>of("llm.backend", "auto")::get);
            var result = LlmService.create(config);
            assertFalse(result.isPresent());
            String message = result.detail() + " " + result.remedy();
            assertTrue(message.contains("could be loaded"), message);
            assertTrue(message.contains("BrokenPluginLlmBackend"), message);
          });
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void runWithLoader(URLClassLoader loader, ThrowingRunnable body) throws Exception {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    try {
      body.run();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private URLClassLoader serviceJar(String name, List<String> providerClasses) throws IOException {
    Path jar = tempDir.resolve(name + ".jar");
    String services = String.join("\n", providerClasses) + "\n";
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new FileOutputStream(jar.toFile()))) {
      out.putNextEntry(
          new java.util.zip.ZipEntry("META-INF/services/" + LlmBackend.class.getName()));
      out.write(services.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return new URLClassLoader(new URL[] {jar.toUri().toURL()}, LlmBackend.class.getClassLoader());
  }
}
