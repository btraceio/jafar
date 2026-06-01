# Jafar MCP Server Refactoring Task List

This checklist tracks the incremental refactoring of `JafarMcpServer` into smaller components using established design patterns while preserving existing MCP tool behavior.

## Phase 1 — Shared Infrastructure

- [x] Extract MCP result creation (`successResult`, `errorResult`) into a dedicated factory.
- [x] Extract progress notification helpers into a dedicated reporter.
- [x] Extract result truncation into a small utility.
- [x] Extract safe field-name validation into a validator.
- [x] Centralize MCP server limit configuration constants.
- [x] Extract common argument parsing helpers.
- [x] Extract common file validation helpers and migrate open handlers.

## Phase 2 — Tool Command/Catalog Model

- [x] Introduce `McpToolCommand` command abstraction.
- [x] Introduce `ToolDescriptor` for tool name, description, and schema.
- [x] Introduce `ToolSpecificationFactory` to adapt commands to MCP SDK specifications.
- [x] Introduce `ToolCatalog` to register tool providers.
- [ ] Migrate `createToolSpecifications()` to the catalog while initially delegating to legacy handlers.

## Phase 3 — Cross-Cutting Tool Interceptors

- [x] Move activity tracking out of tool construction into an interceptor/decorator.
- [x] Move tool-level error handling into an interceptor/decorator.
- [x] Keep `VirtualMachineError` behavior unchanged.

## Phase 4 — Transport and Lifecycle

- [ ] Extract stdio transport runner.
- [ ] Extract SSE transport runner.
- [ ] Extract MCP server factory.
- [ ] Extract idle watchdog.
- [x] Extract SSE port registry.
- [ ] Extract shutdown coordination.
- [ ] Isolate MCP SDK reflection workaround for SSE session initialization.

## Phase 5 — Heap Dump Tools

- [ ] Extract `hdump_open`.
- [ ] Extract `hdump_close`.
- [ ] Extract `hdump_query`.
- [ ] Extract `hdump_summary`.
- [ ] Extract `hdump_report`.
- [ ] Extract `hdump_help` and help text provider.

## Phase 6 — pprof and OTLP Tools

- [ ] Introduce shared sampling-profile backend strategy.
- [ ] Extract pprof tools.
- [ ] Extract OTLP tools.
- [ ] Share query, summary, flamegraph, and USE logic where behavior is identical.

## Phase 7 — JFR Basic Tools

- [ ] Extract `jfr_open`.
- [ ] Extract `jfr_close`.
- [ ] Extract `jfr_query`.
- [ ] Extract `jfr_list_types`.
- [ ] Extract `jfr_help` and help text provider.

## Phase 8 — JFR Analysis Tools

- [ ] Extract summary analyzer/tool.
- [ ] Extract exception analyzer/tool.
- [ ] Extract hot methods analyzer/tool.
- [ ] Extract flamegraph analyzer/tool.
- [ ] Extract callgraph analyzer/tool.
- [ ] Extract stack profile analyzer/tool.
- [ ] Extract USE analyzer/tool.
- [ ] Extract TSA analyzer/tool.
- [ ] Extract diagnose analyzer/tool.

## Phase 9 — Test Migration and Cleanup

- [ ] Replace reflection-based tests with direct tests for extracted classes.
- [ ] Keep schema registration tests for all tools.
- [ ] Remove legacy delegation methods from `JafarMcpServer`.
- [ ] Run `./gradlew :jfr-mcp:test`.
- [ ] Run `./gradlew test`.
- [ ] Run `./gradlew :jfr-mcp:shadowJar`.
