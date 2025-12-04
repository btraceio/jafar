# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Core typed parser API** - Define Java interfaces annotated with `@JfrType` and `@JfrField` for type-safe JFR event parsing
- **Core untyped parser API** - Parse JFR events as `Map<String, Object>` without pre-defining interfaces
- **Multi-Release JAR support** - Single JAR optimized for Java 8, 9, 13, and 21 runtimes
- **Dual constant pool access** - Access both resolved values and raw CP indices for the same field via `@JfrField(raw = true)`
- **Chunk-based parallel processing** - Parse large JFR files efficiently using multi-threaded chunk processing (#5)
- **Control API** - Abort parsing early, access chunk metadata, and query stream position from event handlers
- **ParsingContext reuse** - Reuse parsing context across multiple files for improved performance
- **Gradle plugin** - Automatically generate JFR type interfaces from JFR recordings (`io.jafar.jafar-gradle-plugin`)
- **Scrubbing tool** - Redact sensitive field values from JFR recordings
- **JMH benchmarks** - Performance benchmarking infrastructure for parser optimization
- **CI/CD pipeline** - GitHub Actions workflows testing on JDK 8 and 21
- **Synthetic test generation** - Generate JFR test files programmatically for edge case validation
- **ChunkInfo API** - Expose chunk ID and metadata to typed and untyped parsers
- **Time conversion utilities** - Convert JFR ticks to wall-clock timestamps (#21)

### Changed
- **Optimized handler code generation** - Reworked ASM-based bytecode generation for better performance (#7)
- **Optimized metadata parsing** - Reduced allocation and improved parsing speed for JFR metadata sections
- **Optimized constant pool loading** - Lazy rehydration of constant pools for memory efficiency
- **Optimized string parsing** - Improved performance for string deserialization
- **Optimized deserialization** - Reduced allocations during event deserialization
- **More flexible string parsing** - Enhanced robustness for various string encodings
- **Improved time conversion API** - More user-friendly tick-to-timestamp conversion (#21)
- **Consistent Control usage** - Unified Control object handling across typed and untyped APIs
- **Java 8 compatibility** - Parser module now targets Java 8 bytecode (#20)

### Fixed
- **parseLongSWAR overflow** - Fixed NumberFormatException for negative long values in optimized string parsing (Dec 3, 2024)
- **Null pointer protection** - Added safety checks for malformed JFR files (Dec 2, 2024)
- **Erroneous skipper bytecode** - Fixed incorrect bytecode generation in skip paths (Dec 1, 2024)
- **Local variable index tracking** - Corrected LVT indices in recursive skip bytecode generation (Dec 1, 2024)
- **Recursive type generation** - Fixed infinite loops in Gradle plugin for recursive JFR types (Dec 1, 2024)
- **Raw pointer support** - Fixed handling of raw constant pool pointers (Aug 17, 2024)
- **Untyped parser thread safety** - Create new ParsingContext per chunk to avoid race conditions (#17)
- **BytePackingTest** - Fixed test failures for byte packing utilities (Aug 12, 2024)

### Security
- **Large file handling** - Added support for very large JFR files (multi-GB)
- **Thread safety improvements** - Enhanced concurrency safety in parser internals

## Version History

This is the initial release tracking. Version 0.1.0 will be the first public release.

[Unreleased]: https://github.com/jbachorik/jafar/compare/e696ef4...HEAD
