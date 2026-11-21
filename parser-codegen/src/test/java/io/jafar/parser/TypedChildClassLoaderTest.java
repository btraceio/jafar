package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An interface loaded by a class loader the parser's own loader cannot see: a plugin's, or an
 * application server's per-deployment loader. The runtime class implementing it was defined in
 * {@code CodeGenerator}'s loader, which cannot resolve the interface: {@code NoClassDefFoundError},
 * and the parse failed.
 */
public class TypedChildClassLoaderTest {

  @TempDir Path dir;

  private Path compile() throws Exception {
    Path src = dir.resolve("src/child");
    Files.createDirectories(src);
    Files.write(
        src.resolve("CSample.java"),
        ("package child;\n"
                + "@io.jafar.parser.api.JfrType(\"jdk.ExecutionSample\")\n"
                + "public interface CSample {\n"
                + "  long startTime();\n  CThread sampledThread();\n}\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        src.resolve("CThread.java"),
        ("package child;\n"
                + "@io.jafar.parser.api.JfrType(\"java.lang.Thread\")\n"
                + "public interface CThread {\n  String javaName();\n}\n")
            .getBytes(StandardCharsets.UTF_8));
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    assertNotNull(javac, "no system Java compiler");
    int rc =
        javac.run(
            null,
            null,
            null,
            "-proc:none",
            "-cp",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            src.resolve("CSample.java").toString(),
            src.resolve("CThread.java").toString());
    assertEquals(0, rc, "javac");
    return classes;
  }

  /** Parses with interfaces from {@code child}, returning "startTime javaName" per sample. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<String> parseWith(ClassLoader child, Path jfr) throws Exception {
    Class<?> sample = child.loadClass("child.CSample");
    Class<?> thread = child.loadClass("child.CThread");
    Method startTime = sample.getMethod("startTime");
    Method sampledThread = sample.getMethod("sampledThread");
    Method javaName = thread.getMethod("javaName");
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
      p.handle(
          (Class) sample,
          (JFRHandler)
              (e, ctl) -> {
                try {
                  if (!sample.isInstance(e)) {
                    seen.add("not a child.CSample of the child loader: " + e.getClass());
                    return;
                  }
                  Object t = sampledThread.invoke(e);
                  seen.add(startTime.invoke(e) + " " + (t == null ? null : javaName.invoke(t)));
                } catch (ReflectiveOperationException ex) {
                  throw new AssertionError(ex);
                }
              });
      p.run();
    }
    return seen;
  }

  private static Path recording() throws Exception {
    URL resource = TypedChildClassLoaderTest.class.getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    return Paths.get(resource.toURI());
  }

  private static void assertSamples(List<String> seen) {
    assertTrue(seen.size() > 1, "no samples");
    assertTrue(seen.stream().noneMatch(s -> s.startsWith("not a")), seen.get(0));
    assertTrue(seen.stream().anyMatch(s -> s.contains("worker-")), seen.get(0));
  }

  @Test
  void anInterfaceInAChildClassLoaderIsImplemented() throws Exception {
    try (URLClassLoader child =
        new URLClassLoader(new URL[] {compile().toUri().toURL()}, getClass().getClassLoader())) {
      // the premise: CodeGenerator's loader, which decides, cannot see the interface
      try {
        ClassLoader codeGenerator =
            Class.forName("io.jafar.parser.internal_api.CodeGenerator").getClassLoader();
        Class.forName("child.CSample", false, codeGenerator);
        throw new AssertionError("CodeGenerator's class loader sees child.CSample");
      } catch (ClassNotFoundException expected) {
        // as intended
      }
      assertSamples(parseWith(child, recording()));
    }
  }

  /**
   * Defines its own copy of {@link ChildFirstShared} from the same bytes; delegates everything
   * else.
   */
  private static final class ChildFirst extends ClassLoader {
    ChildFirst(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(ChildFirstShared.class.getName())) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
          return c;
        }
        String resource = name.replace('.', '/') + ".class";
        try (java.io.InputStream in = getParent().getResourceAsStream(resource)) {
          byte[] bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length);
        } catch (java.io.IOException e) {
          throw new ClassNotFoundException(name, e);
        }
      }
    }
  }

  /**
   * A child-first loader (a plugin's or a webapp's) with its own copy of an interface the parser's
   * loader also has: the runtime class must implement the child's copy. The bridge asked its parent
   * first, so it implemented the parser loader's copy, and the handler got objects that are not
   * instances of the interface it registered.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void aChildFirstLoadersOwnCopyIsImplemented() throws Exception {
    Class<?> copy =
        new ChildFirst(getClass().getClassLoader()).loadClass(ChildFirstShared.class.getName());
    assertTrue(copy != ChildFirstShared.class, "the premise: two copies of ChildFirstShared");
    Method startTime = copy.getMethod("startTime");
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          (Class) copy,
          (JFRHandler)
              (e, ctl) -> {
                try {
                  seen.add(
                      copy.isInstance(e)
                          ? String.valueOf(startTime.invoke(e))
                          : "not the child's ChildFirstShared: " + e.getClass());
                } catch (ReflectiveOperationException ex) {
                  throw new AssertionError(ex);
                }
              });
      p.run();
    }
    assertTrue(seen.size() > 1, "no samples");
    assertTrue(seen.stream().noneMatch(v -> v.startsWith("not")), seen.get(0));
  }

  /**
   * The interface's loader is collected once nothing uses it: generated classes for an interface
   * the parser's loader cannot see stay out of the JVM-wide handler cache, which holds its entries
   * (and through them the interface and its loader) for as long as the JVM runs.
   */
  @Test
  void aChildLoaderIsCollectedAfterItsParse() throws Exception {
    WeakReference<ClassLoader> ref = parseInADroppedLoader(compile(), recording());
    for (int i = 0; i < 50 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertTrue(ref.get() == null, "the child class loader is still reachable after its parse");
  }

  private static WeakReference<ClassLoader> parseInADroppedLoader(Path classes, Path jfr)
      throws Exception {
    URLClassLoader child =
        new URLClassLoader(
            new URL[] {classes.toUri().toURL()}, TypedChildClassLoaderTest.class.getClassLoader());
    assertSamples(parseWith(child, jfr));
    child.close();
    return new WeakReference<>(child);
  }
}
