package io.jafar.parser.benchmark;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark measuring untyped parser performance with Tier 1 optimizations (flyweight pattern).
 *
 * <p>This benchmark validates the allocation reduction and throughput improvements from using
 * FlyweightEventMap instead of HashMap for event representation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class UntypedParserBenchmark {

  private Path testFile;

  @Setup(Level.Trial)
  public void setup() {
    testFile = Paths.get("parser/src/test/resources/test-jfr.jfr").toAbsolutePath().normalize();
    if (!testFile.toFile().exists()) {
      throw new IllegalStateException("Test file not found: " + testFile);
    }
  }

  /**
   * Benchmark parsing with Tier 1 optimizations enabled (current implementation with
   * FlyweightEventMap).
   *
   * <p>Measures throughput (ops/s) and allocation rate when using flyweight pattern for event
   * representation.
   */
  @Benchmark
  public void parseUntyped_Tier1(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong totalFields = new AtomicLong();

    try (UntypedJafarParser parser = ctx.newUntypedParser(testFile)) {
      parser.handle(
          (MetadataClass type, Map<String, Object> event, Control ctl) -> {
            bh.consume(event.size());
            totalFields.addAndGet(event.size());
            eventCount.incrementAndGet();

            // Access some fields to ensure map is not optimized away
            for (Map.Entry<String, Object> entry : event.entrySet()) {
              bh.consume(entry.getKey());
              bh.consume(entry.getValue());
            }
          });
      parser.run();
    }

    bh.consume(eventCount.get());
    bh.consume(totalFields.get());
  }

  /**
   * Benchmark measuring allocation profile for untyped parsing.
   *
   * <p>Run with: {@code ./gradlew jmh -Pjmh.include=UntypedParserBenchmark.allocationProfile
   * -Pjmh.prof=gc}
   *
   * <p>Key metrics to watch:
   *
   * <ul>
   *   <li>gc.alloc.rate.norm - Allocation per operation (bytes/op)
   *   <li>gc.alloc.rate - Allocation rate (MB/s)
   *   <li>gc.count - GC invocations
   * </ul>
   *
   * <p>Expected: ~60-80% reduction in gc.alloc.rate.norm compared to baseline HashMap
   * implementation.
   */
  @Benchmark
  public void allocationProfile(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (UntypedJafarParser parser = ctx.newUntypedParser(testFile)) {
      parser.handle(
          (MetadataClass type, Map<String, Object> event, Control ctl) -> {
            bh.consume(event);
            eventCount.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(eventCount.get());
  }

  /**
   * Benchmark measuring throughput with minimal field access.
   *
   * <p>Tests scenario where handler only accesses a few fields from the event map. With flyweight
   * pattern, all fields are eagerly deserialized (no benefit from lazy access), so this represents
   * the worst case.
   */
  @Benchmark
  public void sparseFieldAccess(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (UntypedJafarParser parser = ctx.newUntypedParser(testFile)) {
      parser.handle(
          (MetadataClass type, Map<String, Object> event, Control ctl) -> {
            // Only access 2 fields regardless of event size
            bh.consume(event.get("startTime"));
            bh.consume(event.get("duration"));
            eventCount.incrementAndGet();
          });
      parser.run();
    }

    bh.consume(eventCount.get());
  }

  /**
   * Benchmark measuring parser throughput with event filtering.
   *
   * <p>Tests common use case where only specific event types are processed. Validates that
   * flyweight optimization doesn't add overhead for filtered events.
   */
  @Benchmark
  public void parseWithFiltering(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicInteger processedCount = new AtomicInteger();

    try (UntypedJafarParser parser = ctx.newUntypedParser(testFile)) {
      parser.handle(
          (MetadataClass type, Map<String, Object> event, Control ctl) -> {
            eventCount.incrementAndGet();
            // Only process specific event types (simulating real-world filtering)
            if (type.getName().contains("jdk.")) {
              for (Object value : event.values()) {
                bh.consume(value);
              }
              processedCount.incrementAndGet();
            }
          });
      parser.run();
    }

    bh.consume(eventCount.get());
    bh.consume(processedCount.get());
  }

  /**
   * Simple parse benchmark that just counts events without accessing fields.
   *
   * <p>Measures pure parsing overhead including deserialization. This isolates the cost of creating
   * FlyweightEventMap instances vs HashMap instances.
   */
  @Benchmark
  public void parseCountOnly(Blackhole bh) throws IOException {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (UntypedJafarParser parser = ctx.newUntypedParser(testFile)) {
      parser.handle(
          (MetadataClass type, Map<String, Object> event, Control ctl) -> {
            eventCount.incrementAndGet();
          });
      parser.run();
    } catch (Exception e) {
      throw new IOException(e);
    }

    bh.consume(eventCount.get());
  }
}
