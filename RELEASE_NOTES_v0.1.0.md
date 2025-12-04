# JAFAR v0.1.0 - First Public Release

**Fast, modern JFR (Java Flight Recorder) parser for the JVM**

This is the first public release of JAFAR! 🎉

## What is JAFAR?

JAFAR is a high-performance JFR parser library that provides both typed (interface-based) and untyped (Map-based) APIs for parsing Java Flight Recorder files with minimal ceremony. It emphasizes performance, low allocation, and ease of use.

## Installation

### Maven

```xml
<dependency>
    <groupId>io.btrace</groupId>
    <artifactId>jafar-parser</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.btrace:jafar-parser:0.1.0'
```

## Quick Start

```java
import io.jafar.parser.api.*;
import java.nio.file.Paths;

@JfrType("jdk.ExecutionSample")
public interface ExecutionSample {
  long stackTraceId();
}

try (TypedJafarParser parser = TypedJafarParser.open("recording.jfr")) {
  parser.handle(ExecutionSample.class, (event, ctl) -> {
    System.out.println("Stack trace ID: " + event.stackTraceId());
  });
  parser.run();
}
```

## Release Highlights

### Core Features
- ✅ **Typed Parser API** - Define Java interfaces for type-safe event parsing
- ✅ **Untyped Parser API** - Parse events as `Map<String, Object>` without pre-defining interfaces
- ✅ **Multi-Release JAR** - Single JAR optimized for Java 8, 9, 13, and 21 runtimes
- ✅ **Dual Constant Pool Access** - Access both resolved values and raw CP indices for the same field
- ✅ **Parallel Chunk Processing** - Efficient multi-threaded parsing of large files
- ✅ **Control API** - Abort parsing, access chunk metadata, query stream position
- ✅ **ParsingContext Reuse** - Share parsing context across files for better performance

### Tools & Utilities
- ✅ **Gradle Plugin** - Generate typed interfaces from JFR recordings
- ✅ **Scrubbing Tool** - Redact sensitive field values from recordings
- ✅ **JMH Benchmarks** - Performance testing infrastructure

### Documentation
- 📚 **[CHANGELOG.md](CHANGELOG.md)** - Complete version history
- 📚 **[LIMITATIONS.md](LIMITATIONS.md)** - Known limitations and workarounds
- 📚 **[PERFORMANCE.md](PERFORMANCE.md)** - Benchmark results (~3s avg for typical files)
- 📚 **[CONTRIBUTING.md](CONTRIBUTING.md)** - Contribution guidelines
- 📚 **[SECURITY.md](SECURITY.md)** - Security policy

### Bug Fixes
- 🐛 Fixed `parseLongSWAR` overflow causing NumberFormatException for negative values
- 🐛 Fixed ExecutorService cleanup in StreamingChunkParser preventing thread leaks
- 🐛 Fixed null pointer protection for malformed JFR files
- 🐛 Fixed erroneous skipper bytecode generation
- 🐛 Fixed recursive type generation in Gradle plugin

## Performance

Based on JMH benchmarks on JDK 21 with G1GC:
- **Parse Entire Recording**: 2,958ms average (1.8 GB/s allocation rate)
- **Parse String-Heavy Events**: 3,501ms average (2.3 GB/s allocation rate)
- **Comparison**: ~3s with JAFAR vs ~7s with JMC (anecdotal)

See [PERFORMANCE.md](PERFORMANCE.md) for detailed results and tuning tips.

## API Status

**This is an early public release (v0.x)** - the API is functional and well-tested, but may evolve based on user feedback before reaching v1.0. We welcome contributions and suggestions!

### Known Limitations
- Type inheritance not yet supported (planned for v0.2.0)
- Some public APIs expose internal types (will be addressed in v1.0)
- Performance regression testing not yet in CI

See [LIMITATIONS.md](LIMITATIONS.md) for complete list and workarounds.

## Requirements

- **Java 21+** for development
- **Java 8+** for runtime (thanks to Multi-Release JAR)
- Git LFS for test resources

## Documentation

- **README**: https://github.com/jbachorik/jafar#readme
- **Examples**: `examples/` directory in the repository
- **Javadoc**: Comprehensive API documentation on all public classes

## Contributing

We welcome contributions! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

To report bugs or request features, use our [GitHub issue templates](https://github.com/jbachorik/jafar/issues/new/choose).

For security vulnerabilities, see [SECURITY.md](SECURITY.md) (do not create public issues).

## What's Next?

Planned for future releases:
- **v0.2.0**: Type inheritance support
- **v0.3.0**: API stabilization based on feedback
- **v1.0.0**: Stable API commitment with full documentation

## License

Apache License 2.0

## Acknowledgments

Built with:
- ASM for bytecode generation
- FastUtil for efficient collections
- SLF4J for logging
- JMH for benchmarking

---

**Full Changelog**: https://github.com/jbachorik/jafar/blob/v0.1.0/CHANGELOG.md
