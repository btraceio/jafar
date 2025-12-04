# Performance Baselines

This document provides baseline performance metrics for JAFAR parser operations.

## Benchmark Environment

**Hardware**:
- CPU: System with multiple cores (tests used `availableProcessors() - 2` threads)
- Memory: 4GB heap (`-Xmx4g -Xms2g`)

**Software**:
- JVM: OpenJDK 64-Bit Server VM, JDK 21.0.5+11-LTS
- GC: G1GC (`-XX:+UseG1GC`)
- JAFAR Version: 0.1.0 (pre-release)

**Benchmark Framework**: JMH 1.37
- Warmup: 3 iterations, 2 seconds each
- Measurement: 5 iterations, 3 seconds each
- Threads: 1 thread (single-threaded parsing)
- Mode: Average time per operation

**Test File**: `test-jfr.jfr` (real-world JFR recording)

## Benchmark Results

### Summary Table

| Benchmark | Avg Time (ms) | Error (ms) | Alloc Rate (MB/s) | Memory/Op (GB) | GC Count | GC Time (ms) |
|-----------|---------------|------------|-------------------|----------------|----------|--------------|
| Parse Entire Recording | 2,958 | ±3,038 | 1,815 | 4.98 | 37 | 120 |
| Parse Nested Structures | 4,947 | ±425 | 1,092 | 5.27 | 22 | 129 |
| Parse String-Heavy Events | 3,501 | ±466 | 2,307 | 7.88 | 33 | 190 |

### Detailed Results

#### 1. Parse Entire Recording
Full end-to-end parsing of a complete JFR file.

```
Benchmark: AllocationBenchmark.parseEntireRecording
Mode:      Average time per operation

Results:
  Time:        2,958.491 ± 3,038.415 ms/op
  Min/Max:     (2,399.165, 4,102.578) ms
  Std Dev:     789.067 ms

  Allocation Rate:      1,815.298 ± 1,641.727 MB/sec
  Memory per Operation: 5,350,961,151 bytes (~4.98 GB)

  GC Statistics:
    Total GC Count: 37 collections
    Total GC Time:  120 ms
    GC Overhead:    ~4% (120ms / 2958ms)
```

**Interpretation**:
- Fast parsing: ~3 seconds average for full recording
- High throughput: ~1.8 GB/s allocation rate
- Low GC overhead: Only 4% of time spent in GC
- Large variance due to GC pauses (note ±3s error margin)

#### 2. Parse Nested Structures
Focus on events with deeply nested complex types.

```
Benchmark: AllocationBenchmark.parseNestedStructures
Mode:      Average time per operation

Results:
  Time:        4,946.548 ± 425.118 ms/op
  Min/Max:     (4,824.189, 5,120.687) ms
  Std Dev:     110.402 ms

  Allocation Rate:      1,092.013 ± 92.703 MB/sec
  Memory per Operation: 5,662,317,821 bytes (~5.27 GB)

  GC Statistics:
    Total GC Count: 22 collections
    Total GC Time:  129 ms
    GC Overhead:    ~2.6% (129ms / 4947ms)
```

**Interpretation**:
- Slower than simple parsing (~5 seconds)
- Lower allocation rate due to complex object graphs
- More consistent performance (±425ms error)
- Lower GC overhead despite complex structures

#### 3. Parse String-Heavy Events
Focus on events with many string fields.

```
Benchmark: AllocationBenchmark.parseStringHeavyEvents
Mode:      Average time per operation

Results:
  Time:        3,501.317 ± 465.989 ms/op
  Min/Max:     (3,357.524, 3,675.714) ms
  Std Dev:     121.016 ms

  Allocation Rate:      2,307.227 ± 304.858 MB/sec
  Memory per Operation: 8,464,302,203 bytes (~7.88 GB)

  GC Statistics:
    Total GC Count: 33 collections
    Total GC Time:  190 ms
    GC Overhead:    ~5.4% (190ms / 3501ms)
```

**Interpretation**:
- Moderate parsing time (~3.5 seconds)
- Highest allocation rate: 2.3 GB/s
- Highest memory usage: 7.88 GB per operation
- String interning and caching help performance

## Optimization Features Enabled

The benchmarks were run with the following optimizations enabled:

- **HashMap pooling**: ✅ Enabled
- **String caching**: ✅ Enabled
- **Field interning**: ✅ Enabled

