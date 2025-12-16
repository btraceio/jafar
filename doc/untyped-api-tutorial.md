# JAFAR Untyped API Tutorial

This tutorial teaches you how to use JAFAR's Untyped API for flexible, dynamic JFR parsing. The Untyped API represents events as `Map<String, Object>`, allowing you to work with any JFR event type without defining interfaces upfront.

## Table of Contents
1. [Quick Start](#quick-start)
2. [When to Use Untyped API](#when-to-use-untyped-api)
3. [Basic Concepts](#basic-concepts)
4. [Accessing Event Fields](#accessing-event-fields)
5. [Working with Complex Types](#working-with-complex-types)
6. [Working with Arrays](#working-with-arrays)
7. [Type Filtering](#type-filtering)
8. [Advanced Techniques](#advanced-techniques)
9. [Performance Considerations](#performance-considerations)
10. [Complete Example](#complete-example)

## Quick Start

Here's the minimal code to parse JFR events with the Untyped API:

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

try (UntypedJafarParser parser = JafarParser.newUntypedParser(Paths.get("recording.jfr"))) {
    parser.handle((type, value, ctl) -> {
        if ("jdk.ExecutionSample".equals(type.getName())) {
            Object state = Values.get(value, "state");
            System.out.println("Thread state: " + state);
        }
    });
    parser.run();
}
```

That's it! The handler receives every event as a Map, and you check the type name to process specific events.

## When to Use Untyped API

**Use the Untyped API when:**
- You don't know event types at compile time
- You're exploring unknown JFR recordings
- You need maximum flexibility
- You're building generic tools (like JFR Shell)
- You want to avoid defining many interfaces

**Use the Typed API when:**
- You know event types upfront
- You want compile-time safety
- You need better IDE support
- You prefer type-safe code

## Basic Concepts

### Event Maps

Every event is represented as `Map<String, Object>` where:
- Keys are field names (e.g., `"state"`, `"duration"`, `"eventThread"`)
- Values can be:
  - Primitive wrappers (`Long`, `Integer`, `Boolean`, etc.)
  - `String`
  - `Instant` for timestamps
  - `Map<String, Object>` for nested complex types
  - `ComplexType` for constant-pool references
  - `ArrayType` for array fields
  - `null` for absent optional fields

### Type Information

The `type` parameter provides metadata about the event:
- `getName()` - fully-qualified type name (e.g., `"jdk.ExecutionSample"`)
- Use this to filter and dispatch to appropriate handlers

### Values Helper

The `Values` utility class provides safe field access:
- `Values.get(map, "field")` - get a field value
- `Values.get(map, "nested", "field")` - navigate nested structures
- `Values.get(map, "array", 0)` - access array elements
- `Values.as(map, Class, "field")` - get typed value with Optional
- Handles null values gracefully

## Accessing Event Fields

### Simple Fields

Access top-level fields directly:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        // Direct map access
        String path = (String) value.get("path");
        Long bytes = (Long) value.get("bytes");

        // Or use Values helper (null-safe)
        Object pathObj = Values.get(value, "path");
        Object bytesObj = Values.get(value, "bytes");

        System.out.printf("Read %d bytes from %s%n", bytes, path);
    }
});
```

### Typed Access with Optional

Use `Values.as()` for type-safe access with Optional:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        // Type-safe access
        String path = Values.as(value, String.class, "path").orElse("unknown");
        long bytes = Values.as(value, Long.class, "bytes").orElse(0L);

        System.out.printf("Read %d bytes from %s%n", bytes, path);
    }
});
```

### Nested Fields

Navigate nested structures using path notation:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Access nested field: eventThread.javaName
        Object threadName = Values.get(value, "eventThread", "javaName");

        // Or with type safety
        String name = Values.as(value, String.class, "eventThread", "javaName")
                           .orElse("unknown");

        System.out.println("Thread: " + name);
    }
});
```

### Deep Nesting

Access deeply nested fields:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Access: stackTrace.frames[0].method.type.name
        Object className = Values.get(value, "stackTrace", "frames", 0,
                                     "method", "type", "name", "string");

        if (className != null) {
            System.out.println("Top frame: " + className);
        }
    }
});
```

## Working with Complex Types

### Inline Complex Types

Some complex fields appear directly as nested maps:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Get nested map
        Object threadObj = Values.get(value, "eventThread");

        if (threadObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> thread = (Map<String, Object>) threadObj;

            String name = (String) thread.get("javaName");
            Long id = (Long) thread.get("javaThreadId");

            System.out.printf("Thread: %s (ID: %d)%n", name, id);
        }
    }
});
```

### ComplexType Wrapper

Constant-pool backed references use `ComplexType` wrapper:

```java
import io.jafar.parser.api.ComplexType;

parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        Object threadObj = Values.get(value, "eventThread");

        if (threadObj instanceof ComplexType) {
            // Resolve constant-pool reference
            Map<String, Object> thread = ((ComplexType) threadObj).getValue();

            String name = (String) thread.get("javaName");
            System.out.println("Thread: " + name);
        } else if (threadObj instanceof Map) {
            // Inline value
            @SuppressWarnings("unchecked")
            Map<String, Object> thread = (Map<String, Object>) threadObj;
            String name = (String) thread.get("javaName");
            System.out.println("Thread: " + name);
        }
    }
});
```

### Unified Complex Type Access

Use `Values.as()` to handle both cases automatically:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Automatically resolves ComplexType or Map
        Optional<Map<String, Object>> threadOpt =
            Values.as(value, Map.class, "eventThread");

        threadOpt.ifPresent(thread -> {
            String name = (String) thread.get("javaName");
            Long id = (Long) thread.get("javaThreadId");
            System.out.printf("Thread: %s (ID: %d)%n", name, id);
        });
    }
});
```

## Working with Arrays

### ArrayType Wrapper

Array fields use the `ArrayType` wrapper:

```java
import io.jafar.parser.api.ArrayType;

parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        Object framesObj = Values.get(value, "stackTrace", "frames");

        if (framesObj instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) framesObj;

            // Get array class
            Class<?> arrayClass = arrayType.getType();
            System.out.println("Array type: " + arrayClass);

            // Get backing array
            Object array = arrayType.getArray();

            if (array instanceof Object[]) {
                Object[] frames = (Object[]) array;
                System.out.println("Stack depth: " + frames.length);
            }
        }
    }
});
```

### Direct Array Element Access

Use `Values.get()` with index to access array elements:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Access first frame directly: stackTrace.frames[0]
        Object topFrame = Values.get(value, "stackTrace", "frames", 0);

        if (topFrame != null) {
            // topFrame is a Map or ComplexType
            Object methodName = Values.get((Map<String, Object>) topFrame,
                                         "method", "name", "string");
            System.out.println("Top method: " + methodName);
        }
    }
});
```

