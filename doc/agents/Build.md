# Building and testing

Prerequisites, commands, and the Go parser's separate toolchain.
See [Verification.md](Verification.md) for how to know a change actually works.

## Prerequisites
- Java 25+ (shell and MCP modules: `shell-core`, `jfr-shell`, `jfr-mcp`, `hdump-shell`, `pprof-shell`, `otlp-shell`)
- Java 8+ (parser and tools modules: `parser-core`, `tools`, `demo`)
- Binary test recordings are fetched via `./get_resources.sh` (downloads from Dropbox), not Git LFS — see below

## Essential Commands
```bash
# Fetch binary test resources (required before first build)
./get_resources.sh

# Build all modules
./gradlew build

# Build shadow JARs for all modules
./gradlew shadowJar

# Run tests
./gradlew test

# Run tests with verbose output
./gradlew test --info

# Run a specific test class
./gradlew :parser-codegen:test --tests "io.jafar.parser.TypedJafarParserTest"

# Run demo application
java -jar demo/build/libs/demo-all.jar [jafar|jmc|jfr|jfr-stream] /path/to/recording.jfr

# Run JFR Shell (Interactive JFR Analysis)
./gradlew :jfr-shell:run --console=plain

# Rebuild the gradle plugin
./rebuild_plugin.sh

# Code formatting (Spotless)
./gradlew spotlessApply

# Check formatting
./gradlew spotlessCheck

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Go parser Commands
The `go-parser/` directory is a standalone Go module and is deliberately kept out of the Gradle
build; `./gradlew build` neither builds nor tests it.

```bash
cd go-parser
go test ./...            # unit tests plus the JFR recordings checked into the repo
go test -bench . ./...   # throughput benchmarks
go vet ./...
gofmt -l .               # must print nothing
```

Only the untyped parser is ported. The typed API depends on run-time bytecode generation for
interfaces discovered at run time and has no Go equivalent - do not attempt to port it.

The Go and Java untyped parsers must be kept at parity - see the parity rule under **Rules** below
before changing either of them.
Benchmarks need real recordings, and the large ones are not in the repository - `./get_resources.sh`
downloads them. The **Go Parser Benchmarks** workflow (`.github/workflows/go-parser-bench.yml`) runs
them where that download works: on demand (`workflow_dispatch`, with inputs for benchtime, count,
benchmark pattern and an optional baseline ref to diff against via benchstat), weekly, and on pushes
to `main` that touch `go-parser/`. It caches the recordings on the hash of `get_resources.sh`, runs
the correctness tests against them before benchmarking, and publishes the numbers to the job summary
plus an artifact. Do not add the recording download to the fast per-PR job; it would slow every PR
for numbers that are too noisy to gate on.

`workflow_dispatch` only works for workflows that already exist on the default branch, so to
benchmark a branch that has not been merged yet, push it as `bench/<something>` - the workflow also
triggers on any `bench/**` branch.

## Module-specific Commands
```bash
# Build only the parser core module
./gradlew :parser-core:build

# Build only the demo
./gradlew :demo:build

# Run the demo application directly
./gradlew :demo:run --args="jafar /path/to/recording.jfr"
```
