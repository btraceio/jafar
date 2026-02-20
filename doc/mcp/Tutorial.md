# MCP Server Tutorial

This tutorial teaches you how to use the Jafar MCP (Model Context Protocol) server to enable AI agents to analyze Java Flight Recordings. The MCP server exposes Jafar's JFR parsing capabilities through a standardized protocol that AI tools like Claude can use.

## Table of Contents
1. [What is MCP?](#what-is-mcp)
2. [Installation](#installation)
3. [Starting the Server](#starting-the-server)
4. [Available Tools](#available-tools)
5. [Using with Claude Desktop](#using-with-claude-desktop)
6. [Manual Testing](#manual-testing)
7. [Example Workflows](#example-workflows)
8. [Troubleshooting](#troubleshooting)

## What is MCP?

The Model Context Protocol (MCP) is a standard protocol for AI agents to interact with external tools and data sources. The Jafar MCP server exposes thirteen tools for JFR analysis:

**Core Tools:**
- **jfr_open** - Open a JFR recording file for analysis
- **jfr_list_types** - List available event types in a recording
- **jfr_query** - Execute JfrPath queries against the recording
- **jfr_close** - Close a recording session
- **jfr_help** - Get JfrPath query language documentation

**Analysis Tools:**
- **jfr_diagnose** - Comprehensive automated diagnosis with multi-dimensional analysis
- **jfr_summary** - Quick overview with duration, event counts, and key highlights
- **jfr_flamegraph** - Generate aggregated stack trace data for flamegraph-style analysis
- **jfr_callgraph** - Generate caller-callee relationship graph from stack traces
- **jfr_exceptions** - Analyze exception patterns and throw sites
- **jfr_hotmethods** - Identify CPU-intensive methods with sample counts
- **jfr_use** - USE Method analysis (Utilization, Saturation, Errors) for resource bottlenecks
- **jfr_tsa** - Thread State Analysis showing time distribution across thread states

This allows AI assistants to autonomously analyze JFR files, identify performance issues, and provide insights without manual intervention.

## Installation

### Via JBang (Recommended)

The simplest way to install and use the MCP server:

**Stable Release:**
```bash
# Install stable version
jbang app install jfr-mcp@btraceio

# Use anywhere
jfr-mcp                    # HTTP mode on port 3000
jfr-mcp --stdio            # STDIO mode for Claude Desktop
jfr-mcp -Dmcp.port=8080    # Custom port
```

**Development Snapshot (Latest Features):**
```bash
# Install development version
jbang app install jfr-mcp-dev@btraceio

# Use
jfr-mcp-dev                # HTTP mode with latest snapshot
jfr-mcp-dev --stdio        # STDIO mode with latest snapshot

# Force refresh to get absolute latest
jbang --fresh jfr-mcp-dev@btraceio
```

**Prerequisites:** JBang (installs automatically)
```bash
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

### Build from Source

For development or custom builds:

```bash
git clone https://github.com/btraceio/jafar.git
cd jafar
./gradlew :jfr-mcp:shadowJar
```

The server JAR will be created at `jfr-mcp/build/libs/jfr-mcp-*-all.jar`.

**Prerequisites:**
- Java 21 or later
- A JFR recording file to analyze

## Starting the Server

### Basic Usage

```bash
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar
```

The server starts on port 3000 by default:

```
18:06:06.791 [main] INFO  io.jafar.mcp.JafarMcpServer - Jafar MCP Server started at http://localhost:3000/mcp
18:06:06.791 [main] INFO  io.jafar.mcp.JafarMcpServer - SSE endpoint: http://localhost:3000/mcp/sse
18:06:06.791 [main] INFO  io.jafar.mcp.JafarMcpServer - Message endpoint: http://localhost:3000/mcp/message
```

### Custom Port

```bash
java -Dmcp.port=8080 -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar
```

### Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/mcp/sse` | Server-Sent Events connection (establishes session) |
| `/mcp/message` | JSON-RPC message endpoint |

## Available Tools

### jfr_open

Opens a JFR recording file for analysis.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `path` | string | Yes | Absolute path to the JFR recording file |
| `alias` | string | No | Optional alias for the session |

**Example Response:**
```json
{
  "id": 1,
  "path": "/path/to/recording.jfr",
  "openedAt": "2026-02-10T17:13:48.249772Z",
  "availableTypes": 224,
  "totalEvents": 0,
  "chunkCount": 2,
  "message": "Recording opened successfully"
}
```

### jfr_list_types

Lists all event types available in a JFR recording.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `sessionId` | string | No | Session ID or alias (uses current if not specified) |
| `filter` | string | No | Filter for event type names (case-insensitive) |
| `scan` | boolean | No | Scan recording to get accurate event counts (may be slow) |

**Example Response (with scan=true):**
```json
{
  "sessionId": 1,
  "recordingPath": "/path/to/recording.jfr",
  "totalTypes": 224,
  "totalEvents": 191675,
  "scanned": true,
  "eventTypes": [
    {"name": "datadog.ExecutionSample", "count": 96388},
    {"name": "datadog.Endpoint", "count": 69931},
    {"name": "jdk.ThreadCPULoad", "count": 1718}
  ]
}
```

### jfr_query

Executes a JfrPath query against the recording.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | Yes | JfrPath query expression |
| `sessionId` | string | No | Session ID or alias (uses current if not specified) |
| `limit` | integer | No | Maximum results to return (default: 100) |

**Common Query Patterns:**
```
events/jdk.ExecutionSample | count()
events/jdk.GCPhasePause | top(10)
events/jdk.FileRead | groupBy(path)
events/jdk.ThreadCPULoad | stats(user)
events/jdk.JavaMonitorEnter[duration > 10ms] | top(5)
```

**Example Response:**
```json
{
  "query": "events/datadog.ExecutionSample | count()",
  "sessionId": 1,
  "resultCount": 1,
  "results": [{"count": 96388}]
}
```

### jfr_close

Closes a JFR recording session.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `sessionId` | string | No | Session ID or alias to close |
| `closeAll` | boolean | No | Close all open sessions |

**Example Response:**
```json
{
  "success": true,
  "message": "Session 1 closed successfully",
  "remainingSessions": 0
}
```

### jfr_help

Returns JfrPath query language documentation. AI agents can call this tool to learn query syntax before constructing queries.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `topic` | string | No | Help topic (default: overview) |

**Available Topics:**
| Topic | Description |
|-------|-------------|
| `overview` | Basic syntax and quick examples |
| `filters` | Filter syntax, operators, and functions |
| `pipeline` | Aggregation operators (count, groupBy, top, stats, etc.) |
| `functions` | Built-in functions for filters and select expressions |
| `examples` | Common query patterns for CPU, GC, I/O, memory analysis |
| `event_types` | Common JDK event types and their fields |

**Example Request:**
```json
{"name": "jfr_help", "arguments": {"topic": "filters"}}
```

**Example Response (overview):**
```
# JfrPath Query Language

JfrPath is a path-based query language for navigating and querying JFR files.

## Basic Syntax
events/<eventType>[filters] | pipeline_operator

## Query Roots
- events/<type> - Access event data (e.g., events/jdk.ExecutionSample)
- metadata/<type> - Access type metadata
- chunks - Access chunk information
- cp/<type> - Access constant pool entries

## Quick Examples
events/jdk.ExecutionSample | count()
events/jdk.FileRead[bytes>1000] | top(10)
events/jdk.GCPhasePause | stats(duration)
...
```

### jfr_flamegraph

Generates aggregated stack trace data for flamegraph-style analysis. Returns stack paths with sample counts.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `eventType` | string | Yes | Event type (e.g., `jdk.ExecutionSample`, `jdk.ObjectAllocationSample`) |
| `direction` | string | No | `bottom-up` (hot methods at root) or `top-down` (entry points at root). Default: `bottom-up` |
| `format` | string | No | `folded` (semicolon-separated) or `tree` (JSON). Default: `folded` |
| `sessionId` | string | No | Session ID or alias (uses current if not specified) |
| `minSamples` | integer | No | Minimum sample count to include (default: 1) |
| `maxDepth` | integer | No | Maximum stack depth (default: unlimited) |

**Example Response (folded format):**
```json
{
  "format": "folded",
  "totalSamples": 96388,
  "data": "java.lang.Thread.run;com.example.Main.main;com.example.Worker.process 1523\njava.lang.Thread.run;com.example.Main.main;com.example.Worker.compute 892\n..."
}
```

The folded format is compatible with standard flamegraph tools (e.g., Brendan Gregg's FlameGraph).

**Example Response (tree format):**
```json
{
  "format": "tree",
  "direction": "bottom-up",
  "totalSamples": 96388,
  "root": {
    "name": "root",
    "value": 96388,
    "children": [
      {
        "name": "java.util.HashMap.get",
        "value": 5000,
        "children": [...]
      }
    ]
  }
}
```

**Use Cases:**
- **bottom-up**: See where CPU time is spent (hot methods as roots)
- **top-down**: See call paths from entry points to hot spots

### jfr_callgraph

Generates a call graph showing caller-callee relationships from stack traces. Unlike flamegraph (which preserves full paths), this shows which methods call which, revealing convergence points.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `eventType` | string | Yes | Event type (e.g., `jdk.ExecutionSample`) |
| `format` | string | No | `dot` (graphviz) or `json`. Default: `dot` |
| `sessionId` | string | No | Session ID or alias (uses current if not specified) |
| `minWeight` | integer | No | Minimum edge weight to include (default: 1) |

**Example Response (DOT format):**
```json
{
  "format": "dot",
  "totalSamples": 96388,
  "nodeCount": 250,
  "edgeCount": 480,
  "data": "digraph callgraph {\n  rankdir=TB;\n  \"Thread.run\" -> \"Main.main\" [label=\"96388\"];\n  \"Main.main\" -> \"Worker.process\" [label=\"50000\"];\n  \"Worker.process\" -> \"HashMap.get\" [label=\"5000\"];\n  \"Worker.compute\" -> \"HashMap.get\" [label=\"3000\"];\n}\n"
}
```

Note: `HashMap.get` has two incoming edges - this is a convergence point called from multiple places.

**Example Response (JSON format):**
```json
{
  "format": "json",
  "totalSamples": 96388,
  "nodes": [
    {"id": "java.lang.Thread.run", "samples": 96388},
    {"id": "com.example.Worker.process", "samples": 50000},
    {"id": "java.util.HashMap.get", "samples": 8000, "inDegree": 2}
  ],
  "edges": [
    {"from": "com.example.Worker.process", "to": "java.util.HashMap.get", "weight": 5000},
    {"from": "com.example.Worker.compute", "to": "java.util.HashMap.get", "weight": 3000}
  ]
}
```

**Use Cases:**
- **Convergence analysis**: Find methods called from many places (high `inDegree`)
- **Dependency mapping**: See what method X calls and what calls X
- **Hot edge detection**: Identify the most frequent caller-callee pairs
- **Visualization**: DOT format can be rendered with graphviz tools

## Using with Claude Desktop

Add the MCP server to your Claude Desktop configuration:

### macOS

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

**With JBang (Recommended):**
```json
{
  "mcpServers": {
    "jafar": {
      "command": "jbang",
      "args": ["jfr-mcp@btraceio", "--stdio"],
      "env": {}
    }
  }
}
```

**With Development Snapshots:**
```json
{
  "mcpServers": {
    "jafar-dev": {
      "command": "jbang",
      "args": ["jfr-mcp-dev@btraceio", "--stdio"],
      "env": {}
    }
  }
}
```

**With Manual JAR:**
```json
{
  "mcpServers": {
    "jafar": {
      "command": "java",
      "args": ["-jar", "/path/to/jafar/jfr-mcp/build/libs/jfr-mcp-0.13.1-all.jar", "--stdio"],
      "env": {}
    }
  }
}
```

### Windows

Edit `%APPDATA%\Claude\claude_desktop_config.json` with similar content (JBang works on Windows too).

After configuration, restart Claude Desktop. You can then ask Claude to analyze JFR files:

> "Open the recording at /tmp/myapp.jfr and tell me what's causing high CPU usage"

> "List all event types in the recording and show me the top 10 GC pause events"

> "Find the longest monitor waits and show me their stack traces"

## Manual Testing

You can test the MCP server manually using curl and the SSE protocol.

### Test Script

```bash
#!/bin/bash

# Start SSE connection in background
curl -s -N http://localhost:3000/mcp/sse > /tmp/sse_output.txt &
SSE_PID=$!
sleep 2

# Extract session ID
SESSION_ID=$(grep -o 'sessionId=[a-f0-9-]*' /tmp/sse_output.txt | head -1 | cut -d= -f2)
ENDPOINT="http://localhost:3000/mcp/message?sessionId=$SESSION_ID"

# Initialize (required by MCP protocol)
curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' &
sleep 1

# Send initialized notification (required before tool calls)
curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' &
sleep 1

# Open a recording
curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"jfr_open","arguments":{"path":"/path/to/recording.jfr"}}}' &
sleep 2

# Run a query
curl -s -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"jfr_query","arguments":{"query":"events/jdk.ExecutionSample | count()"}}}' &
sleep 2

# Show responses
cat /tmp/sse_output.txt

kill $SSE_PID 2>/dev/null
```

### Important Protocol Notes

1. **SSE Connection First**: Always establish SSE connection before sending messages
2. **Initialize Handshake**: Send `initialize` request and wait for response
3. **Initialized Notification**: Send `notifications/initialized` before any tool calls
4. **Responses via SSE**: Tool responses arrive through the SSE stream, not as HTTP response bodies

## Example Workflows

### Performance Analysis Workflow

1. **Open the recording**
   ```
   jfr_open: path="/tmp/production.jfr"
   ```

2. **Discover what's in the recording**
   ```
   jfr_list_types: scan=true
   ```

3. **Analyze CPU samples**
   ```
   jfr_query: query="events/jdk.ExecutionSample | groupBy(stackTrace) | top(10)"
   ```

4. **Check for lock contention**
   ```
   jfr_query: query="events/jdk.JavaMonitorEnter[duration > 1ms] | top(10)"
   ```

5. **Examine GC pauses**
   ```
   jfr_query: query="events/jdk.GCPhasePause | stats(duration)"
   ```

### Memory Analysis Workflow

1. **Find allocation hotspots**
   ```
   jfr_query: query="events/jdk.ObjectAllocationSample | groupBy(stackTrace) | top(10)"
   ```

2. **Check heap usage over time**
   ```
   jfr_query: query="events/jdk.GCHeapSummary | select(startTime, heapUsed)"
   ```

3. **Identify large object allocations**
   ```
   jfr_query: query="events/jdk.ObjectAllocationOutsideTLAB | top(10, by=allocationSize)"
   ```

### I/O Analysis Workflow

1. **Find slow file reads**
   ```
   jfr_query: query="events/jdk.FileRead[duration > 10ms] | top(10)"
   ```

2. **Analyze socket activity**
   ```
   jfr_query: query="events/jdk.SocketRead | groupBy(address) | top(10)"
   ```

### Profiler Analysis Workflow

Use flamegraph and callgraph tools for deep CPU and allocation analysis:

1. **Generate CPU flamegraph (bottom-up)**
   ```
   jfr_flamegraph: eventType="jdk.ExecutionSample", format="tree"
   ```
   Shows hot methods at the root - where CPU time is actually spent.

2. **Generate CPU flamegraph (top-down)**
   ```
   jfr_flamegraph: eventType="jdk.ExecutionSample", direction="top-down", format="tree"
   ```
   Shows call paths from entry points (main, run) down to hot spots.

3. **Find convergence points with callgraph**
   ```
   jfr_callgraph: eventType="jdk.ExecutionSample", format="json"
   ```
   Look for nodes with high `inDegree` - methods called from many places.

4. **Analyze allocation hotspots**
   ```
   jfr_flamegraph: eventType="jdk.ObjectAllocationSample", format="folded"
   ```
   The folded format can be used with standard flamegraph visualization tools.

5. **Export for visualization**
   ```
   jfr_callgraph: eventType="jdk.ExecutionSample", format="dot", minWeight=100
   ```
   The DOT format can be visualized with graphviz: `dot -Tpng output.dot -o graph.png`

## Troubleshooting

### Port Already in Use

**Automatic Detection (v0.10.0+):**
The server automatically detects if another instance is already running on the requested port and exits silently. You can:
- Use the existing instance on port 3000
- Start on a different port: `jfr-mcp -Dmcp.port=8080` or `jfr-mcp-dev -Dmcp.port=8080`
- Stop the existing instance and restart

**Manual Check:**
```bash
# Check what's using port 3000
lsof -i :3000

# Use a different port
jfr-mcp -Dmcp.port=8080              # With JBang
java -Dmcp.port=8080 -jar jfr-mcp-*-all.jar  # With JAR
```

**Note:** STDIO mode (for Claude Desktop) always starts a fresh instance since it doesn't use network ports.

### Switching Between Stable and Development Versions

**Install both as different commands:**
```bash
# Install both versions
jbang app install jfr-mcp@btraceio
jbang app install jfr-mcp-dev@btraceio

# Use stable
jfr-mcp --stdio

# Use dev
jfr-mcp-dev --stdio
```

**Or uninstall before switching:**
```bash
# Switch to dev
jbang app uninstall jfr-mcp
jbang app install jfr-mcp-dev@btraceio

# Switch back to stable
jbang app uninstall jfr-mcp-dev
jbang app install jfr-mcp@btraceio
```

### Tool Calls Not Working

**Missing initialized notification:**
The MCP protocol requires sending `notifications/initialized` after the initialize handshake. Without this, tool calls are silently ignored.

**Wrong session ID:**
Each SSE connection gets a unique session ID. Make sure you're using the session ID from your active SSE connection.

### Query Returns Empty Results

**Event type doesn't exist:**
Use `jfr_list_types` to see available types. Type names are case-sensitive.

**Filter too restrictive:**
Try removing filters to see if events exist:
```
events/jdk.ExecutionSample | count()
```

### Slow Responses

**Large recordings:**
For recordings over 100MB, queries may take several seconds. Consider:
- Using more specific filters
- Limiting results with `top(n)` or the `limit` parameter
- Using `scan=false` for `jfr_list_types` (faster but no counts)

### Connection Issues

**SSE connection drops:**
The SSE connection must stay open to receive responses. If using curl, use `-N` flag to disable buffering.

**Firewall blocking:**
Ensure port 3000 (or your custom port) is accessible.

## See Also

- [JFR Shell Tutorial](../cli/Tutorial.md) - Interactive CLI for JFR analysis
- [JfrPath Query Language](../cli/JFRPath.md) - Complete query language reference
- [MCP Specification](https://modelcontextprotocol.io) - Official MCP documentation
