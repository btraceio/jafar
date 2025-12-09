package io.jafar.parser.benchmark;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.GlobalHandlerCache;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark to measure JIT compilation benefits from handler class reuse.
 *
 * <p>This benchmark validates the hypothesis that reusing handler classes across parsing sessions
 * allows the JVM to accumulate JIT optimizations, resulting in improved throughput after warm-up.
 *
 * <p>To run manually: java -cp parser/build/libs/*:parser/build/classes/java/test \
 * io.jafar.parser.benchmark.HandlerReuseJITBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgs = {"-Xms2g", "-Xmx2g"})
public class HandlerReuseJITBenchmark {

  private Path testFile;

  // Handler interfaces for test events
  @JfrType("test.ByteEvent")
  public interface ByteEvent {
    @JfrField("value")
    byte value();
  }

  @Setup(Level.Trial)
  public void setup() throws Exception {
    // Use test-jfr.jfr from resources
    URL resource = getClass().getClassLoader().getResource("test-jfr.jfr");
    if (resource == null) {
      throw new IllegalStateException("test-jfr.jfr not found in test resources");
    }
    testFile = Paths.get(resource.toURI());

    // Warm up file system caching
    try (TypedJafarParser parser = TypedJafarParser.open(testFile)) {
      parser.run();
    }

    // Clear global cache before benchmark
    GlobalHandlerCache.getInstance().clear();
  }

  @TearDown(Level.Iteration)
  public void printCacheStats() {
    System.out.printf(
        "Cache: size=%d, hits=%d, misses=%d, hit_rate=%.1f%%%n",
        GlobalHandlerCache.getInstance().size(),
        GlobalHandlerCache.getInstance().getTotalHits(),
        GlobalHandlerCache.getInstance().getTotalMisses(),
        GlobalHandlerCache.getInstance().getHitRate());
  }

  /**
   * Parse with handler reuse via global cache.
   *
   * <p>Creates fresh ParsingContext each iteration, but handlers are reused via global cache,
   * allowing JIT optimizations to accumulate across iterations.
   */
  @Benchmark
  public void parseWithReuse(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger count = new AtomicInteger();

    try (TypedJafarParser parser = ctx.newTypedParser(testFile)) {
      parser.handle(
          ByteEvent.class,
          (event, ctl) -> {
            bh.consume(event.value());
            count.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(count.get());
  }

  public static void main(String[] args) throws Exception {
    org.openjdk.jmh.Main.main(args);
  }
}
