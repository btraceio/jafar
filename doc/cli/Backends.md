# JFR Shell Backend Plugin Guide

This document describes the JFR Shell backend plugin architecture, how to write custom backends, and how to validate them with the TCK (Technology Compatibility Kit).

## Overview

JFR Shell uses a pluggable backend system to parse JFR (Java Flight Recorder) files. Different backends offer various trade-offs between feature support, performance, and memory usage.

### Built-in Backends

| Backend | ID | Priority | Description |
|---------|-----|----------|-------------|
| Jafar Parser | `jafar` | 100 | Full-featured reference implementation with all capabilities |
| JDK JFR API | `jdk` | 50 | Uses standard `jdk.jfr.consumer` API, limited but widely compatible |

### Backend Selection

Backends are selected in this order:

1. `JFRSHELL_BACKEND` environment variable
2. `jfr.shell.backend` system property
3. Highest priority backend available

```bash
# Use specific backend via environment variable
JFRSHELL_BACKEND=jdk java -jar jfr-shell.jar

# Or via system property
java -Djfr.shell.backend=jdk -jar jfr-shell.jar

# Or via CLI option
java -jar jfr-shell.jar --backend jdk
```

## Backend Capabilities

Each backend declares a set of capabilities. JFR Shell adapts its features based on what the current backend supports.

| Capability | Description | Jafar | JDK |
|------------|-------------|-------|-----|
| `EVENT_STREAMING` | Stream events from recordings | ✓ | ✓ |
| `METADATA_CLASSES` | Access event types and field information | ✓ | ✓ |
| `CHUNK_INFO` | Access chunk-level details (headers, offsets) | ✓ | ✗ |
| `CONSTANT_POOLS` | Direct constant pool access | ✓ | ✗ |
| `STREAMING_PARSE` | Parse large files without full memory load | ✓ | ✓ |
| `TYPED_HANDLERS` | Compile-time typed event interfaces | ✓ | ✗ |
| `UNTYPED_HANDLERS` | Map-based event access | ✓ | ✓ |
| `CONTEXT_REUSE` | Share parsing context across sessions | ✓ | ✗ |

The JDK backend reads merged event metadata and recovers chunk-local types from events, following
nested field descriptors, including threads, classes, stack traces, frames, and methods. These types appear in the TUI
metadata browser and in the fields supplied to `ask` and `as-query` in both shells. For example,
with a recording open and the JDK backend selected:

```text
metadata class java.lang.Thread --fields
metadata class jdk.types.StackTrace --fields
```

Array fields such as stack-trace frames retain their array dimension. Names follow the public JDK
API: symbol-backed strings appear as `java.lang.String`, rather than separate raw symbol types.
Types unreachable from event field descriptors, raw constant pools, and chunk headers are not
exposed by this backend. A type's presence in metadata does not mean it emitted events.

Shell event queries (`events/...`) always read through the Jafar parser, whichever backend is
selected. The JDK API's event values matter in live `tail` and in this backend's `EventSource`:
there a struct is a field map, an array of structs (such as `stackTrace.frames`) is an `Object[]`
of field maps, and a primitive array stays a primitive array, the same containers as the Jafar
backend's resolved values. Leaf values follow the JDK API: symbols are plain strings, simple types
are collapsed (`type="Native"`) and ints are `Integer`, so a `tail` filter written against the Jafar
wrapped form (`method/name/string`, `type/description`) matches nothing; write
`method/name="..."` and `type="Native"`, which the Jafar-side filter matcher also accepts, so
the same filter works on both backends. Before #196 these paths passed the JDK's raw
`RecordedFrame` objects through, and `tail --format json` printed
`"frames":"[Ljava.lang.Object;@…"`.

In the TUI metadata sidebar, `/` filters type names. Clearing or dismissing a filter keeps the
selected type visible; canceling a search with no matches restores a selection in the full list.

