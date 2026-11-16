package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build-time handler factory decodes every chunk of a recording, every run of a parser, and is
 * not disturbed by another parse using the same generated classes.
 *
 * <p>{@code tck-test.jfr} has two chunks from two producers. Both hold {@code jdk.ActiveSetting}
 * events (380 and 22) whose {@code eventThread} is a constant pool reference, and {@code
 * java.lang.Thread} is type id 176 in chunk 1 and 22 in chunk 2. The typed parser used to bind
 * factories once per parser, to the first chunk that reached its metadata callback: the factory was
 * attached to that chunk's metadata classes only, and the generated handlers kept that chunk's pool
 * type ids in static fields. So
 *
 * <ul>
 *   <li>the events of every other chunk reached the handler as {@code null} (or, with {@code
 *       parser-codegen} on the classpath, were decoded by a run-time generated handler instead);
 *   <li>a second {@code run()} of the parser delivered only {@code null};
 *   <li>a parse of another recording rebound the static ids for every parse in the JVM, and
 *       references of the first parse then resolved against the wrong pool id.
 * </ul>
 *
 * <p>Each case compares what the handler sees, per chunk, with the untyped parser's view of the
 * same events. {@code GeneratedFactoryMultiChunkTest} pins the single parse on a synthetic
 * recording; this adds a real one, the second {@code run()} and the second recording.
 */
class GeneratedFactoryChunkBindingTest {
  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  private static ClassLoader loader;

  /** Chunk start and {@code eventThread.javaName} to the number of such events. */
  private static Map<String, Integer> reference;

  @BeforeAll
  static void compileAndReadReference() throws Exception {
    loader =
        compileAndLoad(
            source(
                "PThread",
                "@JfrType(\"java.lang.Thread\")\npublic interface PThread {\n  String javaName();\n}\n"),
            source(
                "PSetting",
                "@JfrType(\"jdk.ActiveSetting\")\npublic interface PSetting {\n"
                    + "  String name();\n  PThread eventThread();\n}\n"));
    Map<String, Integer> seen = new TreeMap<>();
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(TCK)) {
      parser.handle(
          (type, value, ctl) -> {
            if (type.getName().equals("jdk.ActiveSetting")) {
              Object thread = Values.resolvedDeep(value).get("eventThread");
              count(seen, ctl, "thread=" + ((Map<?, ?>) thread).get("javaName"));
            }
          });
      parser.run();
    }
    assertEquals(2, seen.size(), "two chunks, one thread each: " + seen);
    assertEquals(402, seen.values().stream().mapToInt(Integer::intValue).sum(), seen.toString());
    reference = seen;
  }

  private static void count(Map<String, Integer> seen, Control ctl, String what) {
    synchronized (seen) {
      seen.merge("chunk@" + ctl.chunkInfo().startTime() + " " + what, 1, Integer::sum);
    }
  }

  /** What a typed handler sees of one event; a null event or reference is spelled out. */
  private static void record(Map<String, Integer> seen, Object event, Control ctl) {
    if (event == null) {
      count(seen, ctl, "the event is null");
      return;
    }
    Object thread = call(event, "eventThread");
    count(seen, ctl, "thread=" + (thread == null ? null : call(thread, "javaName")));
  }

  private static TypedJafarParser open(Path jfr, ParsingContext context, Map<String, Integer> seen)
      throws Exception {
    TypedJafarParser parser = context.newTypedParser(jfr);
    registerAndHandle(parser, (event, ctl) -> record(seen, event, ctl));
    return parser;
  }

  @Test
  void everyChunkIsDecodedByTheFactory() throws Exception {
    Map<String, Integer> seen = new TreeMap<>();
    try (TypedJafarParser parser = open(TCK, ParsingContext.create().withParallelism(1), seen)) {
      parser.run();
    }
    assertEquals(reference, seen);
  }

  /** Whichever chunk reaches its metadata first, with the chunks parsed on two threads. */
  @Test
  void everyChunkIsDecodedWhenChunksAreParsedInParallel() throws Exception {
    for (int attempt = 1; attempt <= 20; attempt++) {
      Map<String, Integer> seen = new TreeMap<>();
      try (TypedJafarParser parser = open(TCK, ParsingContext.create().withParallelism(2), seen)) {
        parser.run();
      }
      assertEquals(reference, seen, "attempt " + attempt);
    }
  }

  @Test
  void aSecondRunOfTheParserDecodesTheSame() throws Exception {
    Map<String, Integer> seen = new TreeMap<>();
    try (TypedJafarParser parser = open(TCK, ParsingContext.create().withParallelism(1), seen)) {
      parser.run();
      assertEquals(reference, seen, "first run");
      seen.clear();
      parser.run();
      assertEquals(reference, seen, "second run");
    }
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
    ParsingContext sequential = ParsingContext.create().withParallelism(1);
    try (TypedJafarParser parser = sequential.newTypedParser(TCK)) {
      registerAndHandle(
          parser,
          (event, ctl) -> {
            if (!nested[0]) {
              nested[0] = true;
              try (TypedJafarParser inner = open(second, sequential, other)) {
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
    assertEquals(reference, seen);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void registerAndHandle(TypedJafarParser parser, JFRHandler<Object> handler)
      throws Exception {
    for (String name : new String[] {"PSetting", "PThread"}) {
      parser.registerFactory(
          (HandlerFactory)
              loader.loadClass("test." + name + "Factory").getConstructor().newInstance());
    }
    parser.handle((Class) loader.loadClass("test.PSetting"), (JFRHandler) handler);
  }
}
