# jafar
Experimental, fast JFR parser with a small, focused API.

Very much a work in progress. The goal is to parse JFR files and extract event data with minimal ceremony.

## Requirements
- Java 21+
- Git LFS (recordings are stored with LFS). Install per GitHub docs: `https://docs.github.com/en/repositories/working-with-files/managing-large-files/installing-git-large-file-storage`

## Build
1) Fetch binary resources: `./get_resources.sh`
2) Build all modules: `./gradlew shadowJar`

## Quick start (typed API)
Define a Java interface per JFR type and annotate with `@JfrType`. Methods correspond to event fields; use `@JfrField` to map differing names and `@JfrIgnore` to skip fields.

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

@JfrType("custom.MyEvent")
public interface MyEvent { // no base interface required
  String myfield();
}

try (TypedJafarParser p = JafarParser.newTypedParser(Paths.get("/path/to/recording.jfr"))) {
  HandlerRegistration<MyEvent> reg = p.handle(MyEvent.class, (e, ctl) -> {
    System.out.println(e.myfield());
    long pos = ctl.stream().position(); // current byte position while in handler
    // ctl.abort(); // optionally stop parsing immediately without throwing
  });
  p.run();
  reg.destroy(p); // deregister
}
```

Notes:
- Handlers run synchronously on the parser thread. Keep work small or offload.
- Exceptions thrown from a handler stop parsing and propagate from `run()`.
- Call `ctl.abort()` inside a handler to stop parsing early without an exception.

## Untyped API
Receive events as `Map<String, Object>` with nested maps/arrays when applicable.

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

try (UntypedJafarParser p = JafarParser.newUntypedParser(Paths.get("/path/to/recording.jfr"))) {
  HandlerRegistration<?> reg = p.handle((type, value) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
      Object threadId = Values.get(value, "eventThread", "javaThreadId");
      // use threadId ...
    }
  });
  p.run();
  reg.destroy(p);
}
```

### Complex and array values in untyped events
- **ComplexType**: Complex fields may appear either inline as `Map<String, Object>` or as a wrapper implementing `io.jafar.parser.api.ComplexType` (e.g., constant-pool backed references). Use `getValue()` on a `ComplexType` to obtain the resolved `Map<String, Object>`.
- **ArrayType**: When a field is an array, the value implements `io.jafar.parser.api.ArrayType`. Use `getType()` to inspect the array class (e.g., `int[].class`, `Object[].class`) and `getArray()` to access the underlying Java array.

Examples:

```java
import io.jafar.parser.api.*;
import java.util.Map;

try (UntypedJafarParser p = JafarParser.newUntypedParser(Paths.get("/path/to/recording.jfr"))) {
  p.handle((type, value) -> {
    // ComplexType: constant-pool backed references (e.g., eventThread)
    Map<String, Object> thread = Values.as(value, Map.class, "eventThread").orElse(null);
    if (thread != null) {
      System.out.println("thread id=" + thread.get("javaThreadId") + ", name=" + thread.get("name"));
    }

    // ArrayType: arrays of primitives, Strings, maps, or ComplexType elements
    Object framesVal = Values.get(value, "stackTrace", "frames");
    if (framesVal instanceof ArrayType at) {
      Object arr = at.getArray();
      if (arr instanceof Object[] objs) {
        for (Object el : objs) {
          if (el instanceof ComplexType cpx) {
            Map<String, Object> m = cpx.getValue();
            // use fields from the resolved element
          } else if (el instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) el; // inline complex value
          } else {
            // primitive wrapper or String
          }
        }
      } else if (arr instanceof int[] ints) {
        for (int i : ints) { /* ... */ }
      } else if (arr instanceof long[] longs) {
        for (long l : longs) { /* ... */ }
      }
    }
  });
  p.run();
}
```

## Core API overview

For the architecture of the `parser` module, see the [Parser Architecture](parser/ARCHITECTURE.md).
- `JafarParser`
  - `newTypedParser(Path)` / `newUntypedParser(Path)`: start a session.
  - `withParserListener(ChunkParserListener)`: observe low-level parse events (advanced, see below).
  - `run()`: parse and invoke registered handlers.
- `TypedJafarParser`
  - `handle(Class<T>, JFRHandler<T>) -> HandlerRegistration<T>`
  - Static `open(String|Path[, ParsingContext])` are also available, but prefer `JafarParser.newTypedParser(Path)`.
- `UntypedJafarParser`
  - `handle(UntypedJafarParser.EventHandler) -> HandlerRegistration<?>`
  - Static `open(String|Path[, ParsingContext])` also available.
- Data wrappers
  - `ArrayType`: wrapper around arrays. `getType()` returns the array class; `getArray()` returns the backing Java array.
  - `ComplexType`: wrapper around complex values. `getValue()` resolves to a `Map<String, Object>`. Note that some complex fields may be provided inline as a `Map` without a wrapper.
- `ParsingContext`
  - `create()`: build a reusable context.
  - `newTypedParser(Path)` / `newUntypedParser(Path)`: create parsers bound to the shared context.
  - `uptime()`: cumulative processing time across sessions using the context.
- `Control`
  - `stream().position()`: current byte position while a handler executes.
  - `abort()`: stop parsing immediately (no exception thrown).
  - `chunkInfo()`: chunk metadata with `startTime()`, `duration()`, `size()`, and `convertTicks(long, TimeUnit)`.
    - Why `convertTicks(...)`? JFR records many time values in chunk-relative ticks. Converting on demand avoids creating `Instant`/`Duration` objects for every event, minimizing allocation and GC pressure when a scalar value suffices. Convert only when needed and to the unit you need.

