# MCP guidance contract and scripted agent workflows

Status: approved scope — Tier 0 completion and Tier 1 implementation.

This plan closes the gap between a server that compiles and a client that can follow its guidance
through a complete analysis. It deliberately builds on the current `jfr-mcp` contract tests and
subprocess E2E task; it does not redesign the MCP tools or add an LLM.

## Scope

### Tier 0 — guidance is an executable contract

Complete and validate the inherited guidance checks:

- Commit `jfr-mcp/src/test/resources/mcp-contract.json`, rendered by the existing
  `McpContractJson` / `McpContractExport` path. It is the reviewed baseline for registered tool
  names, descriptions, input schemas, prompt definitions **and rendered prompt messages**, and
  resource metadata **and rendered resource content**. The rendered text is part of the contract:
  it is what an MCP client can actually follow, whereas metadata alone cannot expose stale playbook
  instructions.
- Make the `jafar://help/tools` assertion true by ensuring the resource's actual tool-selection
  text names the JFR entry point it teaches (`jfr_open`). Do not weaken the assertion merely to
  make the test pass.
- Keep the four existing guards as the contract boundary: every documented query parses with its
  real parser; every mentioned tool and resource exists; opening tools identify only their own
  format; and the snapshot changes only after an explicit review.
- Preserve `exportMcpContract` as the single export path for external consumers such as
  `btraceio/jafar-perf-box`. Its output and the committed snapshot must represent the same
  live-server wiring. Add a test that writes a temporary export through `McpContractExport` and
  semantically compares it with the committed snapshot; do not merely compare two calls to the
  renderer.

### Tier 1 — scripted ideal-agent workflows

Add black-box tests that start the locally built shaded JAR with `--stdio`, perform MCP
initialization, and drive the shortest useful workflow for each supported input family:

| Family | Scripted workflow | Required evidence |
|---|---|---|
| JFR | `jfr_open` -> `jfr_summary` -> `jfr_query` -> a guidance-recommended analysis -> `jfr_close` | Responses are successful, the result JSON has the fields the next step needs, and the process accepts a subsequent request. |
| HPROF | `hdump_open` -> `hdump_summary` -> `hdump_query` -> `hdump_report` -> `hdump_close` | A generated minimal valid dump is opened, query/report results have usable JSON content, and close succeeds. |
| pprof | `pprof_open` -> `pprof_summary` -> `pprof_query` -> `pprof_use` (or the documented alternative) -> `pprof_close` | The synthetic profile's known sample/method evidence survives the transport. |
| OTLP | `otlp_open` -> `otlp_summary` -> `otlp_query` -> `otlp_use` -> `otlp_close` | The synthetic profile's known sample/evidence survives the transport. |

These are *agent workflows*, not one-test-per-tool coverage. Each must use the same JSON-RPC
requests a client sends, including the initialized notification and, where an analysis reports
progress, a numeric `_meta.progressToken`. Assertions inspect the response payload and confirm
that a later call still receives a response; an exit code alone is not evidence.

Use deterministic, repository-owned fixtures only: the existing small JFR recording and minimal
pprof/OTLP files constructed during the test. For HPROF, promote the existing
`hdump-parser` `SyntheticHeapDumpGenerator` to its `java-test-fixtures` source set and consume it
from `jfr-mcp` with Gradle's `testFixtures(project(":hdump-parser"))`; do not add a second HPROF
encoder to `jfr-mcp`. Give fixture content distinctive names and known counts so assertions
establish that the opened file, rather than an empty fallback or stale session, produced the
result.

Each launched child JVM receives a fresh, test-owned session file, for example
`-Djafar.mcp.sessions.file=<jfr-mcp/build/mcp-e2e/<unique-test-id>/sessions.json`, and an isolated
`-Duser.home` under that same unique build directory. The subprocess harness creates the directory,
passes both properties before `-jar`, and removes it after shutdown. It asserts that the configured
session path is under the test's build directory, that no state is inherited from a prior workflow,
and that cleanup succeeded. This makes access to a real `~/.jafar` session file impossible even if
the explicit session property is accidentally ignored.

## Affected components

