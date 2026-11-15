package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module jar can be broken — its provider class can be missing, fail in its static initializer,
 * or answer {@code getId()}/{@code getPriority()}/{@code initialize()} with a throwable. Discovery
 * must skip the broken ones, keep the healthy ones, and say what it skipped.
 */
class ShellModuleLoaderTest {

  @TempDir Path tempDir;

  // ── fixture modules ────────────────────────────────────────────────────────

  abstract static class BaseModule implements ShellModule {
    @Override
    public String getId() {
      return "base";
    }

    @Override
    public String getDisplayName() {
      return "Base";
    }

    @Override
    public java.util.Set<String> getSupportedExtensions() {
      return java.util.Set.of("zzz");
    }

    @Override
    public Session createSession(Path path, Object context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QueryEvaluator getQueryEvaluator() {
      return null;
    }

    @Override
    public org.jline.reader.Completer getCompleter(SessionManager<?> sessions, Object context) {
      return null;
    }

    @Override
    public List<String> getExamples() {
      return List.of();
    }

    @Override
    public int getPriority() {
      return 10;
    }

    @Override
    public void initialize() {}

    @Override
    public void shutdown() {}
  }

  public static final class GoodModule extends BaseModule {
    @Override
    public String getId() {
      return "good";
    }
  }

  public static final class AnotherGoodModule extends BaseModule {
    @Override
    public String getId() {
      return "another";
    }

    @Override
    public int getPriority() {
      return 100;
    }
  }

  public static final class BlankIdModule extends BaseModule {
    @Override
    public String getId() {
      return " ";
    }
  }

  public static final class ThrowingIdModule extends BaseModule {
    @Override
    public String getId() {
      throw new IllegalStateException("id exploded");
    }
  }

  public static final class ThrowingPriorityModule extends BaseModule {
    @Override
    public String getId() {
      return "throwing-priority";
    }

    @Override
    public int getPriority() {
      throw new IllegalStateException("priority exploded");
    }
  }

  public static final class ThrowingInitModule extends BaseModule {
    @Override
    public String getId() {
      return "throwing-init";
    }

    @Override
    public void initialize() {
      throw new IllegalStateException("init exploded");
    }
  }

  /**
   * Simulates a half-loaded jar: initialize() fails with a linkage error, not a plain exception.
   */
  public static final class LinkageErrorInitModule extends BaseModule {
    @Override
    public String getId() {
      return "linkage-init";
    }

    @Override
    public void initialize() {
      throw new NoClassDefFoundError("com/example/does/not/Exist");
    }
  }

  public static final class BrokenDisplayNameModule extends BaseModule {
    @Override
    public String getId() {
      return "broken-display-name";
    }

    @Override
    public String getDisplayName() {
      throw new IllegalStateException("display name exploded");
    }
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void unloadableProviderDoesNotBlockTheHealthyOnes() {
    try (URLClassLoader loader =
        loaderWithModules("com.example.does.not.Exist", GoodModule.class.getName())) {
      List<ShellModule> modules = assertDoesNotThrow(() -> ShellModuleLoader.loadAll(loader));
      assertEquals(List.of("good"), ids(modules));
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void blankIdIsSkippedAndReported() {
    try (URLClassLoader loader = loaderWithModules(BlankIdModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertTrue(modules.isEmpty());
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void throwingGetIdIsSkippedAndReported() {
    try (URLClassLoader loader = loaderWithModules(ThrowingIdModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertTrue(modules.isEmpty());
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void throwingGetPriorityIsSkippedAndReported() {
    try (URLClassLoader loader = loaderWithModules(ThrowingPriorityModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertTrue(modules.isEmpty());
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void throwingInitializeIsSkippedAndReported() {
    try (URLClassLoader loader =
        loaderWithModules(ThrowingInitModule.class.getName(), GoodModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertEquals(List.of("good"), ids(modules));
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void linkageErrorInInitializeIsSkippedAndReported() {
    try (URLClassLoader loader =
        loaderWithModules(LinkageErrorInitModule.class.getName(), GoodModule.class.getName())) {
      List<ShellModule> modules = assertDoesNotThrow(() -> ShellModuleLoader.loadAll(loader));
      assertEquals(List.of("good"), ids(modules));
      assertEquals(1, ShellModuleLoader.lastDiscoveryFailures().size());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void healthyModulesAreSortedByPriorityDescending() {
    try (URLClassLoader loader =
        loaderWithModules(GoodModule.class.getName(), AnotherGoodModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertEquals(List.of("another", "good"), ids(modules));
      assertTrue(ShellModuleLoader.lastDiscoveryFailures().isEmpty());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void throwingDisplayNameDoesNotBlockTheModule() {
    try (URLClassLoader loader = loaderWithModules(BrokenDisplayNameModule.class.getName())) {
      List<ShellModule> modules = ShellModuleLoader.loadAll(loader);
      assertEquals(List.of("broken-display-name"), ids(modules));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> ids(List<ShellModule> modules) {
    return modules.stream().map(ShellModule::getId).collect(Collectors.toList());
  }

  private URLClassLoader loaderWithModules(String... providers) throws IOException {
    Path jar = Files.createTempFile(tempDir, "modules", ".jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("META-INF/services/io.jafar.shell.core.ShellModule"));
      out.write((String.join("\n", providers) + "\n").getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
  }
}
