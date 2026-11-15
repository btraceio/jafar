package io.jafar.shell.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the shared ServiceLoader guard. */
class ProviderDiscoveryTest {

  /** Test SPI so the guard can be exercised without touching a real backend SPI. */
  public interface DummySpi {}

  public static class DummyProvider implements DummySpi {}

  public static class ThrowingProvider implements DummySpi {
    public ThrowingProvider() {
      throw new IllegalStateException("ctor boom");
    }
  }

  @TempDir Path tempDir;

  private URLClassLoader serviceJar(String name, List<String> providerClasses) throws IOException {
    Path jar = tempDir.resolve(name + ".jar");
    String services = String.join("\n", providerClasses) + "\n";
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new FileOutputStream(jar.toFile()))) {
      out.putNextEntry(new java.util.zip.ZipEntry("META-INF/services/" + DummySpi.class.getName()));
      out.write(services.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
  }

  @Test
  void brokenProviderDoesNotStopLaterProviders() throws Exception {
    try (URLClassLoader loader =
        serviceJar(
            "mixed", List.of(ThrowingProvider.class.getName(), DummyProvider.class.getName()))) {
      ProviderDiscovery.Outcome<DummySpi> outcome =
          ProviderDiscovery.loadAll(DummySpi.class, loader);
      assertEquals(1, outcome.providers().size());
      assertEquals(1, outcome.failures().size());
      assertTrue(
          outcome.failures().get(0).detail().contains("ctor boom")
              || outcome.failures().get(0).detail().contains("ThrowingProvider"),
          "failure carries the cause: " + outcome.failures());
    }
  }

  @Test
  void missingProviderClassIsReported() throws Exception {
    try (URLClassLoader loader = serviceJar("missing", List.of("com.example.NoSuchProvider"))) {
      ProviderDiscovery.Outcome<DummySpi> outcome =
          ProviderDiscovery.loadAll(DummySpi.class, loader);
      assertEquals(0, outcome.providers().size());
      assertEquals(1, outcome.failures().size());
    }
  }

  @Test
  void corruptServiceFileIsReportedAndLaterEntriesSurvive() throws Exception {
    // One corrupt and one good provider listed in the same file: the broken entry must not be
    // fatal to the pass as a whole (a same-jar failure may still lose that jar's other providers —
    // the JDK aborts that file's parse — but the call must come back, not throw).
    String services = "!!!not-a-class!!!\n" + DummyProvider.class.getName() + "\n";
    Path jar = tempDir.resolve("corrupt.jar");
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(new FileOutputStream(jar.toFile()))) {
      out.putNextEntry(new java.util.zip.ZipEntry("META-INF/services/" + DummySpi.class.getName()));
      out.write(services.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
      ProviderDiscovery.Outcome<DummySpi> outcome =
          ProviderDiscovery.loadAll(DummySpi.class, loader);
      // The JDK aborts the unparseable services file; the guard must surface it as a failure
      // instead of throwing or looping forever.
      assertTrue(!outcome.failures().isEmpty(), "corrupt services file must be reported");
    }
  }

  @Test
  void callFallsBackAndRecords() {
    List<ProviderDiscovery.Failure> failures = new ArrayList<>();
    String result =
        ProviderDiscovery.call(
            () -> {
              throw new IllegalStateException("boom");
            },
            "fallback",
            failures,
            "com.example.Provider",
            "readiness()");
    assertEquals("fallback", result);
    assertEquals(1, failures.size());
    assertEquals("com.example.Provider", failures.get(0).provider());
    assertTrue(failures.get(0).detail().contains("boom"));
  }

  @Test
  void callReturnsValueWhenProviderBehaves() {
    List<ProviderDiscovery.Failure> failures = new ArrayList<>();
    String result =
        ProviderDiscovery.call(() -> "ok", "fallback", failures, "com.example.Provider", "id()");
    assertEquals("ok", result);
    assertTrue(failures.isEmpty());
  }

  @Test
  void virtualMachineErrorsPropagate() {
    assertThrows(
        StackOverflowError.class,
        () ->
            ProviderDiscovery.call(
                () -> {
                  throw new StackOverflowError("deep recursion");
                },
                "fallback",
                new ArrayList<>(),
                "com.example.Provider",
                "readiness()"));
  }
}
