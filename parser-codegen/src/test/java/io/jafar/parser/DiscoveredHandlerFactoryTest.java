package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.HandlerInterfaceConflictTest.FixedSampleA;
import io.jafar.parser.HandlerInterfaceConflictTest.SampleA;
import io.jafar.parser.HandlerInterfaceConflictTest.SampleAFactory;
import io.jafar.parser.HandlerInterfaceConflictTest.SampleB;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.TypedJafarParser;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build-time factory found by {@code ServiceLoader}, as {@code jafar-processor} registers them.
 *
 * <p>The typed parser discovers factories once per class loader, in a static initializer, so this
 * test cannot add one to the test JVM's classpath without affecting every other test in it. Each
 * case runs in its own class loader instead: the test classpath plus a {@code
 * META-INF/services/io.jafar.parser.api.HandlerFactory} naming {@link SampleAFactory}, with the
 * platform class loader as parent, so the parser classes are loaded, and discover, afresh. {@link
 * Probe} runs inside it and reports in strings.
 *
 * <p>This is the case found on {@code parser-codegen}'s JMH classpath: a handler for {@code
 * io.jafar.parser.types.JFRExecutionSample} received 0 of 8000 events because the generated factory
 * of {@code io.jafar.parser.benchmark.events.JFRExecutionSample} had claimed {@code
 * jdk.ExecutionSample}.
 */
public class DiscoveredHandlerFactoryTest {
  @TempDir Path dir;

  @Test
  void aDiscoveredFactoryForAnotherInterfaceDoesNotSilenceTheHandler() throws Exception {
    Path jfr = HandlerInterfaceConflictTest.writeRecording(dir);
    // Was: "0 events". SampleAFactory had claimed test.Sample for SampleA when the parser was
    // created, and the SampleB handler was never called.
    assertEquals(
        Probe.describe(HandlerInterfaceConflictTest.allSeqs(), "runtime-generated"),
        probe(jfr, SampleB.class, "plain"));
  }

  @Test
  void aDiscoveredFactoryDecodesItsInterfaceUnlessTheParserIgnoresDiscoveredFactories()
      throws Exception {
    Path jfr = HandlerInterfaceConflictTest.writeRecording(dir);
    // The premise: the factory is discovered in the probe's class loader, and used.
    assertEquals(
        Probe.describe(
            Collections.nCopies(
                HandlerInterfaceConflictTest.EVENTS, HandlerInterfaceConflictTest.FROM_FACTORY),
            "factory"),
        probe(jfr, SampleA.class, "plain"));
    assertEquals(
        Probe.describe(HandlerInterfaceConflictTest.allSeqs(), "runtime-generated"),
        probe(jfr, SampleA.class, "ignore"));
  }

  @Test
  void ignoringDiscoveredFactoriesKeepsAFactoryPassedToRegisterFactory() throws Exception {
    Path jfr = HandlerInterfaceConflictTest.writeRecording(dir);
    assertEquals(
        Probe.describe(
            Collections.nCopies(
                HandlerInterfaceConflictTest.EVENTS, HandlerInterfaceConflictTest.FROM_FACTORY),
            "factory"),
        probe(jfr, SampleA.class, "register-then-ignore"));
  }

  @Test
  void ignoringDiscoveredFactoriesAfterHandleOfTheirInterfaceIsRefused() throws Exception {
    // handle() skips the accessors of a factory-served interface that only a generated handler
    // can read; dropping the factory afterwards would leave them to the runtime path.
    Path jfr = HandlerInterfaceConflictTest.writeRecording(dir);
    String result = probe(jfr, SampleA.class, "handle-then-ignore");
    assertTrue(result.startsWith("refused: java.lang.IllegalStateException: "), result);
    assertTrue(result.contains(SampleA.class.getName()), result);
    assertTrue(result.contains(SampleAFactory.class.getName()), result);
    // Refused means unchanged: the factory still decodes SampleA.
    assertTrue(
        result.endsWith(
            Probe.describe(
                Collections.nCopies(
                    HandlerInterfaceConflictTest.EVENTS, HandlerInterfaceConflictTest.FROM_FACTORY),
                "factory")),
        result);
  }

  /** Runs {@link Probe#run} in a class loader that discovers {@link SampleAFactory}. */
  private String probe(Path jfr, Class<?> iface, String mode) throws Exception {
    Path services = dir.resolve("services");
    Path file = services.resolve("META-INF/services/" + HandlerFactory.class.getName());
    Files.createDirectories(file.getParent());
    Files.write(file, (SampleAFactory.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));

    List<URL> urls = new ArrayList<>();
    urls.add(services.toUri().toURL());
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      urls.add(Paths.get(entry).toUri().toURL());
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      // ServiceLoader.load(Class) looks in the context class loader.
      thread.setContextClassLoader(loader);
      Method run =
          loader
              .loadClass(Probe.class.getName())
              .getMethod("run", Path.class, String.class, String.class);
      return (String) run.invoke(null, jfr, iface.getName(), mode);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** Loaded in the probe's class loader; takes and returns only JDK types. */
  public static final class Probe {
    /**
     * Parses {@code jfr} with one handler for the interface named {@code iface} and describes what
     * it received. {@code mode}: {@code plain}; {@code ignore} (ignoreDiscoveredFactories, then
     * handle); {@code register-then-ignore} (registerFactory(new SampleAFactory()), then
     * ignoreDiscoveredFactories, then handle); {@code handle-then-ignore} (the outcome of
     * ignoreDiscoveredFactories after handle is prefixed).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String run(Path jfr, String iface, String mode) throws Exception {
      ClassLoader loader = Probe.class.getClassLoader();
      Class clz = loader.loadClass(iface);
      Method seq = clz.getMethod("seq");
      List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
      Set<String> decodedBy = Collections.synchronizedSet(new TreeSet<>());
      String prefix = "";
      try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
        if (mode.equals("register-then-ignore")) {
          p.registerFactory(new SampleAFactory());
        }
        if (mode.equals("ignore") || mode.equals("register-then-ignore")) {
          p.ignoreDiscoveredFactories();
        }
        p.handle(
            clz,
            (JFRHandler)
                (e, ctl) -> {
                  try {
                    seqs.add((Integer) seq.invoke(e));
                  } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                  }
                  decodedBy.add(
                      e.getClass() == FixedSampleA.class ? "factory" : "runtime-generated");
                });
        if (mode.equals("handle-then-ignore")) {
          try {
            p.ignoreDiscoveredFactories();
            prefix = "not refused; ";
          } catch (RuntimeException e) {
            prefix = "refused: " + e + "; ";
          }
        }
        p.run();
      }
      List<Integer> sorted = new ArrayList<>(seqs);
      Collections.sort(sorted);
      return prefix + describe(sorted, String.join("+", decodedBy));
    }

    static String describe(List<Integer> sortedSeqs, String decodedBy) {
      return sortedSeqs.size() + " events, seq " + sortedSeqs + ", decoded by " + decodedBy;
    }
  }
}
