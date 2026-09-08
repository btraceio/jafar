# Jafar MCP Server

MCP (Model Context Protocol) server for JFR analysis — enables AI agents to analyze Java Flight Recordings.

## Quick Install

```bash
curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | bash
```

This installs [JBang](https://www.jbang.dev) (if needed) and the `jfr-mcp` command in one step.

For development snapshots:

```bash
curl -Ls https://raw.githubusercontent.com/btraceio/jafar/main/jfr-mcp/install.sh | JFR_MCP_DEV=1 bash
```

## Usage

```bash
jfr-mcp --stdio          # STDIO mode (Claude Desktop / Claude Code)
jfr-mcp                  # HTTP  mode on port 3000
jfr-mcp -Dmcp.port=8080  # Custom port
```

## Integration

### Claude Code

```bash
claude mcp add jafar -- jbang jfr-mcp@btraceio --stdio
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "jafar": {
      "command": "jbang",
      "args": ["jfr-mcp@btraceio", "--stdio"]
    }
  }
}
```

## Available Tools

The server exposes 37 tools across four artifact formats. Every family shares the same
session model: `*_open` returns a session id, other tools default to the most recently
opened session, and `*_close` releases it. Sessions of different formats can be open at the
same time, which is what makes cross-format correlation possible.

### JFR recordings

| Tool | Description |
|------|-------------|
| `jfr_open` | Open a JFR recording file |
| `jfr_close` | Close a recording session |
| `jfr_list_types` | List available event types |
| `jfr_query` | Execute JfrPath queries |
| `jfr_help` | JfrPath query language docs |
| `jfr_summary` | Recording overview |
| `jfr_diagnose` | Automated diagnosis: runs the USE and TSA analyses and returns merged, severity-ranked findings plus capability gaps |
| `jfr_compare` | Compare a candidate recording against a baseline: duration-normalised rates and per-frame self-time deltas |
| `jfr_flamegraph` | Aggregated stack trace data |
| `jfr_callgraph` | Caller-callee relationship graph |
| `jfr_stackprofile` | Frames with self/total shares, time buckets and per-thread counts |
| `jfr_hotmethods` | CPU-intensive method identification |
| `jfr_exceptions` | Exception pattern analysis |
| `jfr_use` | USE Method analysis (Utilization, Saturation, Errors) |
| `jfr_tsa` | Thread State Analysis |

### Heap dumps (HPROF)

| Tool | Description |
|------|-------------|
| `hdump_open` | Open an HPROF heap dump |
| `hdump_close` | Close one or all heap dump sessions |
| `hdump_query` | Execute HdumpPath queries (retained sizes, GC root paths, leak detectors, clusters, waste, cross-session joins) |
| `hdump_summary` | Fast overview without computing retained sizes |
| `hdump_report` | Heap health report with severity-ranked findings |
| `hdump_help` | HdumpPath query language docs |

### pprof profiles

| Tool | Description |
|------|-------------|
| `pprof_open` / `pprof_close` | Session management |
| `pprof_query` | Execute PprofPath queries |
| `pprof_summary` | Profile overview |
| `pprof_flamegraph` | Aggregated stack data |
| `pprof_hotmethods` | Top leaf functions by self cost |
| `pprof_tsa` | Thread state analysis (heuristic: states are inferred from function names) |
| `pprof_use` | USE method analysis |
| `pprof_help` | PprofPath query language docs |

### OpenTelemetry profiles

| Tool | Description |
|------|-------------|
| `otlp_open` / `otlp_close` | Session management |
| `otlp_query` | Execute OtlpPath queries |
| `otlp_summary` | Profile overview |
| `otlp_flamegraph` | Aggregated stack data |
| `otlp_use` | USE method analysis |
| `otlp_help` | OtlpPath query language docs |

## Prompts and Resources

Besides tools, the server offers MCP prompts and resources.

**Prompts** are analysis playbooks — `triage`, `compare`, `leak-hunt`, `latency`. In Claude
Code they appear as `/mcp__jafar__<name>` slash commands.

**Resources** are readable context: `jafar://sessions` lists what is currently open with
ids and aliases, and `jafar://help/jfrpath`, `jafar://help/hdumppath` and
`jafar://help/tools` serve the query-language and tool-selection references.

## Claude Code plugin

For a guided workflow — methodology skills and specialist analysis subagents on top of these
tools — install the bundled plugin, which also registers this server for you:

```
/plugin marketplace add btraceio/jafar-perf
/plugin install jafar-perf@btraceio
```

See [btraceio/jafar-perf](https://github.com/btraceio/jafar-perf).

## Build from Source

```bash
./gradlew :jfr-mcp:shadowJar
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar --stdio
```

Requires Java 25+.

## Documentation

- **[Tutorial](../doc/mcp/Tutorial.md)** — Full MCP server guide
- **[JBang Usage](../doc/mcp/JBANGUsage.md)** — Detailed JBang installation and usage
- **[JfrPath Reference](../doc/cli/JFRPath.md)** — Query language syntax
