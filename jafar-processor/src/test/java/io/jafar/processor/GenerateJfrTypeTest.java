package io.jafar.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @GenerateJfrType} on a {@code jdk.jfr.Event} subclass (#89): the processor writes the
 * {@code @JfrType} interface and, in the next round, its handler; events the class committed read
 * back through the interface.
 */
class GenerateJfrTypeTest {
  @TempDir Path dir;

  private static final String HEADER =
      "package test;\n"
          + "import io.jafar.parser.api.GenerateJfrType;\n"
          + "import jdk.jfr.Label;\n"
          + "import jdk.jfr.Name;\n";

  private static JavaFileObject src(String simpleName, String body) {
    return JavaFileObjects.forSourceString("test." + simpleName, HEADER + body);
  }

  private static final JavaFileObject BASE =
      src(
          "BaseEvent",
          "public abstract class BaseEvent extends jdk.jfr.Event {\n"
              + "  public int tenant;\n"
              + "  protected int prot;\n"
              + "  int pkgField;\n"
              + "  private int secret; // a superclass's private field: JFR does not record it\n"
              + "}\n");

  private static final JavaFileObject PROBE =
      src(
          "ProbeEvent",
          "@GenerateJfrType\n"
              + "@Name(\"test.Probe\")\n"
              + "public class ProbeEvent extends BaseEvent {\n"
              + "  @Label(\"Request ID\") public String requestId;\n"
              + "  public long bytes;\n"
              + "  public boolean ok;\n"
              + "  public double ratio;\n"
              + "  public char grade;\n"
              + "  public byte b;\n"
              + "  public short sh;\n"
              + "  public float fl;\n"
              + "  @Name(\"my.field\") public long dotted; // not an identifier: JFR keeps 'dotted'\n"
              + "  @Name(\"renamed\") public long internalName;\n"
              + "  public Thread worker;\n"
              + "  public transient long notRecorded;\n"
              + "  public static int alsoNotRecorded;\n"
              + "  public Class<?> notGenerated;\n"
              + "}\n");

  private static Set<String> accessors(Class<?> iface) {
    Set<String> names = new TreeSet<>();
    for (Method m : iface.getDeclaredMethods()) {
      names.add(m.getReturnType().getSimpleName() + " " + m.getName());
    }
    return names;
  }

  @Test
  void theInterfaceHasAnAccessorPerRecordedField() throws Exception {
    ClassLoader loader = compileAndLoad(BASE, PROBE);
    Class<?> iface = loader.loadClass("test.ProbeEventJfr");
    assertTrue(iface.isInterface());
    assertEquals("test.Probe", iface.getAnnotation(io.jafar.parser.api.JfrType.class).value());
    assertEquals(
        new TreeSet<>(
            Arrays.asList(
                "long startTime",
                "long duration",
                "JfrThread eventThread",
                "int tenant",
                "int prot",
                "int pkgField",
                "String requestId",
                "byte b",
                "short sh",
                "float fl",
                "long dotted",
                "long bytes",
                "boolean ok",
                "double ratio",
                "char grade",
                "long renamed",
                "JfrThread worker")),
        accessors(iface));
    Class<?> thread = loader.loadClass("test.JfrThread");
    assertEquals(
        "java.lang.Thread", thread.getAnnotation(io.jafar.parser.api.JfrType.class).value());
    // The handler the processor generates for every @JfrType interface, in the next round
    loader.loadClass("test.ProbeEventJfrFactory");
  }

  @Test
  void eventsTheClassCommittedReadBack() throws Exception {
    ClassLoader loader = compileAndLoad(BASE, PROBE);
    Class<?> eventClass = loader.loadClass("test.ProbeEvent");
    Path jfr = dir.resolve("probe.jfr");
    // A running thread: JFR records no thread for one that never started
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              try {
                done.await();
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            },
            "probe-worker");
    worker.start();
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable("test.Probe");
      r.start();
      for (int i = 0; i < 3; i++) {
        Object e = eventClass.getConstructor().newInstance();
        set(e, "tenant", 7);
        set(e, "requestId", "req-" + i);
        set(e, "bytes", 1000L * i);
        set(e, "ok", i % 2 == 0);
        set(e, "ratio", i / 4.0);
        set(e, "grade", (char) ('a' + i));
        set(e, "b", (byte) -i);
        set(e, "sh", (short) (-300 - i));
        set(e, "fl", 1.5f * i);
        set(e, "dotted", 20L + i);
        setDeclared(e, "prot", 5);
        setDeclared(e, "pkgField", 6);
        set(e, "internalName", 40L + i);
        set(e, "worker", worker);
        set(e, "notRecorded", 99L);
        eventClass.getMethod("commit").invoke(e);
      }
      r.stop();
      r.dump(jfr);
    } finally {
      done.countDown();
      worker.join();
    }
    // Handlers run on the parser's threads: compare with the thread that committed the events
    String committer = Thread.currentThread().getName();
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.ProbeEventJfr"),
        e ->
            seen.add(
                call(e, "tenant")
                    + " "
                    + call(e, "prot")
                    + " "
                    + call(e, "pkgField")
                    + " "
                    + call(e, "b")
                    + " "
                    + call(e, "sh")
                    + " "
                    + call(e, "fl")
                    + " "
                    + call(e, "dotted")
                    + " "
                    + call(e, "requestId")
                    + " "
                    + call(e, "bytes")
                    + " "
                    + call(e, "ok")
                    + " "
                    + call(e, "ratio")
                    + " "
                    + call(e, "grade")
                    + " "
                    + call(e, "renamed")
                    + " "
                    + call(call(e, "worker"), "javaName")
                    + " "
                    + call(call(e, "eventThread"), "javaName").equals(committer)
                    + " "
                    + ((Long) call(e, "startTime") > 0)),
        "ProbeEventJfr",
        "JfrThread");
    Collections.sort(seen);
    assertEquals(
        Set.of(
            "7 5 6 0 -300 0.0 20 req-0 0 true 0.0 a 40 probe-worker true true",
            "7 5 6 -1 -301 1.5 21 req-1 1000 false 0.25 b 41 probe-worker true true",
            "7 5 6 -2 -302 3.0 22 req-2 2000 true 0.5 c 42 probe-worker true true"),
        Set.copyOf(seen));
    assertEquals(3, seen.size());

    // The interface is the fields JFR itself lists for the class, stackTrace aside
    Set<String> recorded = new TreeSet<>();
    for (jdk.jfr.ValueDescriptor d :
        jdk.jfr.EventType.getEventType(eventClass.asSubclass(jdk.jfr.Event.class)).getFields()) {
      recorded.add(d.getName());
    }
    recorded.remove("stackTrace");
    recorded.remove("notGenerated"); // a Class field: recorded, left out of the interface by design
    Set<String> declared = new TreeSet<>();
    for (Method m : loader.loadClass("test.ProbeEventJfr").getDeclaredMethods()) {
      declared.add(m.getName());
    }
    assertEquals(recorded, declared);
  }

  /** Sets a field the class or a superclass declares, whatever its access. */
  private static void setDeclared(Object target, String field, Object value) throws Exception {
    for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException ignored) {
        // a superclass's
      }
    }
    throw new NoSuchFieldException(field);
  }

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getField(field);
    f.set(target, value);
  }

  @Test
  void aNestedClassAndAChosenName() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            src(
                "Outer",
                "public class Outer {\n"
                    + "  @GenerateJfrType public static class Inner extends jdk.jfr.Event {\n"
                    + "    public int n;\n  }\n"
                    + "  @GenerateJfrType(\"Chosen\") public static class Other extends jdk.jfr.Event"
                    + " {\n    public int m;\n  }\n"
                    + "}\n"));
    Class<?> inner = loader.loadClass("test.OuterInnerJfr");
    // JFR names an event without @Name by its class's binary name
    assertEquals(
        "test.Outer$Inner", inner.getAnnotation(io.jafar.parser.api.JfrType.class).value());
    Class<?> chosen = loader.loadClass("test.Chosen");
    assertEquals(
        "test.Outer$Other", chosen.getAnnotation(io.jafar.parser.api.JfrType.class).value());
  }

  @Test
  void aFieldWithNoGeneratedTypeIsLeftOutWithANote() {
    Compilation c = javac().withProcessors(new JfrTypeProcessor()).compile(BASE, PROBE);
    assertThat(c).succeeded();
    assertThat(c).hadNoteContaining("field 'notGenerated'");
    assertThat(c).hadNoteContaining("JFR records it, but no interface is generated");
    assertThat(c).hadWarningContaining("@Name(\"my.field\") is not a Java identifier");
    assertThat(c)
        .generatedSourceFile("test.ProbeEventJfr")
        .contentsAsUtf8String()
        .doesNotContain("notRecorded");
  }

  @Test
  void misuseIsAnError() {
    Compilation notAnEvent =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(src("Plain", "@GenerateJfrType public class Plain { int x; }\n"));
    assertThat(notAnEvent).failed();
    assertThat(notAnEvent).hadErrorContaining("goes on a class that extends jdk.jfr.Event");

    Compilation anAbstract =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(
                src(
                    "Abs",
                    "@GenerateJfrType public abstract class Abs extends jdk.jfr.Event { int x;"
                        + " }\n"));
    assertThat(anAbstract).failed();
    assertThat(anAbstract).hadErrorContaining("abstract class");

    Compilation taken =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(
                src("TakenJfr", "public interface TakenJfr {}\n"),
                src(
                    "Taken",
                    "@GenerateJfrType public class Taken extends jdk.jfr.Event { int x; }\n"));
    assertThat(taken).failed();
    assertThat(taken).hadErrorContaining("test.TakenJfr exists already");
  }

  @Test
  void twoEventClassesShareOnePackageThreadInterface() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            src("A", "@GenerateJfrType public class A extends jdk.jfr.Event { public int a; }\n"),
            src("B", "@GenerateJfrType public class B extends jdk.jfr.Event { public int b; }\n"));
    Class<?> thread = loader.loadClass("test.JfrThread");
    assertEquals(thread, loader.loadClass("test.AJfr").getMethod("eventThread").getReturnType());
    assertEquals(thread, loader.loadClass("test.BJfr").getMethod("eventThread").getReturnType());
    Compilation c =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(
                src(
                    "A",
                    "@GenerateJfrType public class A extends jdk.jfr.Event { public int a; }\n"),
                src(
                    "B",
                    "@GenerateJfrType public class B extends jdk.jfr.Event { public int b; }\n"));
    assertEquals(
        1,
        c.generatedSourceFiles().stream()
            .filter(f -> f.getName().endsWith("/test/JfrThread.java"))
            .count());
  }

  @Test
  void aRecordedNameMayNotRepeat() {
    for (String fields :
        new String[] {
          "@Name(\"dup\") public long a; @Name(\"dup\") public int b;",
          "public String startTime;",
          "@Name(\"duration\") public int d;",
          "public long stackTrace;",
        }) {
      Compilation c =
          javac()
              .withProcessors(new JfrTypeProcessor())
              .compile(
                  src(
                      "Dup",
                      "@GenerateJfrType public class Dup extends jdk.jfr.Event { "
                          + fields
                          + " }\n"));
      assertThat(c).failed();
      assertThat(c).hadErrorContaining("records two fields named");
    }
  }

  @Test
  void aRebuildWithTheEarlierOutputOnTheClassPathWorks() throws Exception {
    Path out = dir.resolve("first");
    ProcessorTestSupport.compileTo(out, BASE, PROBE);
    List<java.io.File> classpath = new ArrayList<>();
    classpath.add(out.toFile());
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      classpath.add(new java.io.File(entry));
    }
    Compilation again =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .withClasspath(classpath)
            .compile(BASE, PROBE);
    assertThat(again).succeeded();
  }

  @Test
  void aHandWrittenInterfaceInTheSameCompilation() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            BASE,
            PROBE,
            src(
                "Other",
                "@io.jafar.parser.api.JfrType(\"test.Other\") public interface Other { long x(); }\n"));
    loader.loadClass("test.OtherFactory");
    loader.loadClass("test.ProbeEventJfrFactory");
  }

  @Test
  void anExistingJfrThreadIsUsedOrRefused() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            src(
                "JfrThread",
                "@io.jafar.parser.api.JfrType(\"java.lang.Thread\") public interface JfrThread {"
                    + " String javaName(); }\n"),
            src("A", "@GenerateJfrType public class A extends jdk.jfr.Event { public int a; }\n"));
    assertEquals(1, loader.loadClass("test.JfrThread").getDeclaredMethods().length);
    Compilation wrong =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(
                src("JfrThread", "public interface JfrThread {}\n"),
                src(
                    "A",
                    "@GenerateJfrType public class A extends jdk.jfr.Event { public int a; }\n"));
    assertThat(wrong).failed();
    assertThat(wrong).hadErrorContaining("is not a @JfrType(\"java.lang.Thread\") interface");
  }

  @Test
  void twoClassesForOneInterfaceNameAreAnError() {
    Compilation c =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(
                src(
                    "Outer",
                    "public class Outer { @GenerateJfrType public static class Inner extends"
                        + " jdk.jfr.Event { public int n; } }\n"),
                src(
                    "OuterInner",
                    "@GenerateJfrType public class OuterInner extends jdk.jfr.Event { public int m; }\n"));
    assertThat(c).failed();
    assertThat(c).hadErrorContaining("test.OuterInnerJfr is taken");
  }

  @Test
  void anInvalidEventNameFallsBackToTheClassName() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            src(
                "Bad",
                "@GenerateJfrType @Name(\"bad-name*/x\") public class Bad extends jdk.jfr.Event {"
                    + " public int n; }\n"));
    assertEquals(
        "test.Bad",
        loader.loadClass("test.BadJfr").getAnnotation(io.jafar.parser.api.JfrType.class).value());
  }

  @Test
  void aTypeAnnotationOnAPrimitiveFieldIsNotCopied() throws Exception {
    ClassLoader loader =
        compileAndLoad(
            src(
                "Unit",
                "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) public"
                    + " @interface Unit {}\n"),
            src(
                "Sized",
                "@GenerateJfrType public class Sized extends jdk.jfr.Event { public @Unit long"
                    + " bytes; }\n"));
    assertEquals(long.class, loader.loadClass("test.SizedJfr").getMethod("bytes").getReturnType());
  }
}
