# JFR Backend Quickstart (10 Minutes)

Build a minimal JFR Shell backend in 10 minutes. This tutorial creates a working backend that wraps the JDK's `jdk.jfr.consumer` API.

## What You'll Build

A backend named `mini` that:
- Streams events from JFR recordings
- Provides event type metadata
- Works with JFR Shell immediately

## Prerequisites

- Java 21+
- Gradle (or adapt for Maven)
- 10 minutes

## Step 1: Project Setup (1 minute)

Create project structure:

```bash
mkdir mini-backend && cd mini-backend
mkdir -p src/main/java/mini src/main/resources/META-INF/services
```

Create `build.gradle`:

```gradle
plugins {
    id 'java-library'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly 'io.btrace:jfr-shell:0.9.0'
}
```

## Step 2: Backend Class (3 minutes)

Create `src/main/java/mini/MiniBackend.java`:

```java
package mini;

import io.jafar.shell.backend.*;
import java.util.EnumSet;
import java.util.Set;

public final class MiniBackend implements JfrBackend {

    private static final Set<BackendCapability> CAPS = EnumSet.of(
        BackendCapability.EVENT_STREAMING,
        BackendCapability.METADATA_CLASSES,
        BackendCapability.STREAMING_PARSE,
        BackendCapability.UNTYPED_HANDLERS
    );

    @Override public String getId() { return "mini"; }
    @Override public String getName() { return "Mini Backend"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public int getPriority() { return 25; }
    @Override public Set<BackendCapability> getCapabilities() { return CAPS; }

    @Override
    public BackendContext createContext() {
        return new BackendContext() {
            private final long start = System.nanoTime();
            @Override public long uptime() { return System.nanoTime() - start; }
            @Override public void close() {}
        };
    }

    @Override
    public EventSource createEventSource(BackendContext ctx) {
        return new MiniEventSource();
    }

    @Override
    public MetadataSource createMetadataSource() {
        return new MiniMetadataSource();
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

## Step 3: Event Source (3 minutes)

Create `src/main/java/mini/MiniEventSource.java`:

```java
package mini;

import io.jafar.shell.backend.EventSource;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class MiniEventSource implements EventSource {

    @Override
    public void streamEvents(Path recording, Consumer<EventSource.Event> consumer) throws Exception {
        try (RecordingFile rf = new RecordingFile(recording)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent re = rf.readEvent();
                consumer.accept(new EventSource.Event(
                    re.getEventType().getName(),
                    extractFields(re)
                ));
            }
        }
    }

    private Map<String, Object> extractFields(RecordedEvent re) {
        Map<String, Object> fields = new HashMap<>();
        re.getEventType().getFields().forEach(field -> {
            try {
                fields.put(field.getName(), re.getValue(field.getName()));
            } catch (Exception e) {
                // Skip problematic fields
            }
        });
        return fields;
    }
}
```

## Step 4: Metadata Source (2 minutes)

Create `src/main/java/mini/MiniMetadataSource.java`:

```java
package mini;

import io.jafar.shell.backend.MetadataSource;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.*;

public final class MiniMetadataSource implements MetadataSource {

    @Override
    public List<Map<String, Object>> loadAllClasses(Path recording) throws Exception {
        List<Map<String, Object>> classes = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(recording)) {
            rf.readEventTypes().forEach(et -> {
                Map<String, Object> cls = new HashMap<>();
                cls.put("id", (long) et.getId());
                cls.put("name", et.getName());
                cls.put("label", et.getLabel());
                cls.put("description", et.getDescription());

                List<Map<String, Object>> fields = new ArrayList<>();
                et.getFields().forEach(f -> {
                    Map<String, Object> field = new HashMap<>();
                    field.put("name", f.getName());
                    field.put("typeName", f.getTypeName());
                    field.put("label", f.getLabel());
                    fields.add(field);
                });
                cls.put("fields", fields);
                classes.add(cls);
            });
        }
        return classes;
    }

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
        return loadAllClasses(recording).stream()
            .filter(c -> typeName.equals(c.get("name")))
            .findFirst()
            .orElse(null);
    }

    @Override
    public Map<String, Object> loadField(Path recording, String typeName, String fieldName)
            throws Exception {
        Map<String, Object> cls = loadClass(recording, typeName);
        if (cls == null) return null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) cls.get("fields");
        return fields.stream()
            .filter(f -> fieldName.equals(f.get("name")))
            .findFirst()
            .orElse(null);
    }
}
```

## Step 5: Register with ServiceLoader (30 seconds)

Create `src/main/resources/META-INF/services/io.jafar.shell.backend.JfrBackend`:

```
mini.MiniBackend
```

## Step 6: Build and Test (30 seconds)

```bash
# Build the JAR
./gradlew jar

# Test with JFR Shell TCK
java -jar /path/to/jfr-shell-tck-all.jar build/libs/mini-backend.jar

# Use with JFR Shell
JFRSHELL_BACKEND=mini java -jar /path/to/jfr-shell.jar recording.jfr
```

## Expected TCK Output

```
=== JFR Shell Backend TCK ===
Backend JAR: mini-backend.jar

Loaded backend: Mini Backend (id=mini)
Capabilities: [EVENT_STREAMING, METADATA_CLASSES, STREAMING_PARSE, UNTYPED_HANDLERS]

=== TCK Results ===
Tests run: 23
Passed: 23
Failed: 0
Skipped: 0
```

Chunk and constant pool tests are auto-skipped since we don't declare those capabilities.

## What's Next?

Your backend works! To enhance it:

| Feature | Add Capability | Implement |
|---------|---------------|-----------|
| Chunk access | `CHUNK_INFO` | `ChunkSource` |
| Constant pools | `CONSTANT_POOLS` | `ConstantPoolSource` |
| Context reuse | `CONTEXT_REUSE` | Cache in `BackendContext` |

See the [Backend Plugin Guide](Backends.md) for complete API reference.

## Complete Source

All files together: ~150 lines of Java.
