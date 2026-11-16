package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The original bug report, as a regression test: discovery used to let the {@code
 * ServiceConfigurationError} from a broken provider escape through {@link LlmBackend#discover()}
 * and out through every caller. Discovery must never throw — it skips the broken provider and
 * returns the healthy ones.
 */
class LlmBackendDiscoverNeverThrowsTest {

  @TempDir Path tempDir;

  @Test
  void discoverDoesNotThrowWithABrokenProviderOnTheClasspath() throws Exception {
    Path jar = tempDir.resolve("broken.jar");
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new FileOutputStream(jar.toFile()))) {
      out.putNextEntry(
          new java.util.zip.ZipEntry("META-INF/services/" + LlmBackend.class.getName()));
      out.write(
          (BrokenPluginLlmBackend.class.getName()
                  + "\n"
                  + TestPluginLlmBackend.class.getName()
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, LlmBackend.class.getClassLoader())) {
      Thread.currentThread().setContextClassLoader(loader);
      try {
        List<LlmBackend> found =
            Assertions.assertDoesNotThrow(
                LlmBackend::discover, "a broken provider jar must not kill discovery");
        assertTrue(
            found.stream().anyMatch(b -> TestPluginLlmBackend.ID.equals(b.id())),
            "healthy provider still discovered: " + found.stream().map(LlmBackend::id).toList());
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
    }
  }
}
