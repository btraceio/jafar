package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An accessor may return the interface registered for a simple type ({@code ThreadState state()}
 * for {@code jdk.ExecutionSample.state}, a pooled {@code jdk.types.ThreadState}, which wraps one
 * {@code String}), not only the value it wraps ({@code String state()}).
 *
 * <p>{@code CodeGenerator} unwrapped every simple type to its value, so the runtime class
 * implemented {@code state()} returning {@code String} and calling {@code ThreadState state()}
 * threw {@code AbstractMethodError}. The build-time handlers read it through the interface's
 * handler (found by {@code BuildTimeRuntimeParityTest} in jafar-processor).
 */
public class TypedSimpleTypeInterfaceTest {

  @JfrType("jdk.ExecutionSample")
  public interface SampleWithStateInterface {
    long startTime();

    @JfrField("state")
    ThreadState state();
  }

  @JfrType("jdk.types.ThreadState")
  public interface ThreadState {
    String name();
  }

  @JfrType("jdk.ExecutionSample")
  public interface SampleWithStateString {
    long startTime();

    @JfrField("state")
    String state();
  }

  @ParameterizedTest
  @ValueSource(strings = {"test-ap.jfr", "test-jfr.jfr"})
  void anInterfaceForASimpleTypeReadsTheSameStateAsTheWrappedString(String fixture)
      throws Exception {
    URL resource = getClass().getClassLoader().getResource(fixture);
    assertNotNull(resource, fixture + " is not on the test classpath");
    Path jfr = Paths.get(resource.toURI());

    // how many samples have each (startTime, state), per path; one parse each, so each path
    // generates its own classes
    Map<String, Integer> viaInterface = new ConcurrentHashMap<>();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
      p.handle(
          SampleWithStateInterface.class,
          (e, ctl) -> {
            ThreadState state = e.state();
            viaInterface.merge(
                e.startTime() + " " + (state == null ? "null" : state.name()), 1, Integer::sum);
          });
      p.run();
    }
    Map<String, Integer> viaString = new ConcurrentHashMap<>();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
      p.handle(
          SampleWithStateString.class,
          (e, ctl) -> viaString.merge(e.startTime() + " " + e.state(), 1, Integer::sum));
      p.run();
    }

    // The premise: samples with a real state, read through the existing String path.
    assertFalse(viaString.isEmpty(), "no " + fixture + " samples");
    assertFalse(
        viaString.keySet().stream().allMatch(k -> k.endsWith(" null")),
        "no sample has a state: " + fixture);
    assertEquals(new TreeMap<>(viaString), new TreeMap<>(viaInterface));
  }
}
