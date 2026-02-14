# Jafar Demo - Java Flight Recorder Analysis

This directory contains comprehensive demonstrations of the Jafar JFR parser, showcasing both the programmatic API and the interactive jfr-shell tool.

## What's Included

### 1. **DatadogProfilerDemo.java** - Untyped API Showcase
A complete Java application demonstrating Jafar's **Untyped API** for flexible JFR analysis without pre-generated types.

**Features:**
- ✅ **Single-pass analysis** - processes all events in one go (50% faster!)
- ✅ CPU hot spot analysis from profiling samples
- ✅ Thread activity and state distribution
- ✅ Exception pattern identification (extracts specific exception types)
- ✅ System resource metrics (CPU, heap)
- ✅ Endpoint performance tracking
- ✅ Event distribution overview

**Run:**
```bash
java -cp build/libs/jafar-demo-all.jar \
  io.jafar.demo.DatadogProfilerDemoUntyped \
  /path/to/recording.jfr
```

### 2. **TypedDemo.java** - Typed API Showcase
A complete Java application demonstrating Jafar's **Typed API** using generated type-safe interfaces.

**Features:**
- ✅ **Type-safe access** with compile-time checking
- ✅ **IDE autocomplete** for all event fields
- ✅ **Better performance** with direct field access
- ✅ Single-pass analysis for standard JDK events
- ✅ CPU hot spot analysis
- ✅ Thread activity patterns
- ✅ System resource and heap metrics

**Run:**
```bash
java -cp build/libs/jafar-demo-all.jar \
  io.jafar.demo.TypedDemo \
  /path/to/recording.jfr
```

**Note:** TypedDemo works with standard JDK JFR events. Use DatadogProfilerDemo for Datadog profiler recordings.

### 3. **JFR_SHELL_DEMO.md** - Interactive Analysis Guide
Step-by-step guide for using jfr-shell to interactively explore JFR recordings.

**Covers:**
- Quick overview commands
- CPU hot spot analysis
- Thread activity patterns
- Exception analysis
- Resource metrics
- Advanced filtering
- Data export
- Interactive exploration

### 4. **Main.java** - API Comparison Benchmark
Compares performance of different JFR parsers: Jafar, JMC, JDK RecordingFile, and EventStream.

## Quick Start

### Build the Demo

```bash
cd /Users/jaroslav.bachorik/opensource/jafar/demo
../gradlew build
```

### Run the Datadog Profiler Demo

```bash
java -cp build/libs/jafar-demo-all.jar \
  io.jafar.demo.DatadogProfilerDemoUntyped \
  /Users/jaroslav.bachorik/demo.jfr
```

**Sample Output:**
```
================================================================================
Datadog Profiler Analysis - Jafar Untyped API Demo
================================================================================
Analyzing: /Users/jaroslav.bachorik/demo.jfr

[1] CPU HOT SPOTS - Top Methods by Sample Count
--------------------------------------------------------------------------------
Top 15 methods by sample count:
    8509 samples: {string=}.{string=poll}
    2127 samples: {string=}.{string=unknown}
    1355 samples: {string=}.{string=itable stub}
    ...

[2] THREAD ACTIVITY - Sample Distribution
--------------------------------------------------------------------------------
Top 10 most active threads:
    1075 samples: qtp2058533447-56
    1009 samples: qtp2058533447-74
    ...

Total parsing time: 3266ms
```

### Use JFR Shell

```bash
# Build jfr-shell
cd /Users/jaroslav.bachorik/opensource/jafar
./gradlew :jfr-shell:jlinkZip

# Run a quick analysis
jfr-shell/build/jlink/bin/jfr-shell show demo.jfr \
  "events/datadog.ExecutionSample | count()"

# Start interactive mode
jfr-shell/build/jlink/bin/jfr-shell -f demo.jfr
```

See **JFR_SHELL_DEMO.md** for complete interactive examples.

## About the Demo Recording

The `demo.jfr` file is a Datadog profiler recording from a production Java application, containing:

- **14,229** execution samples (CPU profiling)
- **13,125** method samples
- **11,129** endpoint events
- **9,383** exception samples
- **60+** different JDK and Datadog event types
- **Total:** 61,759 events

This rich dataset makes it perfect for demonstrating real-world JFR analysis scenarios.

## API Comparison

### Typed API - Type-Safe Analysis
**Best for:** Production code, known event types, IDE support needed

```java
try (TypedJafarParser parser = context.newTypedParser(jfrFile)) {
    parser.handle(JFRExecutionSample.class, (event, ctl) -> {
        // Type-safe access with autocomplete
        JFRStackTrace stackTrace = event.stackTrace();
        JFRThread thread = event.sampledThread();
        String threadName = thread.osName();  // IDE autocomplete!

        // Direct field access - best performance
        if (stackTrace.frames() != null && stackTrace.frames().length > 0) {
            JFRMethod method = stackTrace.frames()[0].method();
            String methodName = method.name();
        }
    });
    parser.run();
}
```

**Pros:**
- ✅ Type safety with compile-time checking
- ✅ IDE autocomplete for all fields
- ✅ Best performance (direct access)
- ✅ Cleaner, more readable code
- ✅ Refactoring support

