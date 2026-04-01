# Jafar Documentation

This directory contains comprehensive documentation for the Jafar project, organized by functional area.

## Directory Structure

```
doc/
├── parser/              # Parser API Documentation
├── cli/                 # JFR Shell (CLI) Documentation
├── mcp/                 # MCP Server Documentation
├── jbang/               # JBang Distribution Documentation
├── performance/         # Performance Benchmarks and Reports
└── temp/                # Temporary/Work-in-Progress Documentation
```

---

## 📚 Parser API (`parser/`)

Documentation for Jafar's typed and untyped parsing APIs.

| Document | Description |
|----------|-------------|
| [TypedAPITutorial.md](parser/TypedAPITutorial.md) | Tutorial for strongly-typed JFR parsing with annotated interfaces |
| [unTypedAPITutorial.md](parser/unTypedAPITutorial.md) | Tutorial for flexible map-based JFR parsing |
| [MapVariables.md](parser/MapVariables.md) | Guide to using map data structures in scripts |

**Start here if you want to:**
- Parse JFR files programmatically in Java
- Build custom analysis tools
- Integrate JFR parsing into existing applications

---

## 🖥️ CLI / Interactive Shell (`cli/`)

Documentation for the interactive shell command-line interface (JFR, pprof, heap dump).

| Document | Description |
|----------|-------------|
| [Tutorial.md](cli/Tutorial.md) | Complete getting started guide for JFR Shell |
| [Usage.md](cli/Usage.md) | Command reference and usage patterns |
| [JFRPath.md](cli/JFRPath.md) | JfrPath query language reference |
| [Scripting.md](cli/Scripting.md) | Scripting guide for automation workflows |
| [Architecture.md](cli/Architecture.md) | Architecture overview with diagrams |
| [Backends.md](cli/Backends.md) | Backend plugin system and API reference |
| [BackendQuickstart.md](cli/BackendQuickstart.md) | Build a custom backend in 10 minutes |
| [CommandRecording.md](cli/CommandRecording.md) | Recording and replaying command workflows |
| [ScriptExecution.md](cli/ScriptExecution.md) | Executing scripts for batch analysis |
| [pprof-shell-tutorial.md](cli/pprof-shell-tutorial.md) | Tutorial for pprof profile analysis |
| [hdump-shell-tutorial.md](cli/hdump-shell-tutorial.md) | Tutorial for heap dump analysis |

**Start here if you want to:**
- Analyze JFR files interactively
- Analyze pprof profiles (async-profiler, Go, Rust)
- Analyze Java heap dumps
- Learn the query languages
- Automate analysis with scripts
- Build custom backend plugins

---

## 🤖 MCP Server (`mcp/`)

Documentation for the Model Context Protocol server for AI-assisted JFR analysis.

| Document | Description |
|----------|-------------|
| [Tutorial.md](mcp/Tutorial.md) | Complete MCP server setup and usage guide |
| [JBANGUsage.md](mcp/JBANGUsage.md) | JBang distribution guide for MCP server |
| [JBANGCatalogSetup.md](mcp/JBANGCatalogSetup.md) | Setting up external JBang catalog repository |

**Start here if you want to:**
- Use Claude Desktop to analyze JFR files
- Integrate JFR analysis with AI assistants
- Deploy the MCP server for web clients
- Distribute the MCP server via JBang

---

## 📦 JBang Distribution (`jbang/`)

Documentation for distributing Jafar tools via JBang.

| Document | Description |
|----------|-------------|
| [JFRShellUsage.md](jbang/JFRShellUsage.md) | Using JFR Shell via JBang (easiest installation) |

**Start here if you want to:**
- Install JFR Shell without building from source
- Use JFR Shell in CI/CD pipelines
- Pin to specific versions or use development snapshots

---

## ⚡ Performance (`performance/`)

Performance benchmarks and optimization reports.

| Document | Description |
|----------|-------------|
| [PerformanceReport.md](performance/PerformanceReport.md) | Comprehensive performance analysis and comparisons |
| [BuildTimeBenchmarks.md](performance/BuildTimeBenchmarks.md) | Build-time code generation benchmarks |

**Start here if you want to:**
- Understand Jafar's performance characteristics
- Compare Jafar with other JFR parsers
- Optimize your JFR analysis workflows

---

## 📝 Temporary (`temp/`)

Work-in-progress documentation and implementation notes.

**Note:** Documents in this directory may be incomplete, outdated, or moved elsewhere once finalized.

---

## Quick Navigation

### I want to...

**Parse JFR files programmatically:**
→ Start with [parser/TypedAPITutorial.md](parser/TypedAPITutorial.md) or [parser/unTypedAPITutorial.md](parser/unTypedAPITutorial.md)

**Analyze JFR files interactively:**
→ Start with [cli/Tutorial.md](cli/Tutorial.md)

**Analyze pprof profiles (async-profiler, Go, Rust):**
→ Start with [cli/pprof-shell-tutorial.md](cli/pprof-shell-tutorial.md)

**Learn the pprof query language:**
→ See [pprof-path-reference.md](pprof-path-reference.md)

**Analyze Java heap dumps:**
→ Start with [cli/hdump-shell-tutorial.md](cli/hdump-shell-tutorial.md)

**Use AI to analyze JFR files:**
→ Start with [mcp/Tutorial.md](mcp/Tutorial.md)

**Install via JBang (easiest):**
→ Start with [jbang/JFRShellUsage.md](jbang/JFRShellUsage.md) or [mcp/JBANGUsage.md](mcp/JBANGUsage.md)

**Learn the JFR query language:**
→ Start with [cli/JFRPath.md](cli/JFRPath.md)

**Automate JFR analysis:**
→ Start with [cli/Scripting.md](cli/Scripting.md)

**Build a custom backend:**
→ Start with [cli/BackendQuickstart.md](cli/BackendQuickstart.md)

**Understand performance:**
→ Start with [performance/PerformanceReport.md](performance/PerformanceReport.md)

---

## Contributing to Documentation

When adding new documentation:

1. **Choose the right directory:**
   - Parser API docs → `parser/`
   - CLI/Shell docs → `cli/`
   - MCP server docs → `mcp/`
   - Distribution guides → `jbang/`
   - Benchmarks/reports → `performance/`
   - Work-in-progress → `temp/`

2. **Use relative links:**
   - Same directory: `[Link](filename.md)`
   - Parent directory: `[Link](../filename.md)`
   - Other subdirectory: `[Link](../cli/filename.md)`

3. **Follow naming conventions:**
   - Tutorials: `*-Tutorial.md` or `Tutorial.md`
   - Guides: `*-guide.md` or descriptive names
   - References: `*-reference.md` or descriptive names
   - Use kebab-case (hyphens) for filenames

4. **Keep filenames concise:**
   - `Tutorial.md` instead of `jfr-shell-Tutorial.md` when in `cli/` directory
   - Context is clear from directory structure

---

## See Also

- [Main README](../README.md) - Project overview and quick start
- [CLAUDE.md](../CLAUDE.md) - Project configuration for Claude Code
- [CHANGELOG.md](../CHANGELOG.md) - Version history and release notes
- [RELEASING.md](../RELEASING.md) - Release process documentation
