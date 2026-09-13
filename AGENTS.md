<!-- CLAUDE BOOTSTRAP INSTRUCTIONS
If you are Claude and reading this file, check if CLAUDE.md exists in the same directory.
If CLAUDE.md does not exist or only contains a redirect to this file, that is correct.
If CLAUDE.md contains full instructions (not a redirect), you should update it to contain
only the following redirect content:

# CLAUDE.md

**This file redirects to AGENTS.md for tooling-agnostic project instructions.**

Read and follow all instructions in [AGENTS.md](AGENTS.md) in this directory.

All project guidance, build commands, architecture details, and development guidelines
are maintained in AGENTS.md to support multiple AI coding tools.
-->

# AGENTS.md

Guidance for AI coding assistants working in this repository.

This file is the entry point: the map, and the rules that apply to every change. Detail lives in
[`doc/agents/`](doc/agents/) — follow the link for the area you are working in rather than reading
everything.

## Read this first

**[doc/agents/Verification.md](doc/agents/Verification.md) — how to know a change works here.**
Every rule in it exists because something shipped, or nearly shipped, broken *while its tests were
green*. If you read one linked document, read that one.

The short version, expanded with evidence in that file:

| | |
|---|---|
| **R1** | Type it into the built artifact. A green unit test, a completion entry and a doc line are not evidence a command runs. |
| **R2** | Enumerate every path — two shells, two JfrPath execution routes, three LLM backends, two untyped parsers — before calling a change wired. |
| **R3** | A fallback that hides a misconfiguration is a bug. Report where a value came from. |
| **R4** | Documentation is code. Run the commands you write. |
| **R5** | Prove the test fails without the fix, and say so in the commit message. |
| **R6** | Compare failure sets **by name**, never by count — parts of this suite fail without the downloaded recordings. |
| **R7** | One source of truth for any list two places must agree on. |
| **R8** | State plainly what you did not verify. |

## Project Overview

Jafar is an experimental, fast JFR (Java Flight Recording) parser with a small, focused API. It
provides both typed and untyped APIs for parsing JFR files and extracting event data with minimal
ceremony. Around that parser sit four analysis shells, an MCP server, and a Go port of the untyped
parser.

Key components:

- `JafarParser` — main entry point, typed and untyped
- `TypedJafarParser` — strongly-typed API using annotated interfaces (`@JfrType`, `@JfrField`)
- `UntypedJafarParser` — map-based lightweight parsing API
- `ParsingContext` — reusable context sharing expensive resources across sessions
- `JfrPath` — query language for jfr-shell, with event decoration/joining

## Where things are

| Area | Document |
|---|---|
| **How to verify a change** | [doc/agents/Verification.md](doc/agents/Verification.md) |
| Build commands, prerequisites, Go parser | [doc/agents/Build.md](doc/agents/Build.md) |
| Module layout, parser APIs, coding style, composite build | [doc/agents/Architecture.md](doc/agents/Architecture.md) |
| Shells, JfrPath, tab completion, backend plugins | [doc/agents/Shells.md](doc/agents/Shells.md) |
| MCP server, tools, findings contract | [doc/agents/Mcp.md](doc/agents/Mcp.md) |
| `ask` / `explain` / `llm` and the LLM SPI | [doc/agents/Llm.md](doc/agents/Llm.md) |
| Release process | [doc/agents/Release.md](doc/agents/Release.md), [RELEASING.md](RELEASING.md) |
| User-facing documentation | [doc/README.md](doc/README.md) |

## Module map