### Iterating Arrays

Process all array elements:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        Object framesObj = Values.get(value, "stackTrace", "frames");

        if (framesObj instanceof ArrayType) {
            Object array = ((ArrayType) framesObj).getArray();

            if (array instanceof Object[]) {
                Object[] frames = (Object[]) array;

                for (Object frameObj : frames) {
                    if (frameObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> frame = (Map<String, Object>) frameObj;

                        Object methodName = Values.get(frame, "method", "name", "string");
                        System.out.println("  Method: " + methodName);
                    } else if (frameObj instanceof ComplexType) {
                        Map<String, Object> frame = ((ComplexType) frameObj).getValue();
                        Object methodName = Values.get(frame, "method", "name", "string");
                        System.out.println("  Method: " + methodName);
                    }
                }
            }
        }
    }
});
```

### Primitive Arrays

Handle primitive array types:

```java
parser.handle((type, value, ctl) -> {
    Object arrayObj = Values.get(value, "someField");

    if (arrayObj instanceof ArrayType) {
        Object array = ((ArrayType) arrayObj).getArray();

        if (array instanceof long[]) {
            long[] longs = (long[]) array;
            for (long l : longs) {
                System.out.println("Value: " + l);
            }
        } else if (array instanceof int[]) {
            int[] ints = (int[]) array;
            for (int i : ints) {
                System.out.println("Value: " + i);
            }
        } else if (array instanceof byte[]) {
            byte[] bytes = (byte[]) array;
            System.out.println("Byte array length: " + bytes.length);
        }
    }
});
```

## Type Filtering

### Single Type Handler

Process only specific event types:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        Object path = Values.get(value, "path");
        System.out.println("File read: " + path);
    }
});
```

### Multiple Type Handler

Handle multiple event types in one handler:

```java
parser.handle((type, value, ctl) -> {
    String typeName = type.getName();

    switch (typeName) {
        case "jdk.FileRead":
            Object path = Values.get(value, "path");
            System.out.println("File read: " + path);
            break;

        case "jdk.FileWrite":
            Object writePath = Values.get(value, "path");
            System.out.println("File write: " + writePath);
            break;

        case "jdk.SocketRead":
            Object address = Values.get(value, "address");
            System.out.println("Socket read: " + address);
            break;
    }
});
```

