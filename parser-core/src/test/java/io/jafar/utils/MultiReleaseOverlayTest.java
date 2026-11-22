package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every class the multi-release jar replaces under {@code META-INF/versions/<n>} keeps the public
 * and protected members of the class it replaces.
 *
 * <p>The tests run against the plain classes, so they never see a version overlay: on a JDK that
 * selects one, a member the overlay lacks fails only at run time, with {@code NoSuchMethodError}.
 * {@code CustomByteBuffer.wrap(byte[])} went missing from the Java 13 and 21 overlays that way, and
 * {@code StreamingChunkParser.parse(InputStream)} failed on every JDK from 13.
 *
 * <p>Each overlay class is loaded from its own output directory and compared, by reflection, with
 * the main class of the same name loaded from the main output directory. Both directories are
 * passed in by the build ({@code jafar.classes.main} and {@code jafar.classes.overlays}).
 */
class MultiReleaseOverlayTest {

  /** Loads the classes of one directory itself, and only the rest from its parent. */
  private static final class DirectoryFirst extends URLClassLoader {
    DirectoryFirst(Path dir) throws IOException {
      super(new URL[] {dir.toUri().toURL()}, MultiReleaseOverlayTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
          try {
            c = findClass(name);
          } catch (ClassNotFoundException e) {
            c = super.loadClass(name, false);
          }
        }
        return c;
      }
    }
  }

  private static Path dir(String property) {
    String value = System.getProperty(property);
    assertTrue(value != null && !value.isEmpty(), property + " is not set by the build");
    return Paths.get(value);
  }

  private static List<String> classNames(Path dir) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(p -> p.toString().endsWith(".class"))
          .map(p -> dir.relativize(p).toString())
          .map(s -> s.substring(0, s.length() - ".class".length()).replace('/', '.'))
          .filter(s -> !s.equals("module-info"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private static boolean visible(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static String params(Class<?>[] types) {
    return Arrays.stream(types).map(Class::getName).collect(Collectors.joining(",", "(", ")"));
  }

  /** The public and protected members {@code c} declares, each as a comparable signature. */
  private static Set<String> members(Class<?> c) {
    Set<String> out = new TreeSet<>();
    for (Method m : c.getDeclaredMethods()) {
      if (visible(m.getModifiers()) && !m.isSynthetic()) {
        out.add(
            (Modifier.isStatic(m.getModifiers()) ? "static " : "")
                + m.getReturnType().getName()
                + " "
                + m.getName()
                + params(m.getParameterTypes()));
      }
    }
    for (Constructor<?> k : c.getDeclaredConstructors()) {
      if (visible(k.getModifiers()) && !k.isSynthetic()) {
        out.add("<init>" + params(k.getParameterTypes()));
      }
    }
    for (Field f : c.getDeclaredFields()) {
      if (visible(f.getModifiers()) && !f.isSynthetic()) {
        out.add(
            (Modifier.isStatic(f.getModifiers()) ? "static " : "")
                + f.getType().getName()
                + " "
                + f.getName());
      }
    }
    return out;
  }

  @Test
  void overlaysKeepEveryPublicAndProtectedMember() throws Exception {
    Path main = dir("jafar.classes.main");
    String[] overlays = System.getProperty("jafar.classes.overlays", "").split(",");
    assertTrue(overlays.length > 0 && !overlays[0].isEmpty(), "no overlay directory given");
    List<String> missing = new ArrayList<>();
    int compared = 0;
    try (DirectoryFirst mainLoader = new DirectoryFirst(main)) {
      for (String overlay : overlays) {
        Path dir = Paths.get(overlay);
        List<String> names = classNames(dir);
        assertFalse(names.isEmpty(), dir + " holds no class");
        try (DirectoryFirst overlayLoader = new DirectoryFirst(dir)) {
          for (String name : names) {
            if (!Files.exists(main.resolve(name.replace('.', '/') + ".class"))) {
              continue; // only in the overlay: replaces nothing
            }
            Set<String> expected = members(Class.forName(name, false, mainLoader));
            Set<String> actual = members(Class.forName(name, false, overlayLoader));
            for (String member : expected) {
              if (!actual.contains(member)) {
                missing.add(dir.getFileName() + ": " + name + " lacks " + member);
              }
            }
            compared++;
          }
          // A public nested class of an overlaid class must be overlaid with it.
          for (String name : classNames(main)) {
            int nested = name.indexOf('$');
            if (nested < 0
                || !names.contains(name.substring(0, nested))
                || names.contains(name)
                || !visible(Class.forName(name, false, mainLoader).getModifiers())) {
              continue;
            }
            missing.add(dir.getFileName() + ": lacks the nested class " + name);
          }
        }
      }
    }
    assertTrue(compared > 0, "no overlay class replaces a main class");
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }
}
