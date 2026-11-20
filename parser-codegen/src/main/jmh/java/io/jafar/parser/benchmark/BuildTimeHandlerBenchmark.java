package io.jafar.parser.benchmark;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.benchmark.events.*;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Typed parsing with the handlers {@code jafar-processor} generates at build time, against the
 * handlers {@code parser-codegen} generates at runtime, on the same interfaces.
 *
 * <p>{@link #decoder} selects the implementation for the parser each operation builds: {@code
 * buildTime} uses the factories {@code ServiceLoader} finds on this classpath (the annotation
 * processor generates one for every interface in {@code io.jafar.parser.benchmark.events}); {@code
 * runtime} calls {@link TypedJafarParser#ignoreDiscoveredFactories()} first, so the same interfaces
 * are decoded by runtime-generated classes. JMH runs each parameter value in forks of its own, so
 * the two implementations never share a JVM, its class loading or its JIT profile. The one
 * remaining difference outside the decoding is that the {@code runtime} parser registers the
 * discovered factories and then drops them, each operation.
 *
 * <p>Each operation parses the whole recording with a new parser: it includes creating the parser
 * and its chunk thread pool and reading metadata and constant pools, not only decoding events. On
 * {@code test-ap.jfr} (one chunk, 117 KB) an operation takes a few milliseconds, so per-parse costs
 * are a visible part of the result.
 *
 * <p>The trial teardown checks which class decoded the first {@code jdk.ExecutionSample} of the
 * measured operations and fails the run if it is not the implementation {@link #decoder} names, so
 * a result is never reported under the wrong label.
 *
 * <pre>
 * ./gradlew :parser-codegen:jmh -PjmhArgs="BuildTimeHandlerBenchmark -prof gc"
 * </pre>
 *
 * Pass no {@code -f}: it would override the forks of {@link #coldStartParse} too.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 5,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
public class BuildTimeHandlerBenchmark {

  /** {@code buildTime}: the generated handlers; {@code runtime}: runtime-generated classes. */
  @Param({"buildTime", "runtime"})
  public String decoder;

  private Path testFile;
  private boolean runtime;

  /**
   * Class of the first {@code jdk.ExecutionSample} decoded in this trial. Written on a chunk thread
   * of the first operation; read after {@code run()} has returned, which waits for those threads.
   */
  private Class<?> decodedBy;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    if (resource == null) {
      throw new IllegalStateException("test-ap.jfr not found in test resources");
    }
    testFile = Paths.get(resource.toURI());
    switch (decoder) {
      case "buildTime":
        runtime = false;
        break;
      case "runtime":
        runtime = true;
        break;
      default:
        throw new IllegalArgumentException("decoder must be buildTime or runtime: " + decoder);
    }
    // No parse here: coldStartParse must meet the JVM before any handler class is loaded.
  }

  @TearDown(Level.Trial)
  public void checkDecoder() {
    if (decodedBy == null) {
      throw new IllegalStateException("no jdk.ExecutionSample was decoded: nothing was measured");
    }
    boolean buildTime = decodedBy.getName().equals(JFRExecutionSample.class.getName() + "Handler");
    System.out.println(
        "decoder="
            + decoder
            + ": jdk.ExecutionSample was decoded by "
            + (buildTime ? "the build-time handler " : "a runtime-generated class ")
            + decodedBy.getName());
    if (buildTime == runtime) {
      throw new IllegalStateException(
          "decoder="
              + decoder
              + " measured "
              + decodedBy.getName()
              + ": the result is mislabelled");
    }
  }

  /** A parser for one operation, using the implementation {@link #decoder} names. */
  private TypedJafarParser newParser() {
    TypedJafarParser parser = ParsingContext.create().newTypedParser(testFile);
    if (runtime) {
      parser.ignoreDiscoveredFactories();
    }
    return parser;
  }

  private void saw(JFRExecutionSample event) {
    if (decodedBy == null) {
      decodedBy = event.getClass();
    }
  }

  /** Parses the recording and reads each event's thread and every frame's method and class. */
  @Benchmark
  public void parseAndReadReferences(Blackhole bh) throws Exception {
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong threadIdSum = new AtomicLong();
    AtomicInteger stackDepthSum = new AtomicInteger();

    try (TypedJafarParser parser = newParser()) {
      parser.handle(
          JFRExecutionSample.class,
          (event, ctl) -> {
            saw(event);
            JFRThread thread = event.sampledThread();
            JFRStackTrace stackTrace = event.stackTrace();

            if (thread != null) {
              bh.consume(thread.javaThreadId());
              threadIdSum.addAndGet(thread.javaThreadId());
              bh.consume(thread.javaName());
            }

            if (stackTrace != null && stackTrace.frames() != null) {
              stackDepthSum.addAndGet(stackTrace.frames().length);
              for (JFRStackFrame frame : stackTrace.frames()) {
                JFRMethod method = frame.method();
                if (method != null) {
                  bh.consume(method.name());
                  JFRClass clazz = method.type();
                  if (clazz != null) {
                    bh.consume(clazz.name());
                  }
                }
              }
            }

            eventCount.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(eventCount.get());
    bh.consume(threadIdSum.get());
    bh.consume(stackDepthSum.get());
  }

  /** Parses the recording and hands each event to the blackhole without reading it. */
  @Benchmark
  public void parseAndConsumeEvent(Blackhole bh) throws Exception {
    AtomicInteger eventCount = new AtomicInteger();

    try (TypedJafarParser parser = newParser()) {
      parser.handle(
          JFRExecutionSample.class,
          (event, ctl) -> {
            saw(event);
            bh.consume(event);
            eventCount.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(eventCount.get());
  }

  /**
   * The first parse in a new JVM: one operation per fork, no warmup, so it includes loading the
   * parser, generating (runtime) or loading (build time) the handler classes, and running them
   * interpreted. Many forks, because each gives one sample.
   */
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 0)
  @Measurement(iterations = 1)
  @Fork(
      value = 20,
      jvmArgs = {"-Xms2g", "-Xmx2g"})
  public void coldStartParse(Blackhole bh) throws Exception {
    AtomicInteger eventCount = new AtomicInteger();

    try (TypedJafarParser parser = newParser()) {
      parser.handle(
          JFRExecutionSample.class,
          (event, ctl) -> {
            saw(event);
            bh.consume(event.startTime());
            eventCount.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(eventCount.get());
  }

  public static void main(String[] args) throws Exception {
    org.openjdk.jmh.Main.main(args);
  }
}
