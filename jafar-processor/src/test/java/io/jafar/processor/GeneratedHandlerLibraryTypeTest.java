package io.jafar.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A complex accessor whose field is stored inline is read by the generated handler of the
 * accessor's type. {@code @JfrType} has runtime retention, so that type can come from a precompiled
 * library, and the processor generates handlers only for the interfaces it compiles: referencing
 * {@code <Interface>Handler} unconditionally made the build fail where it had succeeded. The inline
 * read is now generated only when that handler is compiled here or is on the classpath.
 */
class GeneratedHandlerLibraryTypeTest {
  @TempDir Path dir;

  private static final JavaFileObject LIB_THREAD =
      JavaFileObjects.forSourceString(
          "lib.LibThread",
          "package lib;\nimport io.jafar.parser.api.JfrType;\n"
              + "@JfrType(\"java.lang.Thread\")\npublic interface LibThread {\n"
              + "  String javaName();\n}\n");

  private static final JavaFileObject APP_EVENT =
      JavaFileObjects.forSourceString(
          "app.AppEvent",
          "package app;\nimport io.jafar.parser.api.JfrType;\n"
              + "@JfrType(\"jdk.ExecutionSample\")\npublic interface AppEvent {\n"
              + "  lib.LibThread sampledThread();\n}\n");

  @Test
  void libraryInterfaceWithoutAHandlerCompiles() throws Exception {
    Path lib = compileLibrary(false);
    Compilation app = compileApp(lib);
    assertThat(app).succeeded();
    String handler = handlerSource(app);
    assertFalse(handler.contains("LibThreadHandler"), handler);
  }

  @Test
  void libraryInterfaceWithAHandlerIsReadInline() throws Exception {
    Path lib = compileLibrary(true);
    Compilation app = compileApp(lib);
    assertThat(app).succeeded();
    String handler = handlerSource(app);
    assertTrue(handler.contains("new lib.LibThreadHandler()"), handler);
  }

  /** Compiles the library, with or without the processor, into a directory of class files. */
  private Path compileLibrary(boolean withProcessor) throws Exception {
    Compilation c =
        withProcessor
            ? javac().withProcessors(new JfrTypeProcessor()).compile(LIB_THREAD)
            : javac().withOptions("-proc:none").compile(LIB_THREAD);
    assertThat(c).succeeded();
    Path out = dir.resolve(withProcessor ? "lib-with-handlers" : "lib-plain");
    for (JavaFileObject f : c.generatedFiles()) {
      if (f.getKind() != JavaFileObject.Kind.CLASS) {
        continue;
      }
      String path = f.toUri().getPath();
      Path target =
          out.resolve(path.substring(path.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length()));
      Files.createDirectories(target.getParent());
      try (InputStream in = f.openInputStream()) {
        Files.copy(in, target);
      }
    }
    return out;
  }

  /** Compiles the application with the processor and the library's class files on the classpath. */
  private static Compilation compileApp(Path lib) {
    List<File> classpath = new ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      classpath.add(new File(entry));
    }
    classpath.add(lib.toFile());
    return javac()
        .withProcessors(new JfrTypeProcessor())
        .withClasspath(classpath)
        .compile(APP_EVENT);
  }

  private static String handlerSource(Compilation c) throws Exception {
    return c.generatedSourceFile("app.AppEventHandler")
        .orElseThrow()
        .getCharContent(true)
        .toString();
  }
}