**Cons:**
- ❌ Requires type generation step (`generateJafarTypes`)
- ❌ Less flexible for unknown event types

### Untyped API - Dynamic Analysis
**Best for:** Ad-hoc analysis, custom events, exploratory work

```java
try (UntypedJafarParser parser = context.newUntypedParser(jfrFile)) {
    parser.handle((type, event, ctl) -> {
        // Dynamic event filtering
        if ("datadog.ExecutionSample".equals(type.getName())) {
            // Navigate nested structures with Values.get()
            Object threadName = Values.get(event, "sampledThread", "osName");
            Object method = Values.get(event, "stackTrace", "frames", 0, "method", "name", "string");

            // Works with any event type - no pre-generation needed!
            String className = String.valueOf(Values.get(event, "stackTrace", "frames", 0, "method", "type", "name", "string"));
        }
    });
    parser.run();
}
```

**Pros:**
- ✅ No type generation needed
- ✅ Works with ANY JFR file (custom, Datadog, etc.)
- ✅ Easy navigation with Values.get()
- ✅ Perfect for exploration and prototyping

**Cons:**
- ❌ No compile-time type checking
- ❌ No IDE autocomplete
- ❌ Slightly more verbose

### When to Use Which?

| Use Case | Recommended API | Why |
|----------|----------------|-----|
| Production monitoring | **Typed** | Type safety, performance, maintainability |
| Custom event types (Datadog, etc.) | **Untyped** | No type generation needed |
| Ad-hoc JFR analysis | **Untyped** | Quick and flexible |
| Building analysis tools | **Typed** | Better IDE support and refactoring |
| Exploratory analysis | **Untyped** | Works with any recording |
| Known JDK events | **Typed** | Best performance and type safety |

## Key Features Demonstrated

### 1. Performance
- **Fast parsing:** The demo.jfr file (43MB, 61K events) parses in ~3.3 seconds
- **Efficient memory:** Streaming architecture for large files
- **Reusable context:** ParsingContext can be shared across multiple parsings

### 2. Flexibility
- Works with any JFR recording (JDK, custom, Datadog, etc.)
- No dependency on JFR metadata at compile time
- Easy navigation of nested structures

### 3. Simplicity
- Clean, minimal API
- No complex setup or configuration
- Easy integration into existing tools

## Use Cases

### Development & Debugging
- Profile application performance
- Identify CPU/memory hotspots
- Track down exception sources
- Analyze thread behavior

### Production Monitoring
- Parse profiling data from production
- Generate performance reports
- Track resource usage trends
- Identify bottlenecks

### Research & Tooling
- Build custom JFR analysis tools
- Create visualization dashboards
- Automate performance regression detection
- Extract metrics for monitoring systems

## Performance Comparison

From the Main.java benchmark (when available):

```
Parser Comparison on demo.jfr:
- Jafar (typed):    ~X.Xms
- Jafar (untyped):  ~X.Xms
- JMC:              ~X.Xms
- JDK RecordingFile: ~X.Xms
- JDK EventStream:  ~X.Xms
```

## Architecture Highlights

### Streaming Parser
- Processes JFR chunks as they're read
- Minimal memory footprint
- Parallel event processing

### Values Helper
- Simplified nested field navigation
- Automatic type unwrapping
- Null-safe access

### Parsing Context
- Reusable across multiple files
- Caches generated bytecode
- Thread-safe

## Extending the Demos

### Add Your Own Analysis

```java
try (UntypedJafarParser parser = ctx.newUntypedParser(jfrFile)) {
    Map<String, AtomicLong> customMetric = new HashMap<>();

    parser.handle((type, event, ctl) -> {
        if ("your.custom.Event".equals(type.getName())) {
            Object value = Values.get(event, "field", "nested");
            // Your analysis logic here
        }
    });

    parser.run();
}
```

### Create JFR-Shell Queries

```bash
# Your custom analysis
jfr-shell show recording.jfr \
  "events/your.Event | groupBy(field) | top(10, by=count)"
```

## Documentation

- **API Docs:** See JavaDoc in source files
- **JFR Shell:** See [JFR_SHELL_DEMO.md](JFR_SHELL_DEMO.md)
- **JfrPath Reference:** See `../doc/cli/JFRPath.md`
- **Project README:** See `../README.md`

## Troubleshooting

### "Cannot find symbol" errors
Make sure types are generated: `../gradlew generateJafarTypes`

### "Error occurred while parsing"
Check that the JFR file is valid and not corrupted

### Slow parsing
For very large files (>1GB), consider:
- Using filters to process only relevant events
- Increasing JVM heap size
- Processing in smaller chunks

## Contributing

Have a cool analysis to add? Submit a PR!

Ideas for new demos:
- Allocation profiling
- Lock contention analysis
- GC pause analysis
- File I/O patterns
- Network activity tracking

## License

See parent project license.

## Links

- **Project:** https://github.com/jbachorik/jafar
- **Issues:** https://github.com/jbachorik/jafar/issues
- **JFR Documentation:** https://docs.oracle.com/en/java/javase/21/jfapi/

---

**Happy JFR Analyzing! 🚀**