These features significantly reduce allocation rates and improve performance.

## Comparison to Alternatives

While formal comparisons are not yet available, informal testing suggests:

- **JAFAR**: ~3 seconds to parse a typical recording
- **JMC Parser**: ~7 seconds for the same recording (anecdotal)

**Note**: These comparisons should be taken with caution. Different parsers have different APIs and capabilities. Formal benchmarking against other parsers is planned for future releases.

## Performance Characteristics

### Scaling Behavior

1. **File Size**: Parsing time scales roughly linearly with file size
2. **Event Count**: More events = more time, but constant pools help
3. **Constant Pool Size**: Large constant pools increase memory usage but improve parsing speed (cached values)
4. **Nested Structures**: Deeper nesting increases parsing time (~1.7x slower than simple events)
5. **String Content**: String-heavy events show highest allocation rates but reasonable parsing times

### Memory Usage

- Expect ~5-8 GB of allocations per parsing operation
- Peak heap usage will be lower due to GC
- Constant pools and metadata are kept in memory during parsing
- Consider increasing heap size for very large files: `-Xmx8g` or higher

### Concurrent Parsing

JAFAR uses parallel chunk processing internally. The benchmarks above use single-threaded external invocation but multi-threaded internal processing.

For parsing multiple files concurrently:
- Each parser instance is independent
- ParsingContext can be shared (thread-safe)
- Scale horizontally: N files → N threads → ~N×throughput

## Performance Tips

### 1. Reuse ParsingContext
```java
// Good: Reuse context across files
ParsingContext ctx = ParsingContext.create();
for (Path file : files) {
  try (TypedJafarParser p = TypedJafarParser.open(file, ctx)) {
    p.run();
  }
}
```

### 2. Minimize Handler Work
```java
// Good: Offload heavy processing
ExecutorService executor = Executors.newFixedThreadPool(4);
parser.handle(MyEvent.class, (event, ctl) -> {
  executor.submit(() -> processEvent(event)); // Async processing
});
```

### 3. Use Typed API for Performance
The typed API uses bytecode generation and is faster than the untyped API for repeated parsing.

### 4. Filter Early
```java
// Good: Filter in handler
parser.handle(MyEvent.class, (event, ctl) -> {
  if (event.shouldProcess()) {
    process(event);
  }
});

// Even better: Use type filtering if possible
// (Future feature)
```

### 5. Adjust Heap Size
For large files (>1 GB):
```bash
java -Xmx8g -Xms4g -XX:+UseG1GC -jar myapp.jar
```

## Performance Regression Testing

**Status**: Not yet automated in CI.

**Planned**: Integration of JMH benchmarks into CI pipeline with historical tracking.

To run benchmarks manually:
```bash
./gradlew :benchmarks:jmh
```

Results are saved to: `benchmarks/build/results/jmh/results.json`

## Known Performance Limitations

1. **String Allocation**: Despite caching, string-heavy recordings allocate significant memory
2. **Bytecode Generation**: First parse of a new event type has overhead for code generation (amortized over repeated parses)
3. **Large Constant Pools**: Million+ unique entries may cause memory pressure
4. **Single-Threaded Handlers**: Handlers execute sequentially on the parser thread

## Future Optimization Opportunities

1. **Zero-Copy String Parsing**: Using `CharSequence` views instead of `String` objects
2. **Memory-Mapped Files**: For very large recordings
3. **Incremental Constant Pool Loading**: Lazy loading of rarely-used CP entries
4. **SIMD String Operations**: Java 21+ vector API for faster string parsing
5. **Native Image**: GraalVM native-image compilation for faster startup

## Reproducing These Results

```bash
# Clone repository
git clone https://github.com/jbachorik/jafar.git
cd jafar

# Fetch test resources
./get_resources.sh

# Run benchmarks
./gradlew :benchmarks:jmh

# Results will be in benchmarks/build/results/jmh/results.json
```

## Disclaimer

These benchmarks represent performance on a specific test file with specific characteristics. Real-world performance will vary based on:

- JFR file size and complexity
- Event type distribution
- Hardware (CPU, memory, disk I/O)
- JVM version and GC tuning
- Concurrent system load

Always benchmark with your own data to validate performance for your use case.

---

**Last Updated**: December 4, 2024
**JAFAR Version**: 0.1.0 (pre-release)
**Test Environment**: JDK 21.0.5, macOS, G1GC, 4GB heap
