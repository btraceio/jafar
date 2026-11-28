package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.compileTo;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build-time handlers whose interfaces are in a class loader below the parser's, with {@code
 * parser-codegen} on the parser's class path. {@code MutableMetadataLookup.bindDeserializers} then
 * generates a runtime class for every bound type, the factory-served ones included, and that
 * generation failed for an interface the parser's loader cannot see ({@code NoClassDefFoundError}),
 * failing the parse although the build-time handlers could read it.
 */
class ChildLoaderFactoryTest {

  @TempDir Path classes;

  @Test
  @SuppressWarnings("unchecked")
  void buildTimeHandlersInAChildLoaderReadWithParserCodegenPresent() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    compileTo(
        classes,
        source(
            "LSample",
            "@JfrType(\"jdk.ExecutionSample\")\npublic interface LSample {\n"
                + "  long startTime();\n  LThread sampledThread();\n}\n"),
        source(
            "LThread",
            "@JfrType(\"java.lang.Thread\")\npublic interface LThread {\n  String javaName();\n}\n"));

    // the parser's loader: parser-codegen, the test class path; not the compiled interfaces
    List<URL> urls = new ArrayList<>();
    String runtimeClasspath = System.getProperty("jafar.test.typedRuntimeClasspath");
    assertNotNull(runtimeClasspath, "jafar.test.typedRuntimeClasspath (set by build.gradle)");
    for (String path : new String[] {runtimeClasspath, System.getProperty("java.class.path")}) {
      for (String entry : path.split(File.pathSeparator)) {
        urls.add(Paths.get(entry).toUri().toURL());
      }
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try (URLClassLoader parser =
        new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      thread.setContextClassLoader(parser);
      Method run = parser.loadClass(Probe.class.getName()).getMethod("run", Path.class, URL.class);
      List<String> seen =
          (List<String>) run.invoke(null, Paths.get(resource.toURI()), classes.toUri().toURL());
      assertEquals("decoded by test.LSampleHandler", seen.get(0));
      assertTrue(seen.size() > 2, "no samples");
      assertTrue(seen.stream().anyMatch(s -> s.contains("worker-")), seen.get(1));
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new AssertionError(cause);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** Loaded in the parser's loader; the interfaces and handlers in a child of it. */
  public static final class Probe {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<String> run(Path jfr, URL compiled) throws Exception {
      try (URLClassLoader child =
          new URLClassLoader(new URL[] {compiled}, Probe.class.getClassLoader())) {
        try {
          Class.forName("test.LSample", false, Probe.class.getClassLoader());
          throw new AssertionError("the parser's class loader sees test.LSample");
        } catch (ClassNotFoundException expected) {
          // as intended
        }
        Class<?> iface = child.loadClass("test.LSample");
        Method startTime = iface.getMethod("startTime");
        Method sampledThread = iface.getMethod("sampledThread");
        Method javaName = child.loadClass("test.LThread").getMethod("javaName");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        String[] decodedBy = new String[1];
        try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
          for (String name : new String[] {"LSample", "LThread"}) {
            p.registerFactory(
                (HandlerFactory)
                    child.loadClass("test." + name + "Factory").getConstructor().newInstance());
          }
          p.handle(
              (Class) iface,
              (JFRHandler)
                  (e, ctl) -> {
                    synchronized (decodedBy) {
                      if (decodedBy[0] == null) {
                        decodedBy[0] = e.getClass().getName();
                      }
                    }
                    try {
                      Object t = sampledThread.invoke(e);
                      seen.add(startTime.invoke(e) + " " + (t == null ? null : javaName.invoke(t)));
                    } catch (ReflectiveOperationException ex) {
                      throw new AssertionError(ex);
                    }
                  });
          p.run();
        }
        List<String> out = new ArrayList<>(seen);
        out.add(0, "decoded by " + decodedBy[0]);
        return out;
      }
    }
  }
}