> **Multi-chunk recordings (Datadog profiler and similar):** the JDK backend builds its type
> table with `RecordingFile.readEventTypes()`, which merges every chunk's type table into one
> id-to-name mapping. If chunks redefine the same type id (e.g. `jdk.VirtualizationInformation` and
> `datadog.ExecutionSample` both as 101), the merged view misattributes or hides the chunk-local
> types. The backend detects this and recovers the chunk-local types from event payloads, logging a
> warning; ids in the recovered view are chunk-local and not unique across chunks, and listings
> keep one representative per type name. Detection happens on metadata access (types with no events
> and types unreachable from recovered field descriptors cannot be recovered or warned about);
> the recovery scan is capped at 2,000,000 events per call; the warning is emitted at most
> once per recording change (switching between recordings may re-warn for a previously warned
> recording). A type whose events all occur beyond the recovery-scan cap is reported as missing
> for the rest of the session (the miss is cached). For exact per-chunk metadata, prefer the
> Jafar backend.

## Writing a Custom Backend

### Prerequisites

- Java 21 or later
- Gradle or Maven build system
- Familiarity with Java's ServiceLoader mechanism

### Step 1: Add Dependencies

Your backend needs `jfr-shell` as a compile-only dependency (Gradle shown; Maven users add equivalent `<scope>provided</scope>`):

```gradle
plugins {
    id 'java-library'
}

dependencies {
    // Compile-only: your JAR doesn't bundle jfr-shell
    compileOnly 'io.btrace:jfr-shell:0.9.0'
}
```

### Step 2: Implement the JfrBackend Interface

```java
package com.example.backend;

import io.jafar.shell.backend.*;
import java.util.EnumSet;
import java.util.Set;

public final class MyBackend implements JfrBackend {

    private static final Set<BackendCapability> CAPABILITIES = EnumSet.of(
        BackendCapability.EVENT_STREAMING,
        BackendCapability.METADATA_CLASSES,
        BackendCapability.UNTYPED_HANDLERS
    );

    @Override
    public String getId() {
        return "mybackend";  // Unique identifier
    }

    @Override
    public String getName() {
        return "My Custom Backend";  // Display name
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public int getPriority() {
        return 75;  // Higher = preferred (Jafar=100, JDK=50)
    }

    @Override
    public Set<BackendCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public BackendContext createContext() {
        // BackendContext holds shared resources (caches, parsers) across sessions.
        // Return a simple empty implementation if you don't need context reuse.
        return new MyBackendContext();
    }

    @Override
    public EventSource createEventSource(BackendContext context) {
        return new MyEventSource(context);
    }

    @Override
    public MetadataSource createMetadataSource() {
        return new MyMetadataSource();
    }

    @Override
    public ChunkSource createChunkSource() throws UnsupportedCapabilityException {
        throw new UnsupportedCapabilityException(BackendCapability.CHUNK_INFO, getId());
    }

    @Override
    public ConstantPoolSource createConstantPoolSource() throws UnsupportedCapabilityException {
        throw new UnsupportedCapabilityException(BackendCapability.CONSTANT_POOLS, getId());
    }
}
```

### Step 3: Implement Required Sources

#### EventSource (Required)

Streams events from JFR recordings. Call `consumer.accept()` for each event with the event type name and a `Map<String, Object>` of field values:

```java
public final class MyEventSource implements EventSource {

    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
        // Parse recording and emit events
        // Event constructor: Event(String typeName, Map<String, Object> fields)
        try (var parser = createParser(recording)) {
            parser.forEach(event -> {
                Map<String, Object> fields = extractFields(event);
                consumer.accept(new Event(event.getTypeName(), fields));
            });
        }
    }
}
```

#### MetadataSource (Required for METADATA_CLASSES)

Provides type and field information:

```java
public final class MyMetadataSource implements MetadataSource {

    @Override
    public List<Map<String, Object>> loadAllClasses(Path recording) throws Exception {
        // Return list of class metadata maps
        // Each map should contain: id, name, superType, fields, annotations, etc.
    }

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
        // Return metadata for a specific class, or null if not found
    }

    @Override
    public Map<String, Object> loadField(Path recording, String typeName, String fieldName)
            throws Exception {
        // Return metadata for a specific field, or null if not found
    }
}
```

#### ChunkSource (Optional - for CHUNK_INFO)

Provides chunk-level information:

