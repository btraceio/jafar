# MCP server (`jfr-mcp`)

Tools, prompts, resources, the findings contract, and the plugin repository it feeds.

## MCP Server (`jfr-mcp`)
The `jfr-mcp` module exposes analysis capabilities as an MCP (Model Context Protocol) server, allowing AI agents (Claude, etc.) to analyze JFR recordings, pprof profiles, and OTLP profiles.

JFR tools: `jfr_open`, `jfr_close`, `jfr_list_types`, `jfr_query`, `jfr_help`, `jfr_summary`, `jfr_diagnose`, `jfr_compare`, `jfr_flamegraph`, `jfr_callgraph`, `jfr_hotmethods`, `jfr_exceptions`, `jfr_use`, `jfr_tsa`, `jfr_stackprofile`.

Heap dump tools: `hdump_open`, `hdump_close`, `hdump_query`, `hdump_summary`, `hdump_report`, `hdump_help`.

pprof tools: `pprof_open`, `pprof_close`, `pprof_query`, `pprof_summary`, `pprof_flamegraph`, `pprof_use`, `pprof_hotmethods`, `pprof_tsa`, `pprof_help`.

OTLP profiling tools: `otlp_open`, `otlp_close`, `otlp_query`, `otlp_summary`, `otlp_flamegraph`, `otlp_use`, `otlp_help`.

Run the MCP server:
```bash
./gradlew :jfr-mcp:shadowJar
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar --stdio   # STDIO mode
java -jar jfr-mcp/build/libs/jfr-mcp-*-all.jar           # HTTP mode (port 3000)
```

MCP prompts (analysis playbooks, surfaced as `/mcp__jafar__<name>` in Claude Code): `triage`, `compare`, `leak-hunt`, `latency`.
MCP resources: `jafar://sessions`, `jafar://help/jfrpath`, `jafar://help/hdumppath`, `jafar://help/tools`.

**Analysis tools emit structured findings.** `jfr_diagnose`, `jfr_use`, `jfr_tsa`, `jfr_compare`,
`pprof_use`, `otlp_use` and `hdump_report` all return a `findings` array of
`io.jafar.mcp.findings.Finding` maps (`id`, `severity`, `category`, `title`, `description`,
`source`, `evidence`, `action`, `query`). The `id` is stable, so findings from different tools
de-duplicate and merge — see `Findings.merge`. When adding a tool that makes a judgement, emit
findings in this shape rather than inventing another one.

See [jfr-mcp/README.md](../../jfr-mcp/README.md) and [doc/mcp/Tutorial.md](../../doc/mcp/Tutorial.md) for full documentation.

## Claude Code Plugin (`btraceio/jafar-perf-box`, a separate repository)
A Claude Code plugin turns the MCP server into a guided performance analyst: methodology skills
(`triage`, `cpu`, `latency`, `gc`, `memory-leak`, `heap-diff`, `compare`, `jfrpath`, `report`) and
subagents (`perf-lead` plus five specialists). It bundles `.mcp.json`, so installing it registers
the MCP server too.

**It lives in [btraceio/jafar-perf-box](https://github.com/btraceio/jafar-perf-box), not here.**
Adding a marketplace clones its repository, and this one carries several megabytes of binary test
recordings a plugin user has no use for. That split has a cost, and it is the one thing to
remember:

> **When changing an MCP tool's name, parameters or response shape, update the affected skill files
> in `btraceio/jafar-perf-box`.** They name tools and parameters explicitly, they are not covered
> by this repository's tests, and stale guidance sends an agent down a path that no longer works.
