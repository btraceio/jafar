package io.jafar.shell.backend.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #267: the jafar backend read its version from {@code Class.getPackage()}, which in the shell's
 * one plugin class loader is the manifest of whichever jar defined the package first: a jafar
 * backend next to a built jdk jar reported the jdk jar's version and hid the stale-backend warning.
 * It now reads the manifest of its own jar.
 */
class JafarBackendVersionTest {

  @TempDir Path dir;

  @Test
  void theVersionIsTheOneInTheBackendsJar() throws Exception {
    Path jar = jarOfBackend("9.8.7-SNAPSHOT");
    try (ChildFirst loader = new ChildFirst(jar)) {
      Class<?> loaded = loader.loadClass(JafarBackend.class.getName());
      assertEquals(jar.toUri().toURL(), loaded.getProtectionDomain().getCodeSource().getLocation());

      assertEquals("9.8.7-SNAPSHOT", JafarBackend.buildVersion(loaded));
      assertEquals(
          "9.8.7-SNAPSHOT",
          ((io.jafar.shell.backend.JfrBackend) loaded.getConstructor().newInstance()).getVersion());
    }
  }

  @Test
  void aBlankVersionIsADevBuild() throws Exception {
    try (ChildFirst loader = new ChildFirst(jarOfBackend(" "))) {
      assertEquals(
          "dev", JafarBackend.buildVersion(loader.loadClass(JafarBackend.class.getName())));
    }
  }

  @Test
  void classesOutsideAJarAreADevBuild() {
    // Gradle runs this module's tests from its classes directory
    assertEquals("dev", new JafarBackend().getVersion());
  }

  @Test
  void aJarWithoutAVersionIsADevBuild() throws Exception {
    try (ChildFirst loader = new ChildFirst(jarOfBackend(null))) {
      assertEquals(
          "dev", JafarBackend.buildVersion(loader.loadClass(JafarBackend.class.getName())));
    }
  }

  @Test
  void anotherJarWithThisPackageDoesNotLendItsVersion() throws Exception {
    // The jdk backend's jar has classes in this package too. In one class loader the package is
    // defined from whichever jar is read first; this test's own class stands in for the other jar.
    Path other = jarOf(JafarBackendVersionTest.class, "1.0-other");
    Path backend = jarOfBackend("9.8.7-SNAPSHOT");
    try (ChildFirst loader = new ChildFirst(other, backend)) {
      Class<?> first = loader.loadClass(JafarBackendVersionTest.class.getName());
      assertEquals("1.0-other", first.getPackage().getImplementationVersion());

      Class<?> loaded = loader.loadClass(JafarBackend.class.getName());
      assertEquals("9.8.7-SNAPSHOT", JafarBackend.buildVersion(loaded));
    }
  }

  /** Loads this package's classes from {@code jars}, everything else from the test's class path. */
  private static final class ChildFirst extends URLClassLoader {
    ChildFirst(Path... jars) throws Exception {
      super(urls(jars), ChildFirst.class.getClassLoader());
    }

    private static URL[] urls(Path... jars) throws Exception {
      URL[] urls = new URL[jars.length];
      for (int i = 0; i < jars.length; i++) {
        urls[i] = jars[i].toUri().toURL();
      }
      return urls;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(JafarBackend.class.getName())
          && !name.equals(JafarBackendVersionTest.class.getName())) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        return c != null ? c : findClass(name);
      }
    }
  }

  /** A jar holding JafarBackend's class file, with {@code version} in its manifest. */
  private Path jarOfBackend(String version) throws Exception {
    return jarOf(JafarBackend.class, version);
  }

  /** A jar holding {@code type}'s class file, with {@code version} in its manifest. */
  private Path jarOf(Class<?> type, String version) throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (version != null) {
      manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    }
    String entry = type.getName().replace('.', '/') + ".class";
    Path jar =
        dir.resolve(type.getSimpleName() + "-" + (version == null ? "none" : version) + ".jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        var in = type.getClassLoader().getResourceAsStream(entry)) {
      out.putNextEntry(new JarEntry(entry));
      in.transferTo(out);
      out.closeEntry();
    }
    return jar;
  }
}