```java
public final class MyChunkSource implements ChunkSource {

    @Override
    public List<Map<String, Object>> loadAllChunks(Path recording) throws Exception {
        // Return list of chunk metadata with: index, offset, size, startNanos, duration, etc.
    }

    @Override
    public Map<String, Object> loadChunk(Path recording, int chunkIndex) throws Exception {
        // Return metadata for specific chunk
    }

    @Override
    public List<Map<String, Object>> loadChunks(Path recording,
            Predicate<Map<String, Object>> filter) throws Exception {
        // Return filtered chunks
    }

    @Override
    public Map<String, Object> getChunkSummary(Path recording) throws Exception {
        // Return summary: totalChunks, totalSize, avgSize, minSize, maxSize
    }
}
```

#### ConstantPoolSource (Optional - for CONSTANT_POOLS)

Provides constant pool access:

```java
public final class MyConstantPoolSource implements ConstantPoolSource {

    @Override
    public List<Map<String, Object>> loadSummary(Path recording) throws Exception {
        // Return CP type summary with name and totalSize
    }

    @Override
    public Set<String> getAvailableTypes(Path recording) throws Exception {
        // Return set of constant pool type names
    }

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName)
            throws Exception {
        // Return all entries for a CP type
    }

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName,
            Predicate<Map<String, Object>> filter) throws Exception {
        // Return filtered entries
    }
}
```

### Step 4: Register via ServiceLoader

Create `META-INF/services/io.jafar.shell.backend.JfrBackend`:

```
com.example.backend.MyBackend
```

### Step 5: Build the JAR

```bash
./gradlew jar
# Output: build/libs/my-backend-1.0.0.jar
```

## Using the TCK (Technology Compatibility Kit)

The TCK validates that your backend implementation conforms to expected behavior.

### Building the TCK

```bash
./gradlew :jfr-shell-tck:shadowJar
# Output: jfr-shell-tck/build/libs/jfr-shell-tck-*-all.jar
```

### Running the TCK

```bash
# Basic usage with built-in test file (2.3MB)
java -jar jfr-shell-tck-all.jar path/to/my-backend.jar

# With custom test recording
java -jar jfr-shell-tck-all.jar path/to/my-backend.jar path/to/test.jfr
```

### TCK Test Categories

The TCK runs 34 tests across these categories:

| Category | Tests | Description |
|----------|-------|-------------|
| Identity | 4 | Valid ID, name, version, priority |
| Capabilities | 2 | Capability set consistency |
| Event Streaming | 4 | Events are properly streamed |
| Metadata | 9 | Classes and fields load correctly; duplicates and determinism are asserted |
| Chunks | 4 | Chunk info is accurate (if supported) |
| Constant Pools | 11 | CP access works, and the available types, summary, entry listing and `crossref` agree on every run (if supported) |

### Understanding TCK Output

```
=== JFR Shell Backend TCK ===
Backend JAR: my-backend-1.0.0.jar
Test recording: /tmp/tck-test-123.jfr

Loaded backend: My Custom Backend (id=mybackend)
Capabilities: [EVENT_STREAMING, METADATA_CLASSES, UNTYPED_HANDLERS]

=== TCK Results ===
Tests run: 34
Passed: 34
Failed: 0
Skipped: 0
```

