package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.compileTo;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The typed parser has two implementations of the same interfaces: the handlers {@code
 * jafar-processor} generates at build time, and the classes {@code parser-codegen}'s {@code
 * CodeGenerator} generates at runtime. They must read the same recording into the same values
 * (AGENTS.md, "Keep the two typed handler generators at parity"). This parses each fixture with
 * both and compares every event as rendered through its getters, references followed.
 *
 * <p>Everything runs in one class loader, built from {@code parser-codegen} (kept off the test
 * class path, see build.gradle), the test class path and the compiled interfaces. An interface in a
 * child loader is covered by {@code ChildLoaderFactoryTest}.
 *
 * <p>It checks values only. The other half of that parity, how much work a getter or a field read
 * costs, is not visible here: the build-time getters once looked their constant pool up on every
 * call while the runtime classes kept it, for the same values.
 */
class BuildTimeRuntimeParityTest {

  /** One interface per type an execution sample reaches, with every accessor kind in use. */
  private static void compileInterfaces(Path classes, List<String> options) throws Exception {
    compileTo(
        classes,
        options,
        source(
            "PSample",
            "@JfrType(\"jdk.ExecutionSample\")\npublic interface PSample {\n"
                + "  long startTime();\n  PThread sampledThread();\n  PStackTrace stackTrace();\n"
                + "  PState state();\n"
                // several accessors of one field: an alias, and a pooled reference's raw index
                + "  @io.jafar.parser.api.JfrField(\"startTime\") long time();\n"
                + "  @io.jafar.parser.api.JfrField(value = \"sampledThread\", raw = true) long sampledThreadRef();\n"
                + "  @io.jafar.parser.api.JfrField(\"sampledThread\") PThread thread();\n}\n"),
        source(
            "PThread",
            "@JfrType(\"java.lang.Thread\")\npublic interface PThread {\n"
                + "  String osName();\n  long osThreadId();\n  String javaName();\n"
                + "  long javaThreadId();\n  @io.jafar.parser.api.JfrField(\"javaName\") String name();\n}\n"),
        source(
            "PStackTrace",
            "@JfrType(\"jdk.types.StackTrace\")\npublic interface PStackTrace {\n"
                + "  boolean truncated();\n  PFrame[] frames();\n}\n"),
        source(
            "PFrame",
            "@JfrType(\"jdk.types.StackFrame\")\npublic interface PFrame {\n"
                + "  PMethod method();\n  int lineNumber();\n  int bytecodeIndex();\n"
                + "  String type();\n"
                // an inline int widened to long, next to its int accessor
                + "  @io.jafar.parser.api.JfrField(\"lineNumber\") long line();\n}\n"),
        source(
            "PMethod",
            "@JfrType(\"jdk.types.Method\")\npublic interface PMethod {\n"
                + "  PClass type();\n  String name();\n  String descriptor();\n  int modifiers();\n"
                + "  boolean hidden();\n}\n"),
        source(
            "PClass",
            "@JfrType(\"java.lang.Class\")\npublic interface PClass {\n"
                + "  String name();\n  int modifiers();\n}\n"),
        source(
            "PState",
            "@JfrType(\"jdk.types.ThreadState\")\npublic interface PState {\n  String name();\n}\n"));
  }

  @TempDir Path classes;

  private static final String[] FACTORIES = {
    "PSample", "PThread", "PStackTrace", "PFrame", "PMethod", "PClass", "PState"
  };

