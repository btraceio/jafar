# JFR Shell MCP Server

Model Context Protocol (MCP) server that exposes Jafar's JFR analysis capabilities to AI assistants like Claude and ChatGPT.

## Overview

This module provides an MCP server that allows AI assistants to analyze Java Flight Recorder (JFR) files through a standardized protocol. It exposes 10 tools for session management, querying, metadata inspection, and pre-built analyses.

## Features

- **Session Management**: Open, list, and close JFR recording sessions
- **Natural Language Queries**: Ask questions in plain English about JFR data (powered by LLMs)
- **Direct Queries**: Execute JfrPath queries directly
- **Metadata Inspection**: Explore available event types and their structures
- **Pre-built Analyses**: Ready-to-use analyses for threads, GC, CPU, and memory allocations
- **STDIO Transport**: Local communication via standard input/output for security

## MCP Tools (10 total)

### Session Management (3 tools)

#### `jfr_open`
Opens a JFR recording file and creates a new analysis session.

**Parameters:**
- `filePath` (required): Absolute path to the JFR recording file
- `alias` (optional): Human-readable alias for the session

**Returns:** Session information including ID, event type count, and total events

#### `jfr_sessions`
Lists all active JFR recording sessions.

**Returns:** List of session information for all active sessions

#### `jfr_close`
Closes an active JFR session by ID or alias.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID (number) or alias (string) to close

**Returns:** Success message

### Query Tools (2 tools)

#### `jfr_query`
Executes a JfrPath query against a JFR recording session.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to query
- `query` (required): JfrPath query string to execute

**Returns:** Query results with matched events

**Example:**
```
jfr_query("1", "events/jdk.ExecutionSample | groupBy(eventThread/javaName, agg=count) | top(10, by=count)")
```

#### `jfr_ask`
Translates a natural language question into a JfrPath query and executes it.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to query
- `question` (required): Natural language question about the JFR data

**Returns:** Query results with explanation of generated query

**Example:**
```
jfr_ask("1", "which threads allocated the most memory?")
```

### Metadata Tools (1 tool)

#### `jfr_metadata`
Queries metadata about JFR event types.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to query
- `eventType` (optional): Specific event type to inspect (e.g., "jdk.ExecutionSample")

**Returns:**
- If `eventType` not provided: List of all available event types
- If `eventType` provided: Detailed structure including fields, descriptions, and types

### Pre-built Analyses (4 tools)

#### `jfr_analyze_threads`
Analyzes thread activity by execution samples. Shows which threads consumed the most CPU time.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to analyze
- `topN` (optional, default: 10): Number of top threads to return

#### `jfr_analyze_gc`
Analyzes garbage collection pauses. Returns statistics including min, max, average, standard deviation, and percentiles (p50, p90, p99).

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to analyze

#### `jfr_analyze_cpu`
Analyzes CPU hotspots by profiling execution samples. Shows the top methods consuming CPU time.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to analyze
- `topN` (optional, default: 10): Number of top methods to return

#### `jfr_analyze_allocations`
Analyzes memory allocations by class. Shows which classes are responsible for the most memory allocation.

**Parameters:**
- `sessionIdOrAlias` (required): Session ID or alias to analyze
- `topN` (optional, default: 10): Number of top allocating classes to return

## Building

```bash
# Build the shadow JAR
./gradlew :jfr-shell-mcp:build

# The JAR will be created at:
# jfr-shell-mcp/build/libs/jfr-shell-mcp-0.8.0-SNAPSHOT.jar
```

## Claude Desktop Configuration

### macOS

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jfr-shell": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/jafar/jfr-shell-mcp/build/libs/jfr-shell-mcp-0.8.0-SNAPSHOT.jar"
      ],
      "env": {
        "LLM_API_KEY": "your-api-key-if-needed"
      }
    }
  }
}
```

### Windows

Edit `%APPDATA%\Claude\claude_desktop_config.json` with the same configuration (adjust path format for Windows).

### Restart Claude Desktop

After saving the configuration:
1. Quit Claude Desktop completely
2. Restart Claude Desktop
3. Look for the MCP connection indicator (usually a plug or tools icon)
4. Verify that 10 tools are available

## Usage Example

In Claude Desktop:

```
User: Open the JFR recording at /tmp/myapp.jfr

Claude: [Uses jfr_open tool]
        Session 1 opened successfully
        - Event types: 150
        - Total events: 125,000