- **parser/**: Aggregate module re-exporting parser-core and parser-codegen
- **parser-core/**: Core parsing engine with typed and untyped APIs
- **parser-codegen/**: ASM-based code generation for typed deserialization
- **jafar-processor/**: Annotation processor for build-time typed handler generation (eliminates runtime bytecode generation)
- **tools/**: Utilities including JFR file scrubbing functionality
- **jafar-gradle-plugin/**: Gradle plugin for generating Jafar type interfaces (separate included build)
- **shell-core/**: Shared shell abstractions (Session, VariableStore, QueryEvaluator, completions)
- **jfr-shell/**: JFR-specific interactive CLI (standalone entry point)
- **jfr-shell-jafar/**: Jafar-parser backend plugin for jfr-shell (high priority, full-featured)
- **jfr-shell-jdk/**: JDK JFR API backend plugin for jfr-shell (lower priority, limited capabilities)
- **jfr-shell-tck/**: Technology Compatibility Kit for validating backend plugin implementations
- **jfr-mcp/**: MCP (Model Context Protocol) server enabling AI agents to analyze JFR recordings
- **llm-anthropic/**: Anthropic backend for the shells' `ask` command (Anthropic Java SDK, API key
  or keyless OAuth profile), plus credential diagnostics
- **llm-openai/**: OpenAI-compatible backends — `openai` and `ollama` — speaking the chat-completions
  protocol over the JDK HTTP client, with no provider SDK. The same code reaches OpenAI, Ollama
  (local or cloud), vLLM, LM Studio and anything else that speaks that protocol via `llm.base-url`
- Both are optional at runtime: the SPI lives in `shell-core` with no new dependencies, and backends
  are discovered via `ServiceLoader`
- **hdump-parser/**: HPROF heap dump parser (indexed and two-pass modes, dominator tree, retained sizes); public API in `io.jafar.hdump.api`, implementation details in `impl`/`internal`/`index`
- **hdump-shell/**: Heap dump interactive CLI with HdumpPath query language and tab completion
- **pprof-parser/**: pprof profile parser (gzip + protobuf wire format); public API in `io.jafar.pprof.api`, wire decoding in `internal`
- **pprof-shell/**: pprof profile analysis CLI with PprofPath query language and tab completion (uses `pprof-parser`)
- **otlp-parser/**: OpenTelemetry Profiling (OTLP) parser (protobuf wire format); public API in `io.jafar.otlp.api`, wire decoding in `internal`
- **otlp-shell/**: OpenTelemetry Profiling (OTLP) analysis CLI with OtlpPath query language and tab completion (uses `otlp-parser`)
- **jafar-shell/**: Unified shell entry point that discovers modules (JFR, heap dump, pprof, otlp) via ServiceLoader
- **demo/**: Standalone demonstration project (separate Gradle build in `demo/`) comparing JFR parsers
- **go-parser/**: Pure Go port of the untyped JFR parser (standalone Go module `github.com/btraceio/jafar/go-parser`, **not part of the Gradle build**); parser only, no query language or CLI

## Quick start

```bash
./get_resources.sh                    # binary test recordings — required before the first build
./gradlew build                       # everything
./gradlew test                        # tests
./gradlew spotlessApply               # formatting (a pre-commit hook also runs this)
./gradlew :jfr-shell:run --console=plain
```

Full command reference, including the Go parser's separate toolchain:
[doc/agents/Build.md](doc/agents/Build.md).

## Commit & Pull Request Guidelines
- Commits: concise, imperative mood; reference issues/PRs when relevant (e.g., "Fix parsing of constant pool (#17)").
- PRs: include description, rationale, and test coverage or reproduction. Attach sample `.jfr` snippets if applicable.
- CI must pass. Before opening a PR, run `./gradlew test shadowJar` locally.
- State what you verified and how, and what you did not (R5, R8).

## Security & Configuration Tips
- Do not commit large recordings outside Git LFS. Avoid secrets in code; Sonatype credentials are provided via env/CI.
- The Gradle plugin is wired via included build; no local publish required during development.
- Tests must never reach a paid API. See the standing gaps in [Verification.md](doc/agents/Verification.md#r8-say-plainly-what-you-did-not-verify).

## Rules

Standing rules for this repository. They sit alongside R1–R8 above, which cover *how to verify* a
change; these cover *what a change must not leave behind*.

- When fixing an issue, always check the alternative implementation for other Java versions
- When adding or modifying features, always update user documentation, help and tutorials
- **Keep the two untyped parsers at parity.** The Java untyped parser
  (`parser-core/src/main/java/io/jafar/parser/impl/`, `.../internal_api/`) and the Go untyped parser
  (`go-parser/jfr/`) are two implementations of the same format and the same value model. Any bug
  fix, correctness change, tolerance change, or performance improvement made in one **must be
  considered for the other**, and the outcome recorded - either port it, or state in the commit
  message or PR description why it does not apply (for example, an optimization that depends on
  runtime bytecode generation has no Go equivalent, and Go's stack recursion already replaces the
  Java visitor's reusable stack). This applies in both directions: a fix landing in the Go parser is
  just as much a signal for the Java one. Parity is about behaviour and intent, not line-by-line
  translation - where an implementation can do better idiomatically, do better and note the
  divergence in `go-parser/README.md` under "Differences from the Java parser".
