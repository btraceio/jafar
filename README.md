# jafar
Experimental, fast JFR parser with a small, focused API.

Very much a work in progress. The goal is to parse JFR files and extract event data with minimal ceremony.

## Requirements
- Java 17+
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
  });
  p.run();
  reg.destroy(p); // deregister
}
```

Notes:
- Handlers run synchronously on the parser thread. Keep work small or offload.
- Exceptions thrown from a handler stop parsing and propagate from `run()`.

## Untyped API
Receive events as `Map<String, Object>` with nested maps/arrays when applicable.

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

try (UntypedJafarParser p = JafarParser.newUntypedParser(Paths.get("/path/to/recording.jfr"))) {
  HandlerRegistration<?> reg = p.handle((type, value) -> {
    if ("jdk.ExecutionSample".equals(type.getName())) {
      Object threadId = value.get("eventThread.id");
      // ...
    }
  });
  p.run();
  reg.destroy(p);
}
```

## Core API overview
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
- `ParsingContext`
  - `create()`: build a reusable context.
  - `newTypedParser(Path)` / `newUntypedParser(Path)`: create parsers bound to the shared context.
  - `uptime()`: cumulative processing time across sessions using the context.
- `Control`
  - `stream().position()`: current byte position while a handler executes.
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
After building, you can run the demo application to compare parsers on `jdk.ExecutionSample`:

```shell
java -jar demo/build/libs/demo-all.jar [jafar|jmc|jfr|jfr-stream] /path/to/recording.jfr
```

On an M1 and a ~600MiB JFR, the Jafar parser completes in ~1s vs ~7s with JMC (anecdotal). The stock `jfr` tool may OOM when printing all events.

## License
Apache 2.0 (see `LICENSE`).