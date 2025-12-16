# JAFAR Typed API Tutorial

This tutorial teaches you how to use JAFAR's Typed API for type-safe, high-performance JFR parsing. The Typed API uses Java interfaces annotated with `@JfrType` to represent JFR events, providing compile-time safety and excellent IDE support.

## Table of Contents
1. [Quick Start](#quick-start)
2. [Basic Concepts](#basic-concepts)
3. [Defining Event Interfaces](#defining-event-interfaces)
4. [Parsing Events](#parsing-events)
5. [Working with Complex Types](#working-with-complex-types)
6. [Advanced Features](#advanced-features)
7. [Performance Tips](#performance-tips)
8. [Complete Example](#complete-example)

## Quick Start

Here's the minimal code to parse JFR events with the Typed API:

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

// 1. Define an interface for the event type
@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
    @JfrField("state")
    String state();
}

// 2. Create parser and register handler
try (TypedJafarParser parser = JafarParser.newTypedParser(Paths.get("recording.jfr"))) {
    parser.handle(ExecutionSample.class, (event, ctl) -> {
        System.out.println("Thread state: " + event.state());
    });
    parser.run();
}
```

That's it! The parser will invoke your handler for every `jdk.ExecutionSample` event.

## Basic Concepts

### Event Interfaces

Event interfaces are plain Java interfaces that represent JFR event types. They:
- Must be annotated with `@JfrType("event.type.name")`
- Contain zero-argument methods that return event field values
- Can represent both JDK events and custom application events
- Are implemented at runtime using bytecode generation

### Handlers

Handlers are lambda functions or method references that:
- Receive event instances and a control object
- Execute synchronously on the parser thread
- Should be fast (offload heavy work to background threads)
- Can abort parsing early using `ctl.abort()`

### Control Object

The `Control` parameter provides:
- `stream().position()` - current byte offset in the JFR file
- `abort()` - stop parsing immediately without throwing exception
- `chunkInfo()` - chunk metadata (start time, duration, tick conversion)

## Defining Event Interfaces

### Simple Fields

Map JFR fields to Java methods with matching types:

```java
@JfrType("jdk.FileRead")
public interface FileRead {
    String path();           // String field
    long bytes();           // long field
    long duration();        // nanoseconds (ticks)
    Instant startTime();    // timestamp
}
```

### Field Name Mapping

Use `@JfrField` when Java method names differ from JFR field names:

```java
@JfrType("jdk.SocketRead")
public interface SocketRead {
    @JfrField("address")
    String hostAddress();   // JFR field "address" → method "hostAddress()"

    int port();            // Exact match, no annotation needed
    long bytesRead();      // Exact match
}
```

### Ignoring Fields

Use `@JfrIgnore` to exclude fields you don't need:

```java
@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
    String state();

    @JfrIgnore
    JFRJdkTypesStackTrace stackTrace();  // Won't be populated, always returns null
}
```

This improves performance by skipping unnecessary field extraction.

### Raw Field Access

Request raw JFR values instead of converted types:

```java
@JfrType("jdk.FileRead")
public interface FileRead {
    @JfrField(value = "startTime", raw = true)
    long startTimeTicks();  // Returns raw tick value instead of Instant

    Instant startTime();     // Normal converted value
}
```

Raw access is useful when you need tick values for custom tick conversion or when you want to avoid object allocation.

## Parsing Events

### Basic Parsing

```java
try (TypedJafarParser parser = JafarParser.newTypedParser(Paths.get("recording.jfr"))) {
    HandlerRegistration<FileRead> reg = parser.handle(FileRead.class, (event, ctl) -> {
        System.out.printf("Read %d bytes from %s%n", event.bytes(), event.path());
    });

    parser.run();  // Blocks until parsing completes

    reg.destroy(parser);  // Cleanup (optional if parser is closed)
}
```

### Multiple Event Types

Register handlers for different event types:

```java
try (TypedJafarParser parser = JafarParser.newTypedParser(Paths.get("recording.jfr"))) {
    // Handler 1: File reads
    parser.handle(FileRead.class, (event, ctl) -> {
        System.out.println("File read: " + event.path());
    });

    // Handler 2: Execution samples
    parser.handle(ExecutionSample.class, (event, ctl) -> {
        System.out.println("State: " + event.state());
    });

    // Handler 3: GC events
    parser.handle(GCHeapSummary.class, (event, ctl) -> {
        System.out.println("Heap used: " + event.heapUsed());
    });

    parser.run();  // All handlers execute as events are parsed
}
```

### Early Termination

Stop parsing when you've collected enough data:

```java
AtomicInteger count = new AtomicInteger(0);

try (TypedJafarParser parser = JafarParser.newTypedParser(Paths.get("recording.jfr"))) {
    parser.handle(ExecutionSample.class, (event, ctl) -> {
        if (count.incrementAndGet() >= 1000) {
            ctl.abort();  // Stop after 1000 samples
        }
    });
    parser.run();
}

System.out.println("Processed " + count.get() + " samples");
```

### Error Handling

Exceptions thrown from handlers propagate from `run()`:

```java
try (TypedJafarParser parser = JafarParser.newTypedParser(Paths.get("recording.jfr"))) {
    parser.handle(FileRead.class, (event, ctl) -> {
        if (event.bytes() < 0) {
            throw new IllegalStateException("Invalid byte count: " + event.bytes());
        }
    });

    parser.run();
} catch (Exception e) {
    System.err.println("Parsing failed: " + e.getMessage());
}
```

## Working with Complex Types

### Nested Complex Types

JFR events often contain nested structures. Define interfaces for nested types:

```java
@JfrType("java.lang.Thread")
public interface JFRJavaLangThread {
    String javaName();
    long javaThreadId();
    String osName();
}

@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
    String state();
    JFRJavaLangThread eventThread();  // Nested complex type
}

// Usage
parser.handle(ExecutionSample.class, (event, ctl) -> {
    JFRJavaLangThread thread = event.eventThread();
    if (thread != null) {
        System.out.println("Thread: " + thread.javaName() + " (ID: " + thread.javaThreadId() + ")");
    }
});
```

### Deep Nesting

Access deeply nested fields:

```java
@JfrType("jdk.types.Method")
public interface JFRJdkTypesMethod {
    JFRJavaLangClass type();
    JFRJdkTypesSymbol name();
}

@JfrType("jdk.types.StackFrame")
public interface JFRJdkTypesStackFrame {
    JFRJdkTypesMethod method();
    int lineNumber();
}

@JfrType("jdk.types.StackTrace")
public interface JFRJdkTypesStackTrace {
    JFRJdkTypesStackFrame[] frames();
}

@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
    JFRJdkTypesStackTrace stackTrace();
}

// Usage
parser.handle(ExecutionSample.class, (event, ctl) -> {
    JFRJdkTypesStackTrace stack = event.stackTrace();
    if (stack != null && stack.frames() != null && stack.frames().length > 0) {
        JFRJdkTypesStackFrame topFrame = stack.frames()[0];
        JFRJdkTypesMethod method = topFrame.method();
        if (method != null && method.type() != null) {
            System.out.println("Top frame: " + method.type().name());
        }
    }
});
```

### Arrays

Handle array fields:

```java
@JfrType("jdk.types.StackTrace")
public interface JFRJdkTypesStackTrace {
    JFRJdkTypesStackFrame[] frames();  // Array of complex types
    boolean truncated();
}

// Usage
parser.handle(ExecutionSample.class, (event, ctl) -> {
    JFRJdkTypesStackTrace stack = event.stackTrace();
    if (stack != null && stack.frames() != null) {
        System.out.println("Stack depth: " + stack.frames().length);
        for (JFRJdkTypesStackFrame frame : stack.frames()) {
            // Process each frame
        }
    }
});
```

## Advanced Features

### Reusing Parsing Context

Reuse a `ParsingContext` across multiple recordings for better performance:

```java
ParsingContext ctx = ParsingContext.create();

// Parse first recording
try (TypedJafarParser parser = ctx.newTypedParser(Paths.get("recording1.jfr"))) {
    parser.handle(ExecutionSample.class, (event, ctl) -> {
        // Process event
    });
    parser.run();
}

// Parse second recording (reuses generated classes)
try (TypedJafarParser parser = ctx.newTypedParser(Paths.get("recording2.jfr"))) {
    parser.handle(ExecutionSample.class, (event, ctl) -> {
        // Process event
    });
    parser.run();
}

System.out.println("Total uptime: " + ctx.uptime() + "ns");
```

### Converting Ticks

Convert JFR tick values to time units:

```java
parser.handle(FileRead.class, (event, ctl) -> {
    long durationNanos = ctl.chunkInfo().convertTicks(event.duration(), TimeUnit.NANOSECONDS);
    double durationMs = durationNanos / 1_000_000.0;
    System.out.printf("Duration: %.2f ms%n", durationMs);
});
```

### Chunk Information

Access chunk metadata:

```java
parser.handle(ExecutionSample.class, (event, ctl) -> {
    ChunkInfo chunk = ctl.chunkInfo();
    System.out.println("Chunk start: " + chunk.startTime());
    System.out.println("Chunk duration: " + chunk.duration());
    System.out.println("Chunk size: " + chunk.size() + " bytes");
    System.out.println("Current position: " + ctl.stream().position());
});
```

### Low-Level Parser Events

Observe parsing lifecycle events:

```java
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

parser.withParserListener(new ChunkParserListener() {
    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
        System.out.println("Metadata event received");
        return true;  // Continue parsing
    }

    @Override
    public boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
        System.out.println("Checkpoint event received");
        return true;
    }
}).run();
```

## Performance Tips

### 1. Use @JfrIgnore for Unused Fields

Skip extraction of fields you don't need:

```java
@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
    String state();

    @JfrIgnore
    JFRJdkTypesStackTrace stackTrace();  // Skip if not needed
}
```

### 2. Use Raw Access for Ticks

Avoid `Instant` allocation when you only need tick values:

```java
@JfrType("jdk.FileRead")
public interface FileRead {
    @JfrField(value = "startTime", raw = true)
    long startTimeTicks();  // Faster, no allocation
}
```

### 3. Keep Handlers Fast

Offload heavy work to background threads:

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
BlockingQueue<FileRead> queue = new LinkedBlockingQueue<>();

parser.handle(FileRead.class, (event, ctl) -> {
    queue.offer(event);  // Fast enqueue
});

// Process in background
executor.submit(() -> {
    while (true) {
        FileRead event = queue.take();
        // Heavy processing here
    }
});
```

### 4. Reuse ParsingContext

Generate event classes once, use many times:

```java
ParsingContext ctx = ParsingContext.create();
for (Path file : jfrFiles) {
    try (TypedJafarParser parser = ctx.newTypedParser(file)) {
        // Parse with shared context
    }
}
```

### 5. Primitive Arrays

Use primitive arrays when possible to avoid boxing:

```java
public interface SomeEvent {
    long[] values();  // long[] is faster than Long[]
}
```

## Complete Example

Here's a complete example that analyzes thread activity from JFR:

```java
package com.example;

import io.jafar.parser.api.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ThreadAnalyzer {

    @JfrType("java.lang.Thread")
    public interface JFRJavaLangThread {
        String javaName();
        long javaThreadId();
    }

    @JfrType("jdk.types.Method")
    public interface JFRJdkTypesMethod {
        JFRJavaLangClass type();
    }

    @JfrType("java.lang.Class")
    public interface JFRJavaLangClass {
        String name();
    }

    @JfrType("jdk.types.StackFrame")
    public interface JFRJdkTypesStackFrame {
        JFRJdkTypesMethod method();
    }

    @JfrType("jdk.types.StackTrace")
    public interface JFRJdkTypesStackTrace {
        JFRJdkTypesStackFrame[] frames();
    }

    @JfrType("jdk.ExecutionSample")
    public interface ExecutionSample {
        String state();
        JFRJavaLangThread eventThread();
        JFRJdkTypesStackTrace stackTrace();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ThreadAnalyzer <recording.jfr>");
            System.exit(1);
        }

        Path jfrFile = Paths.get(args[0]);

        // Collect statistics
        Map<String, AtomicLong> threadSamples = new HashMap<>();
        Map<String, AtomicLong> methodSamples = new HashMap<>();
        AtomicLong totalSamples = new AtomicLong(0);

        // Parse recording
        ParsingContext ctx = ParsingContext.create();
        try (TypedJafarParser parser = ctx.newTypedParser(jfrFile)) {
            parser.handle(ExecutionSample.class, (event, ctl) -> {
                totalSamples.incrementAndGet();

                // Count by thread
                JFRJavaLangThread thread = event.eventThread();
                if (thread != null && thread.javaName() != null) {
                    threadSamples.computeIfAbsent(thread.javaName(), k -> new AtomicLong())
                                .incrementAndGet();
                }

                // Count by method
                JFRJdkTypesStackTrace stack = event.stackTrace();
                if (stack != null && stack.frames() != null && stack.frames().length > 0) {
                    JFRJdkTypesStackFrame topFrame = stack.frames()[0];
                    JFRJdkTypesMethod method = topFrame.method();
                    if (method != null && method.type() != null) {
                        String className = method.type().name().replace('/', '.');
                        methodSamples.computeIfAbsent(className, k -> new AtomicLong())
                                    .incrementAndGet();
                    }
                }
            });

            parser.run();
        }

        // Display results
        System.out.println("=".repeat(80));
        System.out.println("Thread Activity Analysis");
        System.out.println("=".repeat(80));
        System.out.printf("Total samples: %,d%n", totalSamples.get());
        System.out.printf("Parser uptime: %,d ms%n%n", ctx.uptime() / 1_000_000);

        System.out.println("Top 10 threads by samples:");
        threadSamples.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
                Long.compare(b.get(), a.get())))
            .limit(10)
            .forEach(e -> System.out.printf("  %6d samples: %s%n",
                e.getValue().get(), e.getKey()));

        System.out.println("\nTop 10 hot methods:");
        methodSamples.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
                Long.compare(b.get(), a.get())))
            .limit(10)
            .forEach(e -> System.out.printf("  %6d samples: %s%n",
                e.getValue().get(), e.getKey()));
    }
}
```

## Next Steps

- Learn about the [Untyped API](untyped-api-tutorial.md) for dynamic event handling
- Explore [JFR Shell](jfr-shell-tutorial.md) for interactive analysis
- Read the [Parser Architecture](../parser/ARCHITECTURE.md) for internals
- Check [PERFORMANCE.md](../PERFORMANCE.md) for optimization tips

## Related Documentation

- [README.md](../README.md) - Project overview and quick start
- [LIMITATIONS.md](../LIMITATIONS.md) - Known limitations
- [Gradle Plugin](../README.md#gradle-plugin-generate-jafar-type-interfaces) - Auto-generate event interfaces