### Pattern-Based Filtering

Filter by type name patterns:

```java
parser.handle((type, value, ctl) -> {
    String typeName = type.getName();

    // All JDK events
    if (typeName.startsWith("jdk.")) {
        // Process JDK event
    }

    // All GC events
    if (typeName.startsWith("jdk.") && typeName.contains("GC")) {
        System.out.println("GC event: " + typeName);
    }

    // All Datadog profiler events
    if (typeName.startsWith("datadog.")) {
        // Process Datadog event
    }
});
```

### Counting Event Types

Discover all event types in a recording:

```java
Map<String, AtomicLong> eventCounts = new ConcurrentHashMap<>();

parser.handle((type, value, ctl) -> {
    eventCounts.computeIfAbsent(type.getName(), k -> new AtomicLong())
              .incrementAndGet();
});

parser.run();

// Display results
eventCounts.entrySet().stream()
    .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
        Long.compare(b.get(), a.get())))
    .forEach(e -> System.out.printf("%6d %s%n", e.getValue().get(), e.getKey()));
```

## Advanced Techniques

### String Extraction Helper

Handle different string representations:

```java
private static String extractString(Object value) {
    if (value == null) return "";
    if (value instanceof ComplexType) {
        Map<String, Object> map = ((ComplexType) value).getValue();
        Object str = map.get("string");
        return str != null ? String.valueOf(str) : "";
    }
    if (value instanceof String) return (String) value;
    return String.valueOf(value);
}

// Usage
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        Object stateObj = Values.get(value, "state", "name");
        String state = extractString(stateObj);
        System.out.println("State: " + state);
    }
});
```

### Field Existence Checking

Check if a field exists before accessing:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        // Check if field exists
        if (value.containsKey("bytesRead")) {
            Object bytes = value.get("bytesRead");
            System.out.println("Bytes: " + bytes);
        } else if (value.containsKey("bytes")) {
            Object bytes = value.get("bytes");
            System.out.println("Bytes: " + bytes);
        }
    }
});
```

### Reusing Parsing Context

Share context across multiple parses:

```java
ParsingContext ctx = ParsingContext.create();

// Parse first recording
try (UntypedJafarParser parser = ctx.newUntypedParser(Paths.get("recording1.jfr"))) {
    parser.handle((type, value, ctl) -> {
        // Process events
    });
    parser.run();
}

// Parse second recording
try (UntypedJafarParser parser = ctx.newUntypedParser(Paths.get("recording2.jfr"))) {
    parser.handle((type, value, ctl) -> {
        // Process events
    });
    parser.run();
}

System.out.println("Total uptime: " + ctx.uptime() + "ns");
```

### Early Termination

Stop parsing when done:

```java
AtomicInteger count = new AtomicInteger(0);

parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        if (count.incrementAndGet() >= 1000) {
            ctl.abort();  // Stop after 1000 samples
        }
    }
});
```

### Converting Ticks

Convert JFR tick values to time units:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        Object durationObj = Values.get(value, "duration");

        if (durationObj instanceof Number) {
            long ticks = ((Number) durationObj).longValue();
            long nanos = ctl.chunkInfo().convertTicks(ticks, TimeUnit.NANOSECONDS);
            double ms = nanos / 1_000_000.0;

            System.out.printf("Duration: %.2f ms%n", ms);
        }
    }
});
```

## Performance Considerations

### 1. Avoid Unnecessary String Checks

Cache type name checks:

```java
// Inefficient
parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) { }
    if ("jdk.FileWrite".equals(type.getName())) { }
    if ("jdk.SocketRead".equals(type.getName())) { }
});

// Better - single getName() call
parser.handle((type, value, ctl) -> {
    String typeName = type.getName();
    if ("jdk.FileRead".equals(typeName)) { }
    else if ("jdk.FileWrite".equals(typeName)) { }
    else if ("jdk.SocketRead".equals(typeName)) { }
});
```

### 2. Minimize Map Lookups

Cache frequently accessed fields:

```java
parser.handle((type, value, ctl) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
        // Cache thread object
        Object threadObj = Values.get(value, "eventThread");

        if (threadObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> thread = (Map<String, Object>) threadObj;

            // Reuse thread map for multiple field accesses
            String name = (String) thread.get("javaName");
            Long id = (Long) thread.get("javaThreadId");
            String osName = (String) thread.get("osName");
        }
    }
});
```

### 3. Use Direct Map Access for Known Fields

Skip `Values.get()` when you know the structure:

```java
// Slower
Object bytes = Values.get(value, "bytes");

// Faster for known structure
Object bytes = value.get("bytes");
```