- `jfr-mcp` guidance sources, contract snapshot, and guidance tests (`GuidanceSurfaces`,
  `ToolCatalogSnapshotTest`, `GuidanceExampleQueriesTest`, `GuidanceToolReferencesTest`, and
  `ToolDescriptionQualityTest`). `McpContractJson` and `McpContractExport` expand to serialize
  rendered prompt/resource text and gain an export-versus-snapshot test.
- `jfr-mcp` E2E test support and Gradle task. Reuse the request/response discipline in
  `McpEndToEndTest`; factor shared subprocess JSON-RPC support only if it prevents divergent
  protocol handling. The harness owns a unique child-home/session directory per process and proves
  it is removed. The existing in-process `McpTransportHarness` remains transport-level coverage,
  not Tier 1 proof.
- `hdump-parser` adds the `java-test-fixtures` capability and moves its existing synthetic HPROF
  generator there; `jfr-mcp` consumes that fixture capability. Production tool APIs, tool names,
  schemas, prompts, and runtime dependencies are not changed by Tier 1.

## Compatibility and non-goals

The public MCP surface remains backward compatible: no tool renames, parameter changes, response
shape changes, or new required client capabilities. A Tier 0 snapshot update is intentional only
when a reviewed guidance contract change warrants it; it is not regenerated blindly.

Out of scope: hosted-model or agent evaluation, `btraceio/jafar-perf-box` changes, live JVM
capture, performance/load testing, HTTP/SSE coverage, broad handler refactors, and exhaustive
per-tool integration coverage. Tier 1 validates the normal stdio route a guided agent needs; the
existing unit and transport suites retain their respective roles.

## Acceptance criteria

1. `:jfr-mcp:test` passes with the committed contract snapshot present; changing a tool,
   parameter, prompt (including rendered message), or resource (including rendered content) fails
   `ToolCatalogSnapshotTest` until the reviewed update switch is used.
2. Every shipped guidance example is parsed by the matching JfrPath, HdumpPath, PprofPath, or
   OtlpPath parser, and every named tool/resource resolves from the live server registrations.
3. `:jfr-mcp:exportMcpContract` writes the same logical JSON as the committed snapshot, proved by
   a test that invokes the exporter to a temporary path and compares the parsed output.
4. A dedicated Tier 1 invocation builds the shadow JAR and passes all four complete workflows
   against a newly launched server process. Each workflow fails if its expected tool call or
   payload assertion is removed or deliberately broken (R5).
5. Every Tier 1 child process receives a distinct build-directory session path and isolated home;
   the test asserts this configuration and removes its directory after shutdown. The task is
   deterministic, requires no downloaded large recording, no running external service, and no paid
   credential, and never reads or writes the user's MCP session state.

## Verification

Run the focused Tier 0 tests first, then the complete fast module suite and the subprocess tests:

```bash
./gradlew :jfr-mcp:test --tests ToolCatalogSnapshotTest --tests GuidanceExampleQueriesTest --tests GuidanceToolReferencesTest --tests ToolDescriptionQualityTest
./gradlew :jfr-mcp:test
./gradlew :jfr-mcp:exportMcpContract
./gradlew :jfr-mcp:endToEndTest
```

For Tier 1, inspect the captured JSON in each assertion: it must show the expected session or
known fixture evidence and `isError: false`. Also inspect the child command/configuration and
assert its generated session path and home are both under `jfr-mcp/build/mcp-e2e/`, then assert
their removal after the process exits. Report any unverified published-JBang path separately; it is
useful release compatibility coverage but is not a prerequisite for local implementation. Before
committing a regression test, temporarily remove the behaviour or assertion it protects and record
the named failing test, then restore it (R5). Run `./gradlew spotlessApply` before final validation.

## Security and cost boundary

Tests start only the locally built server and feed it local synthetic/checked-in data. They make no
network calls, do not invoke an LLM, do not require API keys, and cannot access a user's persisted
MCP sessions because every child gets an explicit private session file and private home. File paths
are test-created or repository-owned. The tests must not convert a malformed/open failure into a
successful empty analysis: assertions verify the payload's fixture-specific evidence and actionable
error semantics where failure paths are tested.
