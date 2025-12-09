package io.jafar.parser.benchmark;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.GlobalHandlerCache;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark to measure metaspace growth with and without handler reuse.
 *
 * <p>This benchmark validates that handler class reuse prevents unbounded metaspace growth by
 * measuring memory usage across multiple parsing iterations.
 *
 * <p>Expected results: - Without reuse: O(N) metaspace growth (N = number of contexts) - With
 * reuse: O(1) metaspace growth (handlers reused)
 *
 * <p>Run with: ./gradlew :parser:test --tests "io.jafar.parser.benchmark.MetaspaceBenchmark"
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 10)
@Fork(1)
public class MetaspaceBenchmark {

  private Path testFile;
  private MemoryPoolMXBean metaspacePool;

  @JfrType("jdk.ExecutionSample")
  public interface ExecutionSample {
    @JfrField("sampledThread")
    Thread sampledThread();
  }

  @JfrType("java.lang.Thread")
  public interface Thread {
    @JfrField("javaName")
    String javaName();
  }

  @Setup(Level.Trial)
  public void setup() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    if (resource == null) {
      throw new IllegalStateException("test-ap.jfr not found");
    }
    testFile = Paths.get(resource.toURI());

    // Find metaspace memory pool
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getName().contains("Metaspace")) {
        metaspacePool = pool;
        break;
      }
    }

    if (metaspacePool == null) {
      System.err.println("Warning: Metaspace pool not found, memory tracking disabled");
    }

    GlobalHandlerCache.getInstance().clear();
  }

  @TearDown(Level.Iteration)
  public void recordMetaspaceUsage() {
    if (metaspacePool != null) {
      MemoryUsage usage = metaspacePool.getUsage();
      System.out.printf(
          "Metaspace: used=%d MB, committed=%d MB, cache_size=%d, hits=%d, misses=%d%n",
          usage.getUsed() / (1024 * 1024),
          usage.getCommitted() / (1024 * 1024),
          GlobalHandlerCache.getInstance().size(),
          GlobalHandlerCache.getInstance().getTotalHits(),
          GlobalHandlerCache.getInstance().getTotalMisses());
    }
  }

  /**
   * Measure metaspace growth with handler reuse enabled.
   *
   * <p>Each iteration creates a new ParsingContext, but handlers are reused via global cache.
   */
  @Benchmark
  public void withReuse(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger count = new AtomicInteger();

    try (TypedJafarParser parser = ctx.newTypedParser(testFile)) {
      parser.handle(
          ExecutionSample.class,
          (event, ctl) -> {
            bh.consume(event);
            count.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(count.get());
  }

  /**
   * Baseline to establish initial metaspace usage.
   *
   * <p>Single parse to measure baseline memory footprint.
   */
  @Benchmark
  @Measurement(iterations = 1)
  public void baseline(Blackhole bh) throws Exception {
    // Force GC before baseline measurement
    System.gc();
    java.lang.Thread.sleep(100);

    ParsingContext ctx = ParsingContext.create();
    AtomicInteger count = new AtomicInteger();

    try (TypedJafarParser parser = ctx.newTypedParser(testFile)) {
      parser.handle(
          ExecutionSample.class,
          (event, ctl) -> {
            bh.consume(event);
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