User: Which threads allocated the most memory?

Claude: [Uses jfr_ask tool with natural language]
        Generated query: events/jdk.ObjectAllocationSample |
                        groupBy(eventThread/javaName, agg=sum, value=bytes) |
                        top(10, by=sum)

        Top memory-allocating threads:
        1. http-nio-8080-exec-1: 45.2 MB
        2. main: 23.1 MB
        3. http-nio-8080-exec-2: 18.7 MB
        ...

User: Show me the GC statistics

Claude: [Uses jfr_analyze_gc tool]
        GC Pause Statistics:
        - Min: 1.2 ms
        - Max: 45.3 ms
        - Average: 8.7 ms
        - p50: 6.5 ms
        - p90: 15.2 ms
        - p99: 32.1 ms
```

## Architecture

```
jfr-shell-mcp/
├── src/main/java/io/jafar/mcp/
│   ├── JfrShellMcpApplication.java    # Spring Boot entry point
│   ├── config/
│   │   └── JfrShellConfig.java        # Bean configuration (reuses jfr-shell infrastructure)
│   ├── service/
│   │   ├── JfrSessionService.java     # Session lifecycle management
│   │   └── JfrAnalysisService.java    # Pre-built analysis patterns
│   ├── tools/
│   │   ├── SessionTools.java          # jfr_open, jfr_sessions, jfr_close
│   │   ├── QueryTools.java            # jfr_query, jfr_ask
│   │   ├── MetadataTools.java         # jfr_metadata
│   │   └── AnalysisTools.java         # jfr_analyze_* (4 tools)
│   ├── model/
│   │   ├── SessionInfo.java           # Session information DTO
│   │   ├── QueryResult.java           # Query result DTO
│   │   └── AnalysisResult.java        # Analysis result DTO
│   └── exception/
│       └── JfrMcpException.java       # Custom exception
└── src/main/resources/
    ├── application.yml                # CRITICAL: STDIO transport config
    └── logback-spring.xml             # File-only logging (no stdout)
```

## Technical Details

### Dependencies
- **Spring Boot 3.4.1**: Application framework
- **Spring AI MCP 1.1.2**: MCP server implementation
- **jfr-shell module**: Core JFR analysis infrastructure (SessionManager, JfrPathEvaluator, QueryTranslator)
- **parser module**: JFR parsing engine

### Java Version
- Requires Java 21+
- Compiled with Java 21 toolchain

### STDIO Transport
The server uses STDIO (standard input/output) for communication with Claude Desktop:
- **Critical**: No stdout output allowed (corrupts JSON-RPC protocol)
- All logging goes to files only (configured in logback-spring.xml)
- Banner and startup messages disabled in application.yml

### Natural Language Translation
The `jfr_ask` tool uses LLM-based query translation:
- Reuses `QueryTranslator` from jfr-shell module
- Loads LLM prompts from resource files (see jfr-shell/src/main/resources/llm-prompts/)
- Requires LLM configuration in `~/.jfr-shell/llm-config.json`
- Supports Ollama (local), OpenAI, and Anthropic providers

## Troubleshooting

### Tools Not Showing Up
- Verify Claude Desktop is completely restarted
- Check the configuration file path and JSON syntax
- Look for errors in Claude Desktop's logs

### JAR Not Found
- Use absolute paths in the configuration
- Ensure the JAR was built successfully
- Check file permissions

### Startup Errors
- Check logs in `logs/jfr-shell-mcp.log`
- Verify Java 21+ is available
- Ensure LLM configuration exists (if using jfr_ask)

### Tool Execution Failures
- Verify JFR file paths are absolute
- Check that JFR files are readable
- Review error messages in tool responses

## Future Enhancements

Potential improvements (not currently implemented):
- MCP Resources: Expose recordings as URIs
- MCP Prompts: Pre-built analysis workflows
- Async server mode for long-running analyses
- HTTP+SSE transport for web clients
- Query result caching
- Custom analyzer plugins

## See Also

- [Model Context Protocol Specification](https://spec.modelcontextprotocol.io/)
- [Spring AI MCP Documentation](https://docs.spring.io/spring-ai/reference/api/mcp/)
- [JfrPath Query Language](../jfr-shell/README.md)
- [Claude Desktop Configuration](https://docs.anthropic.com/claude/docs/mcp)
