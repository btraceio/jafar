# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

## [0.6.0] - 2026-01-04

### Added
- **Expression support in select()** - Computed field expressions with arithmetic, string operations, and functions (#37)
  - Arithmetic operators: `+`, `-`, `*`, `/`
  - String concatenation: `+`
  - String templates: `"${field} text ${expr}"` for cleaner string interpolation
  - Built-in functions: `if()`, `upper()`, `lower()`, `substring()`, `length()`, `coalesce()`
  - Expression AST with BinExpr, FuncExpr, FieldRef, Literal, StringTemplate
  - Examples: `select(bytes / 1024 as kb)`, `select("${path} (${bytes} bytes)" as info)`
- **Multi-event type queries** - Query multiple event types simultaneously (#37)
  - Pipe-separated syntax: `events/(Type1|Type2|Type3)`
  - Works with all query operations (filters, aggregations, select)
  - Tab completion for multiple event types
  - Examples: `events/(jdk.FileRead|jdk.FileWrite) | count()`
- **CSV output format** - Export-friendly format with session-level setting (#37)
  - `set output csv` to set CSV as default format
  - CSV format with proper escaping and quoting
  - Override per-query with `--format` flag
- **Nested field projection** - Better handling of nested fields in select() (#37)
  - Nested fields preserve parent structure in output
  - Leaf segment used as column name for flat projections
  - Tab completion for nested fields in select()

### Changed
- **JFR Shell UX improvements** - Various completion and pager fixes (#37)
  - Fixed completion for `decorateByTime()` and `decorateByKey()` functions
  - Fixed script command hanging on pager prompts
  - Added filesystem completion for `record start` command
  - Added type IDs to metadata views

## [0.5.0] - 2026-01-03

### Added
- **JFR Shell scripting support** - Full scripting capabilities for automated JFR analysis (#34)
  - Variable system with type inference (numbers, strings, lists, maps, booleans, null)
  - Conditional execution (`if/elif/else/endif`) with boolean expressions and nested conditions
  - Command recording (`record/endrecord`) for creating reusable command sequences
  - Script execution with `.jfrs` files and embedded example scripts
  - Variable substitution using `$variable` or `${variable}` syntax
  - Non-interactive mode enhancements for scripting and CI integration
  - Example scripts: `basic-analysis.jfrs`, `gc-analysis.jfrs`, `thread-profiling.jfrs`
  - Comprehensive scripting documentation and tutorials

### Changed
- **JfrPath completion** - Enhanced completion for scripting variables and control flow
- **Shell state management** - Added variable store and conditional state tracking

## [0.4.0] - 2025-12-28

### Added
- **Build-time handler generation** - New annotation processor for compile-time handler generation as alternative to runtime bytecode generation (#33)
  - 85% allocation reduction with equivalent throughput
  - ServiceLoader auto-discovery of generated factories
  - Thread-local caching for reduced allocations
  - Backward compatible with runtime generation fallback
  - New `jafar-processor` module with annotation processor
  - New `HandlerFactory<T>` interface for factory pattern
- **Event decoration query language** - JFR Shell now supports joining and correlating events (#32)
  - `decorateByTime()` for temporal event joins on same thread
  - `decorateByKey()` for correlation by matching keys
  - Decorator fields accessible via `$decorator.` prefix
  - Memory-efficient lazy evaluation

### Changed
- **JFR Shell completion framework redesign** - Reduced ShellCompleter from 1300+ lines to ~220 lines using Strategy pattern (#32)
  - 12 specialized completers for different contexts
  - Centralized CompletionContextAnalyzer for testable context detection
  - MetadataService with caching for metadata lookups
  - Support for chunk IDs, metadata subprops, nested filter paths
  - 41 comprehensive completion tests

### Fixed
- **Metadata search** - Improved error reporting for metadata scan failures
- **Tilde expansion** - Fixed path expansion for user home directory

## [0.3.12] - 2025-12-17

### Changed
- **JBang Java requirement** - Updated to require Java 25+ to match jafar-shell compilation target

## [0.3.11] - 2025-12-17

### Changed
- **Removed JitPack publishing** - All artifacts now published exclusively to Maven Central and GitHub Packages
- **Artifact naming consistency** - Renamed `jfr-shell` to `jafar-shell` for consistency with other artifacts
- **JBang catalog improvements** - Updated to use Maven Central instead of JitPack, automatic updates on release

## [0.3.10] - 2025-12-17

### Changed
- **Publishing system migration** - Switched from legacy OSSRH (nexus-publish plugin) to new Sonatype Central Portal (vanniktech maven-publish plugin)
- **Automatic signing** - All artifacts are now automatically signed with GPG
- **Complete POM metadata** - All publications now include required Maven Central metadata (license, developers, SCM)

## [0.3.9] - 2025-12-17

### Fixed
- **Maven Central compliance** - Added sources and javadoc JARs to jfr-shell (required for Maven Central validation)
- **Sonatype publishing** - Fixed close and release to run in same Gradle invocation

## [0.3.8] - 2025-12-17

### Fixed
- **Sonatype publishing** - Added automatic close and release of staging repositories to Maven Central

## [0.3.7] - 2025-12-17

### Changed
- **JFR Shell publishing** - Now published to Maven Central (io.btrace:jfr-shell) for public JBang access
- **JFR Shell groupId** - Changed from io.jafar to io.btrace to match other artifacts and enable Sonatype publishing
- **JBang catalog** - Updated to use Maven Central coordinates (io.btrace:jfr-shell)

## [0.3.6] - 2025-12-17

### Added
- **Automated release workflow** - Complete CI/CD pipeline for publishing releases (#32)
  - Single-command release process triggered by version tags
  - Automatic publishing to Maven Central, GitHub Packages, and JitPack
  - Automatic JBang catalog updates
  - GitHub Release creation with changelog extraction

### Changed
- **Gradle toolchain auto-provisioning** - Added Foojay resolver for automatic JDK downloads
  - Fixes JitPack builds requiring multiple Java versions
  - Enables seamless multi-module builds with different Java requirements

### Fixed
- **GitHub Packages publishing** - Use specific publication task to avoid Sonatype credential conflicts
- **CI workflow separation** - Snapshot publishing (main branch) now separate from release publishing (tags)

## [0.3.0] - 2024-12-16

### Added
- **JFR Shell** - New interactive CLI tool for exploring and analyzing JFR recordings with JfrPath query language (#31)
  - JfrPath query language for navigating events, metadata, chunks, and constant pools
  - Tab completion, command history, and multi-session management
  - Aggregations (groupBy, top, sum, stats) and filtering with boolean expressions
  - Non-interactive mode for scripting and CI integration
  - JBang distribution support for easy installation
  - Standalone jlink distribution with bundled JRE
- **Comprehensive tutorials** - Added detailed tutorials for Typed API, Untyped API, and JFR Shell (#31)
- **Demo applications** - Added DatadogProfilerDemo showcasing both typed and untyped API usage patterns (#31)
- **JBang publishing workflow** - Automated GitHub Actions workflow for publishing to JitPack (#31)

### Changed
- **TypeGenerator naming convention** - Generated type interfaces now include the full namespace to prevent collisions. For example, `jdk.ExecutionSample` generates `JFRJdkExecutionSample` and `datadog.ExecutionSample` generates `JFRDatadogExecutionSample`. This ensures distinct interfaces when multiple events share the same simple name across different namespaces (#30)

### Fixed
- **Circular type resolution in TypeGenerator** - Fixed infinite recursion when generating types with circular references (#29)
- **Irregular attribute names** - Fixed handling of field names containing dots (e.g., `_dd.trace.operation`) by sanitizing them to valid Java identifiers (#29)
- **Type filters in Java 21 TypeGenerator** - Added support for event type filters in Java 21 version of TypeGenerator (#29)
- **Simple type constant resolution** - Fixed resolution of simple type constants in untyped parser's constant pool accessor (#29)
- **String type skipping** - Fixed type skipping for strings and simple types in constant pools (#29)

## [0.2.0] - 2024-12-14

### Added
- **Iterator-style API** - New `ParsingContext.iterateEvents()` method for streaming JFR events without loading entire file into memory (#25)
- **Metadata fingerprinting** - Handler class reuse across parsing sessions with same metadata fingerprint for improved performance (#26)

### Changed
- **Multi-tier untyped parser optimization** - Adaptive strategy system automatically switches between optimized and generic parsers based on event complexity (#27)

### Fixed
- **Typed parser simple type unwrapping** - Fixed null values for simple types like `jdk.types.Symbol` by implementing transitive closure for type filtering (#28)

## [0.1.0] - 2024-12-04

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

This is the first public release of JAFAR.

[Unreleased]: https://github.com/jbachorik/jafar/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/jbachorik/jafar/releases/tag/v0.2.0
[0.1.0]: https://github.com/jbachorik/jafar/releases/tag/v0.1.0
