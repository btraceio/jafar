# Known Limitations

This document describes known limitations and incomplete features in JAFAR.

## Type Inheritance Not Supported

**Status**: Not implemented (planned for v0.2.0)

**Description**: JAFAR does not currently support JFR type inheritance. When a JFR event type extends another event type in the recording's metadata, only the fields defined directly on the subtype are accessible.

**Impact**:
- Fields from parent event types are not accessible through typed parser interfaces
- `@JfrType` annotations must match the exact type, not parent types
- Workaround: Use the untyped parser API to access all fields as a flat map

**Example**:
```java
// JFR metadata defines:
// class BaseEvent { long startTime; }
// class MyEvent extends BaseEvent { String data; }

@JfrType("MyEvent")
interface MyEvent {
  String data();     // ✅ Works
  // long startTime(); // ❌ Not accessible (field from parent)
}

// Workaround: Use untyped API
parser.handle((type, value, ctl) -> {
  if ("MyEvent".equals(type.getName())) {
    Object data = value.get("data");      // ✅ Works
    Object startTime = value.get("startTime"); // ✅ Works (fields are flattened)
  }
});
```

**Technical Notes**:
- Code generation (CodeGenerator.java) contains TODOs for inheritance support
- See: `// TODO: ignore inheritance for now` in CodeGenerator.java
- Metadata correctly parses superType but it's not used during deserialization

**Tracking**: This limitation will be addressed in a future release.

## ParsingContext Reuse Requirements

**Status**: Working as designed, but has subtle requirements

**Description**: When reusing a `ParsingContext` across multiple JFR files, the files must have compatible metadata (same event type definitions).

**Impact**:
- Reusing context with incompatible recordings may cause parsing errors
- No automatic validation of metadata compatibility

**Workaround**: Only reuse contexts when parsing recordings from the same JVM or recordings with identical event schemas.

**Example**:
```java
// Safe: Same JVM, consecutive recordings
ParsingContext ctx = ParsingContext.create();
try (TypedJafarParser p1 = TypedJafarParser.open("recording1.jfr", ctx)) {
  p1.run();
}
try (TypedJafarParser p2 = TypedJafarParser.open("recording2.jfr", ctx)) {
  p2.run(); // ✅ Safe if recordings have identical schemas
}

// Unsafe: Different JVMs or versions
try (TypedJafarParser p3 = TypedJafarParser.open("different-jvm.jfr", ctx)) {
  p3.run(); // ⚠️ May fail if schemas differ
}
```

## Internal API Exposure

**Status**: Design limitation for v0.x

**Description**: Some public APIs expose types from the `io.jafar.parser.internal_api` package, such as `MetadataClass` in `UntypedJafarParser.EventHandler`.

**Impact**:
- User code may inadvertently depend on internal APIs
- Internal APIs are subject to change without notice in v0.x releases
- No compile-time enforcement of API boundaries (no module-info.java)

**Mitigation**:
- Avoid explicitly importing `internal_api` types in your code
- Use type inference (var, lambdas) where possible
- Public API methods handle internal types, so you rarely need to reference them directly

**Example**:
```java
// ❌ Avoid: Explicitly importing internal types
import io.jafar.parser.internal_api.metadata.MetadataClass;
p.handle((MetadataClass type, Map<String, Object> value, Control ctl) -> { ... });

// ✅ Prefer: Use type inference
p.handle((type, value, ctl) -> { ... });
```

**Future**: A stable public metadata API will be provided in v1.0.

## Thread Safety Considerations

**Status**: Mostly thread-safe with known gaps

**Description**: JAFAR parsers are generally thread-safe, but there are some known edge cases:

**Limitations**:
1. Parser instances (`TypedJafarParser`, `UntypedJafarParser`) are **not** thread-safe and should not be shared across threads
2. `ParsingContext` instances **are** thread-safe and can be shared
3. Handlers are called sequentially on the parser thread - concurrent handler execution is not supported

**Example**:
```java
// ✅ Safe: Separate parser instances
ParsingContext ctx = ParsingContext.create(); // Thread-safe
ExecutorService exec = Executors.newFixedThreadPool(4);
for (Path file : files) {
  exec.submit(() -> {
    try (TypedJafarParser p = TypedJafarParser.open(file, ctx)) {
      p.run(); // ✅ Each parser on its own thread
    }
  });
}

// ❌ Unsafe: Sharing parser instance
TypedJafarParser p = TypedJafarParser.open("recording.jfr");
ExecutorService exec = Executors.newFixedThreadPool(4);
exec.submit(() -> p.run()); // ❌ Don't share parser across threads
exec.submit(() -> p.run()); // ❌ Don't share parser across threads
```

## Large File Handling

**Status**: Supported with caveats

**Description**: JAFAR can handle very large (multi-GB) JFR files, but:

**Limitations**:
1. Memory usage scales with the size of constant pools and metadata
2. No streaming API for constant pool values (they are cached in memory)
3. Extremely large constant pools (millions of unique values) may cause high memory pressure

**Recommendations**:
- For very large files, increase heap size: `-Xmx4g` or higher
- Monitor memory usage during parsing
- Consider splitting recordings if memory is constrained

## JDK Version Support

**Status**: Working as designed

**Description**: JAFAR is packaged as a Multi-Release JAR supporting Java 8, 9, 13, and 21.

**Limitations**:
- Java 8 receives minimal performance optimizations (no VarHandles, no value types)
- Optimal performance requires Java 13+ for vector operations and Java 21 for string templates
- JFR file format compatibility: JAFAR can parse recordings from JDK 8+ but may not understand new event types added in later JDKs

**Recommendation**: Use Java 21 for best performance.

## Performance Characteristics

**Status**: Anecdotal benchmarks only

**Description**: Performance claims are based on informal testing. Comprehensive benchmarks are not yet published.

**Limitations**:
- No CI-based performance regression testing
- JMH benchmarks exist but results are not documented
- Performance may vary significantly based on JFR file characteristics

**Tracking**: Performance baseline documentation planned for v0.2.0.

---

## Reporting Issues

If you encounter limitations not documented here, please report them at:
https://github.com/jbachorik/jafar/issues

When reporting, please include:
- JAFAR version
- JDK version
- Sample JFR file (if possible)
- Code snippet demonstrating the issue
