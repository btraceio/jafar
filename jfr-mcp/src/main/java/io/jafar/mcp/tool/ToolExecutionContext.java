package io.jafar.mcp.tool;

import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;

/** Shared dependencies available to extracted MCP tool commands. */
public record ToolExecutionContext(
    SessionRegistry sessionRegistry,
    HeapSessionRegistry heapSessionRegistry,
    PprofSessionRegistry pprofSessionRegistry,
    OtlpSessionRegistry otlpSessionRegistry,
    QueryEvaluator evaluator,
    QueryParser queryParser,
    McpResultFactory resultFactory,
    ProgressReporter progressReporter) {}