  /**
   * Each fixture with its own layouts (the build-time handlers read it with a straight-line read,
   * see Layouts), with none, and with the compiling JDK's, which differ (both use the read loop).
   * Every way must read what the runtime classes read.
   */
  /*
   * The third column is where the handlers' layouts come from: a recording, "-" (none), or "jdk"
   * (only the compiling JDK). Both fixtures store jdk.ExecutionSample as stackTrace, eventThread,
   * startTime, sampledThread, state; a current JDK writes startTime, sampledThread, stackTrace,
   * state. Compiled with the JDK's layout and reading a fixture, the plan finds no straight-line
   * read and uses the loop: what a recording from another JDK or app version gets.
   */
  @ParameterizedTest
  @CsvSource({
    "test-ap.jfr, readLayout1, test-ap.jfr",
    "test-ap.jfr, the loop, -",
    "test-ap.jfr, the loop, jdk",
    "test-jfr.jfr, readLayout1, test-jfr.jfr",
    "test-jfr.jfr, the loop, -",
    "test-jfr.jfr, the loop, jdk"
  })
  void buildTimeAndRuntimeHandlersReadTheSameValues(
      String fixture, String expectedRead, String layoutsFrom) throws Exception {
    URL resource = getClass().getClassLoader().getResource(fixture);
    assertNotNull(resource, fixture + " is not on the test classpath (parser-core test resources)");
    Path jfr = Paths.get(resource.toURI());
    List<String> options = new ArrayList<>();
    if (!layoutsFrom.equals("jdk")) {
      options.add("-Ajafar.layouts.jdk=false");
    }
    if (!layoutsFrom.equals("-") && !layoutsFrom.equals("jdk")) {
      URL layoutResource = getClass().getClassLoader().getResource(layoutsFrom);
      assertNotNull(layoutResource, layoutsFrom);
      options.add("-Ajafar.layouts=" + Paths.get(layoutResource.toURI()));
    }
    compileInterfaces(classes, options);

    List<String> buildTime = probe(jfr, true);
    List<String> runtime = probe(jfr, false);

    // The premise: the recording has samples, and each side was decoded by the implementation it
    // names. Two runs of one implementation would agree whatever the other does.
    assertTrue(buildTime.size() > 1, "no " + fixture + " samples");
    assertEquals("decoded by test.PSampleHandler", buildTime.get(0));
    // which read the build-time handler's plan chose for the samples: a straight-line one only
    // when the handlers were compiled with this recording's layout
    String read = buildTime.remove(1);
    assertEquals("read by " + expectedRead, read);
    assertTrue(
        runtime.get(0).startsWith("decoded by ") && !runtime.get(0).equals(buildTime.get(0)),
        "the runtime side was " + runtime.get(0));
    // Several accessors of one field (time, sampledThreadRef, thread, name, line) read real values
    // on both sides: two sides that both threw would compare equal.
    String threw =
        buildTime.stream().skip(1).filter(e -> e.contains("=threw ")).findFirst().orElse(null);
    assertNull(threw, "a getter threw");
    assertTrue(buildTime.stream().skip(1).allMatch(e -> e.contains(" time=")), "time()");
    assertTrue(buildTime.stream().skip(1).anyMatch(e -> e.contains(" line=")), "line()");
    assertEquals(buildTime.size(), runtime.size(), "events");
    for (int i = 1; i < buildTime.size(); i++) {
      // the first difference only: a whole recording rendered is too long to read in a report
      assertEquals(
          buildTime.get(i),
          runtime.get(i),
          "sorted event " + i + " of " + (buildTime.size() - 1) + " (expected: build time)");
    }
    // PSample.state() (an interface for the simple type jdk.types.ThreadState) was a known
    // difference until CodeGenerator implemented such accessors: the runtime class threw
    // AbstractMethodError. A difference found again is fixed, or listed here with its reason.
  }