### Typed runtime (JDK support)
- The typed parser defines small, generated classes at runtime. It automatically picks the best available strategy for the running JDK:
  - JDK 15+: hidden classes via `MethodHandles.Lookup#defineHiddenClass` (fastest, unloadable)
  - JDK 9–14: `MethodHandles.Lookup#defineClass(byte[])` (good)
  - JDK 8: `sun.misc.Unsafe#defineAnonymousClass` (compatible; slightly heavier)
- Selection is automatic based on capability probes; no flags required. Enable debug logs to see the chosen strategy.

### Multi‑Release JAR (parser)
- The `parser` artifact is a Multi‑Release JAR:
  - Base classes target Java 8 for broad compatibility.
  - Java 21 overrides live under `META-INF/versions/21` and restore faster implementations (e.g., zero‑copy `ByteBuffer` slicing, `Arrays.equals` range checks, `Files.writeString`, etc.).
- On Java 21+, the JVM loads these optimized classes automatically. On older JVMs, the Java 8 fallbacks are used.
- Annotations
  - `@JfrType("<fq.type>")`: declare the JFR type an interface represents.
  - `@JfrField("<jfrField>", raw = false)`: map differing names or request raw representation.
  - `@JfrIgnore`: exclude a method from mapping.

## Advanced usage
- Reusing context across many recordings

```java
ParsingContext ctx = ParsingContext.create();
try (TypedJafarParser p = ctx.newTypedParser(Paths.get("/path/to.a.jfr"))) {
  p.handle(MyEvent.class, (e, ctl) -> {/*...*/});
  p.run();
}
try (TypedJafarParser p = ctx.newTypedParser(Paths.get("/path/to.b.jfr"))) {
  p.handle(MyEvent.class, (e, ctl) -> {/*...*/});
  p.run();
}
System.out.println("uptime(ns)=" + ctx.uptime());
```

- Early termination with Control

```java
AtomicInteger seen = new AtomicInteger();
try (TypedJafarParser p = JafarParser.newTypedParser(Paths.get("/path/to.jfr"))) {
  HandlerRegistration<JFRExecutionSample> reg =
      p.handle(JFRExecutionSample.class, (e, ctl) -> {
        if (seen.incrementAndGet() >= 1000) {
          ctl.abort(); // stop without throwing
        }
      });
  p.run();
  reg.destroy(p);
}
```

- Converting JFR ticks to time units

```java
// Some fields are expressed in JFR ticks. Convert them only when needed.
try (UntypedJafarParser p = JafarParser.newUntypedParser(Paths.get("/file.jfr"))) {
  p.handle((type, value, ctl) -> {
    long ticksObj = value.get("startTime"); // example field holding ticks
    long nanos = ctl.chunkInfo().convertTicks(n.longValue(), TimeUnit.NANOSECONDS);
    // Use nanos directly, or wrap as Instant only when necessary
    Instant startTs = ctl.chunkInfo().startTime().plusNanos(nanos);
    // use the startTs instant ...
  });
  p.run();
}
```

- Observing parse lifecycle (low-level)

```java
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

p.withParserListener(new ChunkParserListener() {
  @Override public boolean onMetadata(ParserContext c, MetadataEvent md) {
    // inspect metadata per chunk
    return true; // continue
  }
}).run();
```

## Gradle plugin: generate Jafar type interfaces
Plugin id: `io.btrace.jafar-gradle-plugin`

Adds task `generateJafarTypes` and wires it to `compileJava`. It can generate interfaces for selected JFR types from either the current JVM metadata (default) or a `.jfr` file.

```gradle
plugins {
  id 'io.btrace.jafar-gradle-plugin' version '0.0.1-SNAPSHOT'
}

repositories {
  mavenCentral()
  mavenLocal()
  maven { url "https://oss.sonatype.org/content/repositories/snapshots/" }
}

generateJafarTypes {
  // Optional: use a JFR file to derive metadata; otherwise JVM runtime metadata is used
  inputFile = file('/path/to/recording.jfr')

  // Optional: where to generate sources (default: build/generated/sources/jafar/src/main)
  outputDir = project.file('src/main/java')

  // Optional: do not overwrite existing files (default: false)
  overwrite = false

  // Optional: filter event types by name (closure gets fully-qualified JFR type name)
  eventTypeFilter {
    it.startsWith('jdk.') && it != 'jdk.SomeExcludedType'
  }

  // Package for generated interfaces (default: io.jafar.parser.api.types)
  targetPackage = 'com.acme.jfr.types'
}
```

You can also provide the input file via a project property: `-Pjafar.input=/path/to/recording.jfr`.

## Tools: Scrubbing sensitive fields in a `.jfr`
`io.jafar.tools.Scrubber` can scrub selected string fields in-place while copying to a new file.

Example: scrub the value of `jdk.InitialSystemProperty.value` when `key == 'java.home'`.

```java
import static io.jafar.tools.Scrubber.scrubFile;
import io.jafar.tools.Scrubber.ScrubField;
import java.nio.file.Paths;

scrubFile(Paths.get("/in.jfr"), Paths.get("/out-scrubbed.jfr"),
  clz -> {
    if (clz.equals("jdk.InitialSystemProperty")) {
      return new ScrubField("key", "value", (k, v) -> "java.home".equals(k));
    }
    return null; // no scrubbing for other classes
  }
);
```

## Demo
Build and run the demo application:

```shell
# First you need to publish parser, tools and plugin to local maven
cd demo
./build.sh
java -jar build/libs/jafar-demo-all.jar [jafar|jmc|jfr|jfr-stream] /path/to/recording.jfr
```

On an M1 and a ~600MiB JFR, the Jafar parser completes in ~1s vs ~7s with JMC (anecdotal). The stock `jfr` tool may OOM when printing all events.

## License
Apache 2.0 (see `LICENSE`).
