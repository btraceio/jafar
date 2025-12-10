package io.jafar.parser.benchmark;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.impl.EventStreamBaseline;
import io.jafar.parser.impl.EventStreamGenerated;
import io.jafar.parser.impl.EventStreamLazy;
import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.internal_api.StreamingChunkParser;
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

  // ============================================================================
  // BASELINE BENCHMARKS (without flyweight optimization, using standard HashMap)
  // ============================================================================

  /**
   * Baseline benchmark using standard HashMap without flyweight optimization.
   *
   * <p>This benchmark measures performance with the original HashMap-based approach, allocating new
   * HashMap instances and HashMap.Node entries for each event. Use this as a comparison baseline to
   * measure the impact of the flyweight optimization.
   *
   * <p>Expected to show higher allocation rates and lower throughput compared to Tier1.
   */
  @Benchmark
  public void parseUntyped_Baseline(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong totalFields = new AtomicLong();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamBaseline listener =
          new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event.size());
              totalFields.addAndGet(event.size());
              eventCount.incrementAndGet();

              // Access fields to ensure map is not optimized away
              for (Map.Entry<String, Object> entry : event.entrySet()) {
                bh.consume(entry.getKey());
                bh.consume(entry.getValue());
              }
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
    bh.consume(totalFields.get());
  }

  /**
   * Baseline allocation profile benchmark.
   *
   * <p>Measures allocation rates with standard HashMap approach. Compare with {@link
   * #allocationProfile()} to quantify memory savings from flyweight pattern.
   *
   * <p>Run with: {@code ./gradlew jmh
   * -Pjmh.include=UntypedParserBenchmark.allocationProfile_Baseline -Pjmh.prof=gc}
   */
  @Benchmark
  public void allocationProfile_Baseline(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamBaseline listener =
          new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event);
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Baseline benchmark for count-only workload.
   *
   * <p>Measures pure parsing overhead with HashMap allocation. Even when not accessing fields, the
   * HashMap.Node allocations still occur.
   */
  @Benchmark
  public void parseCountOnly_Baseline(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamBaseline listener =
          new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  // ============================================================================
  // TIER 2 BENCHMARKS (Lazy Deserialization)
  // ============================================================================

  /**
   * Tier 2 benchmark with lazy deserialization and full field access.
   *
   * <p>This benchmark accesses all fields from the lazy event map, which triggers materialization.
   * Compare with {@link #parseUntyped_Baseline(Blackhole)} to measure overhead of lazy approach.
   *
   * <p>Expected: ~10% overhead compared to baseline (due to array + lazy materialization).
   */
  @Benchmark
  public void parseUntyped_Tier2Lazy(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong totalFields = new AtomicLong();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamLazy listener =
          new EventStreamLazy(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event.size());
              totalFields.addAndGet(event.size());
              eventCount.incrementAndGet();

              // Access all fields to ensure map is not optimized away
              for (Map.Entry<String, Object> entry : event.entrySet()) {
                bh.consume(entry.getKey());
                bh.consume(entry.getValue());
              }
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
    bh.consume(totalFields.get());
  }

  /**
   * Tier 2 benchmark with sparse field access (the sweet spot for lazy deserialization).
   *
   * <p>This benchmark only accesses 2 fields per event, which is a common pattern in real-world JFR
   * analysis (filtering by timestamp, event type, etc.). The lazy map avoids materializing the full
   * HashMap.
   *
   * <p>Expected: 70-80% allocation reduction compared to baseline.
   */
  @Benchmark
  public void sparseFieldAccess_Tier2Lazy(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamLazy listener =
          new EventStreamLazy(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              // Only access 2 fields - this should avoid HashMap materialization
              bh.consume(event.get("startTime"));
              bh.consume(event.get("duration"));
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Tier 2 count-only benchmark.
   *
   * <p>Measures pure parsing overhead with lazy map. Since fields are never accessed, the lazy map
   * provides maximum benefit.
   *
   * <p>Expected: Significant allocation reduction compared to baseline (no HashMap.Node allocations
   * at all).
   */
  @Benchmark
  public void parseCountOnly_Tier2Lazy(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamLazy listener =
          new EventStreamLazy(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Tier 2 allocation profile benchmark.
   *
   * <p>Run with: {@code ./gradlew jmh
   * -Pjmh.include=UntypedParserBenchmark.allocationProfile_Tier2Lazy -Pjmh.prof=gc}
   *
   * <p>Compare with {@link #allocationProfile_Baseline(Blackhole)} to quantify memory savings.
   */
  @Benchmark
  public void allocationProfile_Tier2Lazy(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamLazy listener =
          new EventStreamLazy(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event);
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Tier 2 benchmark with event filtering.
   *
   * <p>Tests lazy deserialization with filtering pattern. Only events matching the filter have
   * their fields accessed.
   */
  @Benchmark
  public void parseWithFiltering_Tier2Lazy(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicInteger processedCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamLazy listener =
          new EventStreamLazy(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              eventCount.incrementAndGet();
              // Only process specific event types
              if (type.getName().contains("jdk.")) {
                for (Object value : event.values()) {
                  bh.consume(value);
                }
                processedCount.incrementAndGet();
              }
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
    bh.consume(processedCount.get());
  }

  // ============================================================================
  // TIER 3 BENCHMARKS (Bytecode Generation)
  // ============================================================================

  /**
   * Tier 3 benchmark with bytecode-generated deserializers and full field access.
   *
   * <p>This benchmark uses runtime bytecode generation to eliminate ValueProcessor callback
   * overhead. Generated deserializers implement a hybrid strategy:
   *
   * <ul>
   *   <li>Simple events (≤10 fields): Direct HashMap deserialization
   *   <li>Complex events (>10 fields): Lazy ArrayPool + LazyEventMap
   * </ul>
   *
   * <p>Expected: 15-25% improvement over Tier 2 Lazy for full field access due to eliminated
   * callback overhead and optimized deserialization paths.
   */
  @Benchmark
  public void parseUntyped_Tier3Generated(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong totalFields = new AtomicLong();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamGenerated listener =
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event.size());
              totalFields.addAndGet(event.size());
              eventCount.incrementAndGet();

              // Access all fields to ensure map is not optimized away
              for (Map.Entry<String, Object> entry : event.entrySet()) {
                bh.consume(entry.getKey());
                bh.consume(entry.getValue());
              }
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
    bh.consume(totalFields.get());
  }

  /**
   * Tier 3 benchmark with FULL_ITERATION strategy for full field access workloads.
   *
   * <p>This benchmark uses {@link UntypedStrategy#FULL_ITERATION} which always generates eager
   * HashMap deserializers, eliminating LazyEventMap materialization overhead when iterating all
   * fields.
   *
   * <p>Key differences from default Tier3 (SPARSE_ACCESS):
   *
   * <ul>
   *   <li>ALL events use eager HashMap (no LazyEventMap for complex events)
   *   <li>No materialization overhead on entrySet() calls
   *   <li>Higher initial allocation, but faster for full iteration
   * </ul>
   *
   * <p>Expected: Match or beat Baseline (~0.514 ops/s) for full field iteration, eliminating the
   * -6.4% regression observed with default SPARSE_ACCESS strategy.
   *
   * <p>Use case: Bulk JFR-to-DuckDB conversion, full data export, analytics where ALL fields are
   * accessed.
   */
  @Benchmark
  public void parseUntyped_Tier3FullIteration(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicLong totalFields = new AtomicLong();

    // Use FULL_ITERATION strategy
    try (StreamingChunkParser parser =
        new StreamingChunkParser(
            ((ParsingContextImpl) ctx).untypedContextFactory(UntypedStrategy.FULL_ITERATION))) {
      EventStreamGenerated listener =
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event.size());
              totalFields.addAndGet(event.size());
              eventCount.incrementAndGet();

              // Full field iteration - this is the use case FULL_ITERATION optimizes for
              for (Map.Entry<String, Object> entry : event.entrySet()) {
                bh.consume(entry.getKey());
                bh.consume(entry.getValue());
              }
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
    bh.consume(totalFields.get());
  }

  /**
   * Tier 3 benchmark with sparse field access (sweet spot for bytecode generation).
   *
   * <p>This benchmark only accesses 2 fields per event. With bytecode generation, simple events use
   * eager HashMap (minimal overhead), while complex events use lazy maps (avoid full
   * materialization).
   *
   * <p>Expected: 60-70% allocation reduction compared to baseline, with 10-20% better throughput
   * than Tier 2 due to eliminated callback overhead.
   */
  @Benchmark
  public void sparseFieldAccess_Tier3Generated(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamGenerated listener =
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              // Only access 2 fields
              bh.consume(event.get("startTime"));
              bh.consume(event.get("duration"));
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Tier 3 count-only benchmark.
   *
   * <p>Measures pure parsing overhead with bytecode-generated deserializers. Since fields are never
   * accessed, lazy maps provide maximum benefit for complex events, while simple events have
   * minimal HashMap overhead.
   *
   * <p>Expected: Significant allocation reduction compared to baseline, comparable to Tier 2 but
   * with slightly better throughput due to eliminated callback overhead.
   */
  @Benchmark
  public void parseCountOnly_Tier3Generated(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamGenerated listener =
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }

  /**
   * Tier 3 allocation profile benchmark.
   *
   * <p>Run with: {@code ./gradlew jmh
   * -Pjmh.include=UntypedParserBenchmark.allocationProfile_Tier3Generated -Pjmh.prof=gc}
   *
   * <p>Compare with {@link #allocationProfile_Baseline(Blackhole)} and {@link
   * #allocationProfile_Tier2Lazy(Blackhole)} to quantify memory savings from bytecode generation
   * optimization.
   *
   * <p>Expected: Allocation rates between Baseline and Tier 2, with best throughput due to
   * eliminated callback overhead and optimized code paths.
   */
  @Benchmark
  public void allocationProfile_Tier3Generated(Blackhole bh) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();

    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      EventStreamGenerated listener =
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> event, Control ctl) {
              bh.consume(event);
              eventCount.incrementAndGet();
            }
          };
      parser.parse(testFile, listener);
    }

    bh.consume(eventCount.get());
  }
}
