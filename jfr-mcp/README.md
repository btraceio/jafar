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

| Tool | Description |
|------|-------------|
| `jfr_open` | Open a JFR recording file |
| `jfr_close` | Close a recording session |
| `jfr_list_types` | List available event types |
| `jfr_query` | Execute JfrPath queries |
| `jfr_help` | JfrPath query language docs |
| `jfr_summary` | Recording overview |
| `jfr_diagnose` | Comprehensive automated diagnosis |
| `jfr_flamegraph` | Aggregated stack trace data |
| `jfr_callgraph` | Caller-callee relationship graph |
| `jfr_hotmethods` | CPU-intensive method identification |
| `jfr_exceptions` | Exception pattern analysis |
| `jfr_use` | USE Method analysis (Utilization, Saturation, Errors) |
| `jfr_tsa` | Thread State Analysis |

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
