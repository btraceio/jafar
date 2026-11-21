package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
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

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void anInterfaceInAChildClassLoaderIsImplemented() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    Path jfr = Paths.get(resource.toURI());
    try (URLClassLoader child =
        new URLClassLoader(new URL[] {compile().toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> sample = child.loadClass("child.CSample");
      Class<?> thread = child.loadClass("child.CThread");
      // the premise: the parser's loader cannot see the interface
      try {
        Class.forName("child.CSample", false, TypedJafarParser.class.getClassLoader());
        throw new AssertionError("the parser's class loader sees child.CSample");
      } catch (ClassNotFoundException expected) {
        // as intended
      }
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
                    Object t = sampledThread.invoke(e);
                    seen.add(startTime.invoke(e) + " " + (t == null ? null : javaName.invoke(t)));
                  } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                  }
                });
        p.run();
      }
      assertTrue(seen.size() > 1, "no samples");
      assertTrue(seen.stream().anyMatch(s -> s.contains("worker-")), seen.get(0));
    }
  }
}