Tests for unsupported capabilities return without checking anything, and the summary counts them as **passed**, not skipped (e.g., if your backend doesn't support `CHUNK_INFO`, the chunk tests pass trivially). Read `Capabilities:` in the output to know which tests actually ran.

### Common TCK Failures

| Failure | Cause | Fix |
|---------|-------|-----|
| `backendHasValidId` | Empty or null ID | Return non-empty string from `getId()` |
| `backendHasReasonablePriority` | Priority outside 0-1000 | Use priority between 0-1000 |
| `eventSourceStreamsEvents` | No events emitted | Ensure `streamEvents()` calls consumer |
| `metadataSourceLoadsAllClasses` | Empty or null result | Return non-empty list from `loadAllClasses()` |
| `capabilitiesMatchBehavior` | Declared capability but throws | Either implement the source or remove capability |

### Memory Considerations

The built-in test file is intentionally small (2.3MB) to accommodate backends with higher memory overhead. If your backend can handle larger files, provide your own test recording:

```bash
java -jar jfr-shell-tck-all.jar my-backend.jar large-recording.jfr
```

## Project Structure

```
jfr-shell/                          # Core shell and SPI
├── src/main/java/io/jafar/shell/backend/
│   ├── JfrBackend.java             # Main SPI interface
│   ├── BackendCapability.java      # Capability enum
│   ├── BackendContext.java         # Resource sharing context
│   ├── EventSource.java            # Event streaming interface
│   ├── MetadataSource.java         # Metadata query interface
│   ├── ChunkSource.java            # Chunk info interface
│   └── ConstantPoolSource.java     # Constant pool interface

jfr-shell-jafar/                    # Jafar backend plugin
jfr-shell-jdk/                      # JDK API backend plugin
jfr-shell-tck/                      # Technology Compatibility Kit
```

## When a provider jar is broken

A backend jar that fails to load — missing class, failing static initializer, unreadable services
file — is skipped at discovery time and does not take down the shell or the other backends. Such
providers are reported, not silently dropped:

- `backend list` shows a **Failed to load** section naming each skipped provider and the reason
  (the same output appears when startup fails with "No JFR backends available").
- A provider whose `getId()` or `getPriority()` throws is dropped the same way — those calls are
  needed to register the backend at all.
- `jfr-shell` logs one `WARN` line per discovery pass, summarizing the skipped providers (up to 5
  named, then a count).

Fix: reinstall or delete the jar. Plugin jars live in `~/.jfr-shell/plugins` (override with
`JFRSHELL_PLUGIN_DIR`), then restart the shell.

## Offline/Airgapped Installation

For environments without internet access (Docker images, air-gapped systems), plugins can be manually installed from local JAR files:

```bash
# Install a backend plugin from a local JAR
java -jar jfr-shell.jar --install-plugin /path/to/jfr-shell-jdk-1.0.0.jar

# The JAR filename must follow the convention: {artifactId}-{version}.jar
# Example: jfr-shell-jdk-1.0.0.jar → pluginId "jdk"
```

### Requirements

1. **ServiceLoader config**: The JAR must contain `META-INF/services/io.jafar.shell.backend.JfrBackend`
2. **Filename format**: `{artifactId}-{version}.jar` (e.g., `jfr-shell-jdk-1.0.0.jar`)
3. **Optional pom.properties**: If present in `META-INF/maven/`, the groupId is extracted; otherwise defaults to `io.btrace`

### Docker Example

```dockerfile
# Pre-install backend plugin during image build
FROM eclipse-temurin:21-jre

COPY jfr-shell.jar /app/
COPY jfr-shell-jdk-1.0.0.jar /tmp/

# Install the plugin
RUN java -jar /app/jfr-shell.jar --install-plugin /tmp/jfr-shell-jdk-1.0.0.jar && \
    rm /tmp/jfr-shell-jdk-1.0.0.jar

ENTRYPOINT ["java", "-jar", "/app/jfr-shell.jar"]
```

### Custom Plugin Directory

Use `--plugin-dir` to specify an alternative plugin directory:

```bash
java -jar jfr-shell.jar --plugin-dir /custom/plugins --install-plugin backend.jar
```

## Best Practices

1. **Declare only supported capabilities** - Don't claim `CHUNK_INFO` if you can't provide chunk data
2. **Use streaming parsers** - Avoid loading entire recordings into memory
3. **Reuse context when possible** - If your parser supports it, implement `CONTEXT_REUSE`
4. **Handle missing data gracefully** - Return null or empty collections, don't throw for missing types
5. **Test with the TCK** - Run the TCK before releasing your backend
6. **Use appropriate priority** - Don't use priority > 100 unless your backend should be preferred over Jafar

## API Compatibility

The backend plugin API is stable and follows semantic versioning. See [PluginAPICompatibility.md](PluginAPICompatibility.md) for details on:
- What changes break compatibility
- Safe evolution patterns
- Version requirements
- Automated enforcement with japicmp

## See Also

- [Backend Quickstart (10 minutes)](BackendQuickstart.md) - Build a working backend fast
- [JFR Shell Usage Guide](Usage.md)
- [JfrPath Query Language](JFRPath.md)
- [JFR Shell Tutorial](Tutorial.md)