### 4. Batch Processing

Queue events for batch processing:

```java
BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();

parser.handle((type, value, ctl) -> {
    if ("jdk.FileRead".equals(type.getName())) {
        queue.offer(new HashMap<>(value));  // Copy for async processing
    }
});

// Process in background
executor.submit(() -> {
    while (true) {
        Map<String, Object> event = queue.take();
        // Heavy processing
    }
});
```

### 5. Filter Early

Skip unnecessary work:

```java
parser.handle((type, value, ctl) -> {
    String typeName = type.getName();

    // Quick rejection
    if (!typeName.startsWith("jdk.")) {
        return;
    }

    // Process only relevant events
    if (typeName.equals("jdk.FileRead")) {
        // Process
    }
});
```

## Complete Example

Here's a comprehensive example analyzing I/O operations:

```java
package com.example;

import io.jafar.parser.api.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class IOAnalyzer {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: IOAnalyzer <recording.jfr>");
            System.exit(1);
        }

        Path jfrFile = Paths.get(args[0]);

        // Statistics
        Map<String, AtomicLong> fileReadCounts = new HashMap<>();
        Map<String, AtomicLong> fileReadBytes = new HashMap<>();
        Map<String, AtomicLong> socketReadCounts = new HashMap<>();
        AtomicLong totalFileReads = new AtomicLong(0);
        AtomicLong totalSocketReads = new AtomicLong(0);

        // Parse recording
        ParsingContext ctx = ParsingContext.create();
        try (UntypedJafarParser parser = ctx.newUntypedParser(jfrFile)) {
            parser.handle((type, value, ctl) -> {
                String typeName = type.getName();

                if ("jdk.FileRead".equals(typeName)) {
                    totalFileReads.incrementAndGet();

                    // Extract path
                    String path = Values.as(value, String.class, "path")
                                       .orElse("unknown");

                    // Extract bytes
                    long bytes = Values.as(value, Long.class, "bytes")
                                      .orElse(0L);

                    // Count by file
                    fileReadCounts.computeIfAbsent(path, k -> new AtomicLong())
                                 .incrementAndGet();
                    fileReadBytes.computeIfAbsent(path, k -> new AtomicLong())
                                .addAndGet(bytes);

                } else if ("jdk.SocketRead".equals(typeName)) {
                    totalSocketReads.incrementAndGet();

                    // Extract address and port
                    String address = Values.as(value, String.class, "address")
                                          .orElse("unknown");
                    Integer port = Values.as(value, Integer.class, "port")
                                        .orElse(0);

                    String endpoint = address + ":" + port;

                    socketReadCounts.computeIfAbsent(endpoint, k -> new AtomicLong())
                                   .incrementAndGet();
                }
            });

            parser.run();
        }

        // Display results
        System.out.println("=".repeat(80));
        System.out.println("I/O Analysis");
        System.out.println("=".repeat(80));
        System.out.printf("Total file reads: %,d%n", totalFileReads.get());
        System.out.printf("Total socket reads: %,d%n", totalSocketReads.get());
        System.out.printf("Parser uptime: %,d ms%n%n", ctx.uptime() / 1_000_000);

        if (!fileReadCounts.isEmpty()) {
            System.out.println("Top 10 files by read count:");
            fileReadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
                    Long.compare(b.get(), a.get())))
                .limit(10)
                .forEach(e -> {
                    long count = e.getValue().get();
                    long bytes = fileReadBytes.getOrDefault(e.getKey(),
                        new AtomicLong(0)).get();
                    System.out.printf("  %6d reads (%s): %s%n",
                        count, formatBytes(bytes), e.getKey());
                });
        }

        if (!socketReadCounts.isEmpty()) {
            System.out.println("\nTop 10 socket endpoints by read count:");
            socketReadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
                    Long.compare(b.get(), a.get())))
                .limit(10)
                .forEach(e -> System.out.printf("  %6d reads: %s%n",
                    e.getValue().get(), e.getKey()));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
```

## Next Steps

- Learn about the [Typed API](typed-api-tutorial.md) for type-safe parsing
- Explore [JFR Shell](jfr-shell-tutorial.md) for interactive analysis
- Read the [Parser Architecture](../parser/ARCHITECTURE.md) for internals
- Check [PERFORMANCE.md](../PERFORMANCE.md) for optimization tips

## Related Documentation

- [README.md](../README.md) - Project overview and quick start
- [LIMITATIONS.md](../LIMITATIONS.md) - Known limitations
- [Values API](../parser/src/main/java/io/jafar/parser/api/Values.java) - Field access utilities
