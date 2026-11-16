package io.jafar.processor.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.internal_api.DeserializerFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With {@code parser-codegen} on the classpath, a build-time handler factory still decodes every
 * chunk of a recording and every run of a parser.
 *
 * <p>This is the configuration {@code GeneratedFactoryChunkBindingTest} cannot see. Without {@code
 * parser-codegen}, a chunk the factory was not bound to hands its handlers {@code null}, which is
 * loud. With it, such a chunk is decoded by a handler generated at run time: the values are right,
 * and nothing says that the build-time handlers the application was compiled with are not used. So
 * this test records, next to each value, which implementation produced it.
 *
 * <p>It has a source set and a task of its own ({@code :jafar-processor:codegenTest}): the
 * interfaces are compiled with the annotation processor, which registers their factories for {@code
 * ServiceLoader}, and {@code parser-codegen} is on the run-time classpath. Neither may leak into
 * the module's other tests, which compile their interfaces in a class loader the run-time generator
 * cannot see.
 *
 * <p>Fixture: {@code tck-test.jfr}, two chunks from two producers, with 380 and 22 {@code
 * jdk.ActiveSetting} events whose {@code eventThread} refers to {@code java.lang.Thread}: type id
 * 176 in chunk 1, 22 in chunk 2.
 */
class BuildTimeFactoryWithRuntimeCodegenTest {
  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /** How a value decoded by the generated build-time handlers is marked in the comparison. */
  private static final String BUILD_TIME = "build-time";

  /** Chunk start and thread name to the number of such events, as the untyped parser sees them. */
  private static Map<String, Integer> expected;

  @BeforeAll
  static void bothGeneratorsArePresentAndTheReferenceIsRead() throws Exception {
    // R9: this test is about a classpath. Prove it is the one it claims before trusting a pass.
    assertTrue(
        ServiceLoader.load(DeserializerFactory.class).iterator().hasNext(),
        "parser-codegen is not on the classpath: no run-time DeserializerFactory was found");
    TreeSet<String> factories = new TreeSet<>();
    for (HandlerFactory<?> factory : ServiceLoader.load(HandlerFactory.class)) {
      factories.add(factory.getJfrTypeName());
    }
    assertEquals(
        new TreeSet<>(Arrays.asList("java.lang.Thread", "jdk.ActiveSetting")),
        factories,
        "the annotation processor did not register the build-time factories of this source set");

    Map<String, Integer> seen = new TreeMap<>();
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(TCK)) {
      parser.handle(
          (type, value, ctl) -> {
            if (type.getName().equals("jdk.ActiveSetting")) {
              Object thread = Values.resolvedDeep(value).get("eventThread");
              count(seen, ctl, BUILD_TIME, BUILD_TIME, ((Map<?, ?>) thread).get("javaName"));
            }
          });
      parser.run();
    }
    assertEquals(2, seen.size(), "two chunks, one thread each: " + seen);
    assertEquals(402, seen.values().stream().mapToInt(Integer::intValue).sum(), seen.toString());
    expected = seen;
  }

  private static void count(
      Map<String, Integer> seen, Control ctl, String event, String thread, Object threadName) {
    String key =
        "chunk@"
            + ctl.chunkInfo().startTime()
            + " event:"
            + event
            + " thread:"
            + thread
            + " name="
            + threadName;
    synchronized (seen) {
      seen.merge(key, 1, Integer::sum);
    }
  }

  /** {@link #BUILD_TIME} for the processor's handler of {@code iface}, else what it is instead. */
  private static String decodedBy(Object value, Class<?> iface) {
    if (value == null) {
      return "null";
    }
    String name = value.getClass().getName();
    return name.equals(iface.getName() + "Handler") ? BUILD_TIME : "run-time";
  }

  private static void record(Map<String, Integer> seen, ActiveSettingEvent event, Control ctl) {
    ThreadRef thread = event == null ? null : event.eventThread();
    count(
        seen,
        ctl,
        decodedBy(event, ActiveSettingEvent.class),
        decodedBy(thread, ThreadRef.class),
        thread == null ? null : thread.javaName());
  }

  /** A parser with the factories {@code ServiceLoader} finds and a handler recording into seen. */
  private static TypedJafarParser open(Path jfr, int chunkThreads, Map<String, Integer> seen) {
    TypedJafarParser parser =
        ParsingContext.create().withParallelism(chunkThreads).newTypedParser(jfr);
    parser.handle(ActiveSettingEvent.class, (event, ctl) -> record(seen, event, ctl));
    return parser;
  }

  @Test
  void everyChunkIsDecodedByTheBuildTimeHandlers() throws Exception {
    Map<String, Integer> seen = new TreeMap<>();
    try (TypedJafarParser parser = open(TCK, 1, seen)) {
      parser.run();
    }
    assertEquals(expected, seen);
  }

  /** Whichever chunk reaches its metadata first. */
  @Test
  void everyChunkIsDecodedByTheBuildTimeHandlersWhenChunksAreParsedInParallel() throws Exception {
    for (int attempt = 1; attempt <= 20; attempt++) {
      Map<String, Integer> seen = new TreeMap<>();
      try (TypedJafarParser parser = open(TCK, 2, seen)) {
        parser.run();
      }
      assertEquals(expected, seen, "attempt " + attempt);
    }
  }

  @Test
  void aSecondRunOfTheParserIsDecodedByTheBuildTimeHandlersToo() throws Exception {
    Map<String, Integer> seen = new TreeMap<>();
    try (TypedJafarParser parser = open(TCK, 1, seen)) {
      parser.run();
      seen.clear();
      parser.run();
    }
    assertEquals(expected, seen);
  }

  /**
   * While chunk 1 is being parsed, another parser reads a recording that is chunk 2 on its own,
   * with the same generated classes: {@code java.lang.Thread} has another id there.
   */
  @Test
  void aParseOfAnotherRecordingDoesNotChangeThisOne(@TempDir Path dir) throws Exception {
    byte[] all = Files.readAllBytes(TCK);
    int firstChunkSize = (int) ByteBuffer.wrap(all, 8, 8).getLong(); // chunk header: size at 8
    Path second = dir.resolve("chunk2.jfr");
    Files.write(second, Arrays.copyOfRange(all, firstChunkSize, all.length));

    Map<String, Integer> seen = new TreeMap<>();
    Map<String, Integer> other = new TreeMap<>();
    boolean[] nested = new boolean[1];
    try (TypedJafarParser parser = ParsingContext.create().withParallelism(1).newTypedParser(TCK)) {
      parser.handle(
          ActiveSettingEvent.class,
          (event, ctl) -> {
            if (!nested[0]) {
              nested[0] = true;
              try (TypedJafarParser inner = open(second, 1, other)) {
                inner.run();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
            record(seen, event, ctl);
          });
      parser.run();
    }
    assertEquals(22, other.values().stream().mapToInt(Integer::intValue).sum(), other.toString());
    assertEquals(expected, seen);
  }
}