  /** Runs {@link Probe#run} in a class loader holding the parser and the compiled interfaces. */
  @SuppressWarnings("unchecked")
  private List<String> probe(Path jfr, boolean buildTime) throws Exception {
    List<URL> urls = new ArrayList<>();
    urls.add(classes.toUri().toURL());
    String runtimeClasspath = System.getProperty("jafar.test.typedRuntimeClasspath");
    assertNotNull(runtimeClasspath, "jafar.test.typedRuntimeClasspath (set by build.gradle)");
    for (String path : new String[] {runtimeClasspath, System.getProperty("java.class.path")}) {
      for (String entry : path.split(File.pathSeparator)) {
        urls.add(Paths.get(entry).toUri().toURL());
      }
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      // the parser finds its DeserializerFactory with ServiceLoader, in the context class loader
      thread.setContextClassLoader(loader);
      Method run =
          loader.loadClass(Probe.class.getName()).getMethod("run", Path.class, boolean.class);
      return (List<String>) run.invoke(null, jfr, buildTime);
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

  /** Loaded in the probe's class loader; takes and returns only JDK types. */
  public static final class Probe {
    /**
     * Every sample of {@code jfr}, rendered and sorted, after one line naming the class that
     * decoded the first sample: by the build-time handlers, registered, or by runtime-generated
     * classes, with discovered factories ignored. The build-time handler is reused for the next
     * event, so each event is rendered in the handler call.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<String> run(Path jfr, boolean buildTime) throws Exception {
      ClassLoader loader = Probe.class.getClassLoader();
      Class<?> iface = loader.loadClass("test.PSample");
      List<String> events = Collections.synchronizedList(new ArrayList<>());
      String[] decodedBy = new String[2];
      try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
        if (buildTime) {
          for (String simpleName : FACTORIES) {
            p.registerFactory(
                (HandlerFactory)
                    loader
                        .loadClass("test." + simpleName + "Factory")
                        .getConstructor()
                        .newInstance());
          }
        } else {
          p.ignoreDiscoveredFactories();
        }
        p.handle(
            (Class) iface,
            (JFRHandler)
                (e, ctl) -> {
                  synchronized (decodedBy) {
                    if (decodedBy[0] == null) {
                      decodedBy[0] = e.getClass().getName();
                      decodedBy[1] = readBy(e);
                    }
                  }
                  events.add(render(e, iface, 0));
                });
        p.run();
      }
      List<String> out = new ArrayList<>(events);
      Collections.sort(out);
      out.add(0, "decoded by " + decodedBy[0]);
      if (buildTime) {
        out.add(1, "read by " + decodedBy[1]);
      }
      return out;
    }

    /** Which read a build-time handler's current plan uses: its straight-line read, or the loop. */
    private static String readBy(Object handler) {
      try {
        java.lang.reflect.Field planField = handler.getClass().getDeclaredField("plan");
        planField.setAccessible(true);
        Object plan = planField.get(handler);
        java.lang.reflect.Field layout;
        try {
          layout = plan.getClass().getDeclaredField("layout");
        } catch (NoSuchFieldException none) {
          return "the loop"; // no layout known when the handler was generated
        }
        layout.setAccessible(true);
        int k = layout.getInt(plan);
        return k == 0 ? "the loop" : "readLayout" + k;
      } catch (ReflectiveOperationException ex) {
        return "the runtime class";
      }
    }
  }

  /** A value as its interface's getters return it, in name order, references followed. */
  private static String render(Object value, Class<?> type, int depth) {
    if (value == null) {
      return "null";
    }
    if (type.isArray()) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < Array.getLength(value); i++) {
        sb.append(i == 0 ? "" : ", ")
            .append(render(Array.get(value, i), type.getComponentType(), depth + 1));
      }
      return sb.append(']').toString();
    }
    if (!type.isInterface() || depth > 8) {
      return String.valueOf(value);
    }
    if (!type.isInstance(value)) {
      // a getter returned what its declared type is not: a difference in itself, shown as such
      return "not a " + type.getSimpleName() + ": " + value.getClass().getName() + " " + value;
    }
    Method[] getters = type.getDeclaredMethods();
    Arrays.sort(getters, Comparator.comparing(Method::getName));
    StringBuilder sb = new StringBuilder(type.getSimpleName()).append('{');
    for (Method m : getters) {
      if (m.getParameterCount() != 0 || m.isDefault()) {
        continue;
      }
      String rendered;
      try {
        rendered = render(m.invoke(value), m.getReturnType(), depth + 1);
      } catch (InvocationTargetException ex) {
        rendered = "threw " + ex.getCause().getClass().getName();
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError(ex);
      }
      sb.append(m.getName()).append('=').append(rendered).append(' ');
    }
    return sb.append('}').toString();
  }
}
