package io.jafar.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.TypedJafarParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.tools.JavaFileObject;

/**
 * Runs {@link JfrTypeProcessor} on interface sources in memory, loads what it compiled, and parses
 * a recording with the generated factories registered, so tests exercise the processor's real
 * output end to end.
 */
public final class ProcessorTestSupport {
  private ProcessorTestSupport() {}

  /** A source file for {@code test.<simpleName>}. */
  public static JavaFileObject source(String simpleName, String body) {
    return JavaFileObjects.forSourceString(
        "test." + simpleName, "package test;\nimport io.jafar.parser.api.JfrType;\n" + body);
  }

  /** Compiles the sources with the processor and returns a loader for every compiled class. */
  public static ClassLoader compileAndLoad(JavaFileObject... sources) throws Exception {
    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(sources);
    assertThat(compilation).succeeded();
    Map<String, byte[]> classes = new HashMap<>();
    for (JavaFileObject f : compilation.generatedFiles()) {
      if (f.getKind() == JavaFileObject.Kind.CLASS) {
        String path = f.toUri().getPath();
        String name =
            path.substring(path.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length())
                .replace(".class", "")
                .replace('/', '.');
        try (InputStream in = f.openInputStream()) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          in.transferTo(out);
          classes.put(name, out.toByteArray());
        }
      }
    }
    return new ClassLoader(ProcessorTestSupport.class.getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classes.get(name);
        if (b == null) {
          throw new ClassNotFoundException(name);
        }
        return defineClass(name, b, 0, b.length);
      }
    };
  }

  /**
   * Parses {@code jfr} with the generated factories for {@code factoryOf} registered and calls
   * {@code handler} with each event of {@code iface}. Exceptions from generated getters invoked
   * reflectively by the handler propagate unwrapped.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void parse(
      Path jfr, ClassLoader loader, Class<?> iface, Consumer<Object> handler, String... factoryOf)
      throws Exception {
    try (TypedJafarParser p = TypedJafarParser.open(jfr.toString())) {
      for (String simpleName : factoryOf) {
        HandlerFactory f =
            (HandlerFactory)
                loader.loadClass("test." + simpleName + "Factory").getConstructor().newInstance();
        p.registerFactory(f);
      }
      p.handle((Class) iface, (JFRHandler) (e, ctl) -> handler.accept(e));
      p.run();
    }
  }

  /** Invokes a no-argument method reflectively, rethrowing what the method threw. */
  public static Object call(Object target, String method) {
    try {
      return target.getClass().getMethod(method).invoke(target);
    } catch (InvocationTargetException e) {
      Throwable c = e.getCause();
      throw c instanceof RuntimeException ? (RuntimeException) c : new RuntimeException(c);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
