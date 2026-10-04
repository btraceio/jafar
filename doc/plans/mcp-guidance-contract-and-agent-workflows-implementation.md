# MCP guidance contract and agent workflows — implementation plan

Status: implementation plan for the approved design in
[`mcp-guidance-contract-and-agent-workflows.md`](mcp-guidance-contract-and-agent-workflows.md).

This is an execution plan, not a replacement for the approved design. Keep the design document
as the historical proposal; record implementation decisions and verification results in the
commit/PR.

## Scope and boundaries

Implement the approved work in two independently shippable gates:

1. **Tier 0:** make the registered MCP guidance a reviewed, reproducible contract.
2. **Tier 1:** prove a real, locally built `--stdio` server can take one useful agent workflow for
   JFR, HPROF, pprof, and OTLP inputs.

Do not rename MCP tools, change their public input schemas, add a live LLM, call a hosted service,
test HTTP/SSE, or modify `btraceio/jafar-perf-box` in this repository. Tier 1 is the local shadow
JAR path only. The optional JBang path in the existing test remains a separately reported release
compatibility check, never a prerequisite for this change.

The existing uncommitted guidance files and MCP source edits are the starting state. Do not discard
or overwrite unrelated work while implementing this plan.

## Pre-flight: establish the baseline and the test net

1. Inspect the current diff before editing. The expected Tier 0 surface is:

   - `jfr-mcp/src/test/java/io/jafar/mcp/GuidanceSurfaces.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/QueryExamples.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/GuidanceExampleQueriesTest.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/GuidanceToolReferencesTest.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/ToolDescriptionQualityTest.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/McpContractJson.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/McpContractExport.java`
   - `jfr-mcp/src/test/java/io/jafar/mcp/ToolCatalogSnapshotTest.java`
   - the relevant live guidance text, currently `JfrHelpProvider` and any changed family tool
     descriptions under `jfr-mcp/src/main/java/io/jafar/mcp/{jfr,hdump,pprof,otlp}/`.

2. Capture named failing tests before a fix. Do not compare only test counts: preserve the failing
   test names and their messages. The first focused command is:

   ```bash
   ./gradlew :jfr-mcp:test \
     --tests io.jafar.mcp.ToolCatalogSnapshotTest \
     --tests io.jafar.mcp.GuidanceExampleQueriesTest \
     --tests io.jafar.mcp.GuidanceToolReferencesTest \
     --tests io.jafar.mcp.ToolDescriptionQualityTest
   ```

   Before relying on this as a fast unit/module command, make the Gradle selection explicit:
   `test.useJUnitPlatform { excludeTags 'e2e' }`. The tagged subprocess tests must never run from
   ordinary `:jfr-mcp:test`, and ordinary tests must not require `shadowJar` as an incidental
   dependency. The dedicated `endToEndTest` task is the sole local-test route that depends on
   `shadowJar` and uses `includeTags 'e2e'`.

3. If this command fails outside the known two contract gaps, stop Tier 0. Classify the unexpected
   failures by name and repair the net/design discrepancy before adding Tier 1. Do not paper over a
   parser failure by excluding an example from extraction, or paper over an unknown tool/resource
   by relaxing the scanner.

4. Confirm the Tier 1 starting net and wire locations before moving fixtures:

   - `jfr-mcp/src/test/java/io/jafar/mcp/McpEndToEndTest.java` is the current spawned-process
     regression test and contains the JSON-RPC reader/handshake discipline to preserve.
   - `jfr-mcp/src/test/java/io/jafar/mcp/McpTransportHarness.java` is in-process transport
     coverage. It is not proof of the packaged process path.
   - `hdump-parser/src/test/java/io/jafar/hdump/test/SyntheticHeapDumpGenerator.java` is the
     existing HPROF writer to promote; it must not be copied into `jfr-mcp`.
   - `pprof-parser/src/testFixtures/java/io/jafar/pprof/MinimalPprofBuilder.java` and
     `otlp-parser/src/testFixtures/java/io/jafar/otlp/MinimalOtlpBuilder.java` are the existing
     deterministic profile writers to consume.

Stop and update the plan/implementation approach if an actual tool requires a capability not in
the approved flow (for example a new required request field or an unavoidable external process).

## Gate 1 — Tier 0 guidance contract

### 1. Complete the live guidance before snapshotting it

Files:

- `jfr-mcp/src/main/java/io/jafar/mcp/jfr/JfrHelpProvider.java`
- any already-changed tool-description source in `JfrSessionTools`, `HdumpTools`, `PprofTools`, or
  `OtlpTools`
- `doc/cli/JFRPath.md`, if the wording/query examples changed there must be aligned with the live
  server's documentation contract.

Actions:

1. Update the actual JFR tool-selection help rendered by `JfrHelpProvider.getToolsHelp()` so
   `jafar://help/tools` explicitly introduces `jfr_open` as the JFR entry point. The assertion in
   `GuidanceToolReferencesTest.helpTextsMentionTheToolsTheyTeach` is intentionally precise; do not
   weaken it or point it at metadata.
2. Keep each documented example syntactically accepted by its real parser. Correct source text or
   schema descriptions, not the test's candidate extractor, when a shipped example is wrong.
3. Make the `jfr_*`, `hdump_*`, `pprof_*`, and `otlp_*` scanner resolve names against the live
   `JafarMcpServer.createToolSpecifications()` list. Resource URI checks must resolve against the
   live registered resource handlers, not a duplicated URI list.

Gate command:

```bash
./gradlew :jfr-mcp:test \
  --tests io.jafar.mcp.GuidanceExampleQueriesTest \
  --tests io.jafar.mcp.GuidanceToolReferencesTest \
  --tests io.jafar.mcp.ToolDescriptionQualityTest
```

Pass condition: all named tests pass and the output states a substantial parsed example corpus
(`GuidanceExampleQueriesTest` currently guards this at more than 200 examples).

R5 proof: temporarily remove `jfr_open` from the rendered tools-help source (or make its returned
text omit that token) and run only
`--tests io.jafar.mcp.GuidanceToolReferencesTest`. It must fail the
`helpTextsMentionTheToolsTheyTeach` test. Restore the text and rerun it green. Also temporarily
change one known query example to an invalid expression and observe the named
`GuidanceExampleQueriesTest` failure; restore it immediately. Record both named failures in the
commit/PR, not merely that the test was run.

Stop condition: if the parser rejects an example whose intended syntax is unclear, do not silently
delete it. Resolve whether the parser or documentation is the contract, then make the chosen
behavior explicit in the source/help text and its test.

### 2. Make the snapshot and exported contract the same semantic artifact

Files:

- `jfr-mcp/src/test/java/io/jafar/mcp/GuidanceSurfaces.java`
- `jfr-mcp/src/test/java/io/jafar/mcp/McpContractJson.java`
- `jfr-mcp/src/test/java/io/jafar/mcp/McpContractExport.java`
- `jfr-mcp/src/test/java/io/jafar/mcp/ToolCatalogSnapshotTest.java`
- new `jfr-mcp/src/test/resources/mcp-contract.json`
- `jfr-mcp/build.gradle` (task comments/inputs only if needed to keep the contract task accurate)

Actions:

1. Add a package-private `GuidanceContractContext` (or equivalently named closeable helper) in
   `jfr-mcp/src/test/java/io/jafar/mcp/`. Every snapshot/export rendering must create it *before*
   constructing `JafarMcpServer`. It owns a unique test/build directory and sets both
   `jafar.mcp.sessions.file=<root>/sessions.json` and `user.home=<root>/home`; it creates a fresh,
   empty store, asserts both normalized paths are under its root, restores the original system
   properties in `close`, and removes only its own directory. Protect tests that mutate the two
   properties with JUnit's system-properties resource lock (or disable parallel execution for this
   narrowly scoped context). This is required because `jafar://sessions` is dynamic content, not
   static guidance metadata.
2. Keep `GuidanceSurfaces` as the single live-server inventory. It must collect:
   - each registered tool's name, description, and real input schema;
   - each prompt's name, description, declared arguments, and rendered messages from the registered
     prompt handler; and
   - each resource's URI, name, description, MIME type, and rendered contents from the registered
     read handler.
   Render `jafar://sessions` only from the clean context and include that deterministic empty
   rendering in the snapshot; do not omit the resource merely because it is dynamic. Preserve
   deterministic ordering at JSON rendering time (tool/prompt name and resource URI) so source
   registration reordering is not a noisy contract change.
3. Extend `McpContractJson.render` to serialize the rendered prompt text and rendered resource
   text, in addition to existing metadata. Treat values as text visible to a client; do not derive
   them from duplicated literals. Keep the canonical mapper and parsed-tree comparison so formatting
   alone does not change semantic equality.
4. Retain `McpContractExport` as the only external export path. Its `main` must itself enter the
   clean `GuidanceContractContext` before rendering, rather than inheriting a developer's process
   session state. It writes canonical JSON to a caller-supplied temporary path (or its default
   build path); no second serializer and no shell copy step. The Gradle `exportMcpContract` task
   may pass an output path, but must not be relied on to clear session state for correctness.
5. Add a focused export-versus-snapshot test, either in `ToolCatalogSnapshotTest` or a dedicated
   `McpContractExportTest`. It must call `McpContractExport.main` with a JUnit temporary output
   path, read that path, parse it, and compare it with the parsed committed
   `src/test/resources/mcp-contract.json`. This proves the task's writer and the committed
   baseline, not merely two calls to `McpContractJson.render`.
6. Add `GuidanceContractSessionIsolationTest` (or an equivalently focused test). It must seed a
   valid stale `sessions.json` containing a distinctive alias in a candidate prior directory, then
   render the snapshot/export via the clean contract context. Assert the rendered
   `jafar://sessions` content is the exact empty-session form and does not contain that alias;
   assert the context's normalized session/home paths are distinct from and nested under its owned
   root. Run the same assertion through `McpContractExport.main`, not just an in-memory renderer.
7. Generate the initial snapshot only after the live guidance guards are green:

   ```bash
   ./gradlew :jfr-mcp:test \
     --tests io.jafar.mcp.ToolCatalogSnapshotTest \
     -Dmcp.updateSnapshot=true
   ```

   Review `mcp-contract.json` as a public API diff. It must include all tool schemas, rendered
   prompt messages, and resource contents. Never regenerate it blindly during ordinary test runs.
6. Rerun the normal snapshot test without the update switch. Its exact comparison must pass.

Gate commands:

```bash
./gradlew :jfr-mcp:test --tests io.jafar.mcp.ToolCatalogSnapshotTest
./gradlew :jfr-mcp:exportMcpContract
```

Inspect `jfr-mcp/build/mcp-contract/mcp-contract.json` as parsed JSON, compare it with the
committed snapshot, and confirm that it has rendered prompt/resource fields rather than metadata
only. The test is the authority for semantic equality; a byte diff is an optional readability
check.

R5 proof: in a disposable working change, alter one rendered resource or prompt message while
leaving its metadata unchanged. `ToolCatalogSnapshotTest` must fail and identify the contract
change. Restore it. Separately break/export a deliberately different temporary payload (or point
the test at a modified temporary output) to prove the export-versus-snapshot assertion fails.
For the dynamic resource guard, deliberately make `GuidanceContractContext` reuse the seeded stale
store (or omit its clean-store override); `GuidanceContractSessionIsolationTest` must fail by
naming the leaked alias in `jafar://sessions`. Restore before proceeding.

Stop conditions:

- If the snapshot contains test-machine paths, timestamps, or non-deterministic session state,
  stop. The empty `jafar://sessions` resource is required, but must be rendered through the clean
  contract context; do not omit it or bless machine-specific JSON.
- If the exporter and snapshot require separate traversal/serialization code, stop and consolidate
  through `McpContractExport` plus `McpContractJson` before accepting the baseline.
- If a contract diff changes an MCP tool/schema, consult the separate perf-box repository before
  claiming compatibility. Do not claim its skills were updated from this repository unless that
  work was actually authorized and completed.

### 3. Tier 0 completion gate

Run, in order:

```bash
./gradlew :jfr-mcp:test \
  --tests io.jafar.mcp.ToolCatalogSnapshotTest \
  --tests io.jafar.mcp.GuidanceExampleQueriesTest \
  --tests io.jafar.mcp.GuidanceToolReferencesTest \
  --tests io.jafar.mcp.ToolDescriptionQualityTest
./gradlew :jfr-mcp:test
./gradlew :jfr-mcp:exportMcpContract
```

Do not start Tier 1 until all three commands pass. Report test totals and named failures, if any,
rather than a count-only comparison. If the complete module suite has standing recording-resource
failures, capture and compare the exact failing names against the pre-change baseline; stop for any
new named failure.

## Gate 2 — promote the HPROF fixture without production leakage

Files:

- `hdump-parser/build.gradle`
- move `hdump-parser/src/test/java/io/jafar/hdump/test/SyntheticHeapDumpGenerator.java` to
  `hdump-parser/src/testFixtures/java/io/jafar/hdump/test/SyntheticHeapDumpGenerator.java`
- HPROF parser tests that import the generator (update imports/source-set dependencies only as
  needed)
- `jfr-mcp/build.gradle`

Actions:

1. Add the `java-test-fixtures` plugin to `hdump-parser`, matching `pprof-parser` and
   `otlp-parser`.
2. Move—not copy—the synthetic generator to `src/testFixtures`. Keep the existing package so its
   existing test imports remain stable. Add the JUnit dependency required by any `hdump-parser`
   tests that continue to compile; test fixtures themselves must expose only what the generator
   actually needs.
3. Exclude `testFixturesApiElements` and `testFixturesRuntimeElements` from Maven publication, as
   the pprof/OTLP parser projects do, so the synthetic writer is a build-time testing capability,
   not a production artifact.
4. Add `testImplementation(testFixtures(project(':hdump-parser')))` in `jfr-mcp/build.gradle`.
   Do not add a custom HPROF writer to `jfr-mcp`.
5. Make a minimal fixture whose data is distinguishable from an empty/stale session. If the
   existing minimal generator cannot encode a distinctive class/object count needed by the report
   and query assertions, extend the promoted fixture once with named, deterministic content (for
   example a unique class name and known instance count), then preserve its existing parser-test
   methods.

Gate commands:

```bash
./gradlew :hdump-parser:test
./gradlew :jfr-mcp:test --tests io.jafar.mcp.HdumpHandlerTest
```

R5 proof: temporarily remove the `testFixtures(project(':hdump-parser'))` dependency or rename the
fixture import. The MCP E2E test compilation must fail, proving it consumes the promoted fixture;
restore it before recording a green test. Also change its expected distinctive value in the new E2E
test and observe the named test fail after the E2E tests have been added.

Stop condition: if moving the fixture would force `hdump-parser` production code to depend on test
APIs or would publish fixture classes, stop and correct Gradle source-set/publication wiring first.

## Gate 3 — Tier 1 subprocess workflow harness

Files:

- refactor/add under `jfr-mcp/src/test/java/io/jafar/mcp/`, preferably:
  - migrate `McpEndToEndTest.java` to the shared child-process harness for its existing
    progress-token regressions (it must no longer call its private `startServer` directly);
  - add `McpAgentWorkflowEndToEndTest.java` for the four workflows; and
  - add a package-private `McpStdioProcessHarness.java` for the shared local-JAR command,
    JSON-RPC protocol, progress-notification collection, and owned-directory cleanup.
- `jfr-mcp/build.gradle` to explicitly exclude `e2e` from `test`, explicitly include it in
  `endToEndTest`, and make only `endToEndTest` depend on `shadowJar`.

Do not turn `McpTransportHarness` into the Tier 1 proof: it constructs `JafarMcpServer` in the test
JVM and cannot prove shadow-JAR command line, manifest, stdio, or child-session isolation.

The normal tagged E2E suite uses only the locally built shadow JAR. Remove the existing
`mcp.e2e.use.jbang` branch from `McpEndToEndTest` and from the `endToEndTest` task's forwarded
properties, rather than leaving a branch that can bypass the required child JVM properties. A
published/JBang check, if separately desired later, is manual/release coverage outside this task
and must have its own documented isolation strategy.

Selection proof (run after changing `jfr-mcp/build.gradle`):

```bash
./gradlew :jfr-mcp:test --info
./gradlew :jfr-mcp:endToEndTest --tests io.jafar.mcp.McpEndToEndTest --info
```

The first invocation must show neither `McpEndToEndTest`/`McpAgentWorkflowEndToEndTest` execution
nor `:jfr-mcp:shadowJar`; the second must select the tagged existing class and run through
`:jfr-mcp:shadowJar`. Add an explicit Gradle-level assertion or a narrowly scoped task-selection
test if the log alone cannot establish this reliably in CI. R5: temporarily remove
`excludeTags 'e2e'` and observe a normal `:jfr-mcp:test` attempt to select the tagged class; then
restore the exclusion and prove it is absent. Separately remove `endToEndTest.dependsOn shadowJar`
in a disposable change and run from a clean `jfr-mcp/build/libs` so the selected E2E test fails for
the missing JAR; restore the dependency.

### Harness design

1. For each test launch a fresh local shadow JAR with:

   ```text
   <current-java>
   -Djafar.mcp.sessions.file=<absolute unique-dir>/sessions.json
   -Duser.home=<absolute unique-dir>/home
   -jar <absolute jfr-mcp-*-all.jar>
   --stdio
   ```

   `unique-dir` must be created under `jfr-mcp/build/mcp-e2e/`, using a test-name-safe unique
   suffix (UUID/counter). The test owns only that directory. The harness must normalize both paths
   and assert `sessions.json` and `home` start with that unique directory before it starts the
   process. This is a defensive double lock: an ignored explicit session property still points the
   server only at the private `user.home`, never at the developer's `~/.jafar`. Both
   `McpEndToEndTest` and `McpAgentWorkflowEndToEndTest` must instantiate this same harness; no
   legacy child launch is exempt from path ownership, child stderr capture, shutdown, or cleanup.
2. Use JSON-RPC lines over child stdin/stdout. Send `initialize`, assert a successful response, then
   send `notifications/initialized`. `tools/call` must send real `arguments`; for analyses that
   report progress, attach a numeric `_meta.progressToken` and a stable test tool-use id. The reader
   queues every JSON response/notification and waits by request id without losing out-of-order
   progress messages.
3. A successful response means: matching JSON-RPC id, no top-level `error`,
   `result.isError == false`, and parsed content containing the expected structured JSON. Retain
   the full response in every assertion failure (R9). Never use `Process.isAlive()` or exit status
   as the only assertion.
4. The message collector must additionally retain and select `notifications/progress` messages
   emitted while a request is in flight. For every progress-capable analysis invoked with numeric
   token `N`, assert at least one such notification, assert
   `params.progressToken.isIntegralNumber()`, and assert `params.progressToken.asInt() == N` for
   every notification associated with that call. Include the raw notification in failure output.
   This asserts both JSON type and value, rather than merely proving that the server remained
   alive after the historic string-token disconnect.
5. Shut down deterministically: send family close calls in each workflow, close stdin, allow a
   bounded graceful wait, then forcibly destroy only the test-owned child as a cleanup fallback.
   Stop the reader thread and retain stderr in a bounded buffer rather than discarding it, so an
   E2E failure names the child error. In `finally`, recursively delete the exact unique directory
   and assert `Files.notExists(uniqueDir)`. If cleanup fails, fail the test and leave the resolved
   path in its message for inspection. Never recursively delete the broader `build/mcp-e2e` root.
6. Assert isolation behavior explicitly: each launch has a different canonical session path and
   home; the server starts with no prior session; no configured path escapes its directory; and the
   directory exists only while that harness is active. A second process/workflow must not see the
   first process's aliases or sessions. Prove this with an initial `jafar://sessions` resource read
   (or the equivalent live session listing) and a fresh-process assertion before any open call.
7. The harness needs no external service, downloaded recording, user state, API key, or network
   access. If a test needs any of these, stop; construct a deterministic fixture instead.

## Gate 4 — implement the four black-box workflows

Put these in `McpAgentWorkflowEndToEndTest`, tagged `e2e`, so the existing
`./gradlew :jfr-mcp:endToEndTest` task runs them after `shadowJar`.

### JFR workflow

Fixture: the small repository-owned `demo/src/test/resources/test-dd.jfr` already selected by
`McpEndToEndTest.findTestJfr()` (with its existing explicit override only for local reproduction).

Sequence:

1. `jfr_open` with absolute path and alias; assert successful result has a numeric/id-like session
   identifier and the expected path/alias evidence.
2. `jfr_summary`; assert successful structured recording summary, not just a success message.
3. `jfr_query` with a known-valid query suited to the fixture; assert the result fields the next
   step uses and non-empty/fixture-valid evidence. Do not assert a fragile global count.
4. Call one guidance-recommended analysis (`jfr_diagnose` is preferred because it exercises numeric
   progress token delivery; use `jfr_use` only if the small fixture cannot support diagnose's
   contract). Attach a numeric progress token (for example `4`). Assert findings/content has the
   documented shape, not prose alone, and assert every matching `notifications/progress` payload
   carries integral token value `4` exactly.
5. `jfr_close`; then issue a further safe request (for example `jfr_help` or resource read) and
   assert it receives a response, proving the child still accepts requests after analysis/close.

### HPROF workflow

Fixture: create it in the unique test directory with the promoted
`io.jafar.hdump.test.SyntheticHeapDumpGenerator`, using distinctive class/object content and a
known count.

Sequence:

1. `hdump_open` with alias; assert id/path/alias returned.
2. `hdump_summary`; assert the expected heap/class/object evidence for the synthetic dump.
3. `hdump_query` using a query accepted by HdumpPath and capable of returning the distinctive
   fixture data; inspect `resultCount` and returned rows, not a prose success message.
4. `hdump_report`; assert a successful structured `findings` array or documented report fields and
   at least one fixture-derived fact. If a deliberately minimal heap legitimately has no findings,
   assert its stable structured empty result plus the summary/query evidence; do not manufacture a
   fake leak merely to obtain a finding.
5. `hdump_close`, then a subsequent request and response as above.

### pprof workflow

Fixture: write a profile into the unique directory with `MinimalPprofBuilder`: choose a distinctive
function name (for example `tier1.pprof.UniqueCpuMethod`), a known sample type and value, plus a
label/query field supported by the parser. The exact query must be accepted by PprofPath before it
is used in the subprocess test.

Sequence:

1. `pprof_open`; assert session/profile metadata.
2. `pprof_summary`; assert known profile/sample evidence.
3. `pprof_query`; assert rows include the distinctive function/sample evidence and expected count
   or value.
4. `pprof_use` (or the documented supported alternative only if the synthetic profile cannot
   produce a meaningful USE response); attach a numeric progress token and assert successful
   structured analysis, fixture-derived evidence where the tool's contract supplies it, and exact
   integral token propagation in all progress notifications.
5. `pprof_close` and a later successful request.

### OTLP workflow

Fixture: write an OTLP profile into the unique directory with `MinimalOtlpBuilder`, with a unique
function/attribute and known sample value; do not reimplement protobuf encoding in `jfr-mcp`.

Sequence:

1. `otlp_open`; assert session/profile metadata.
2. `otlp_summary`; assert the known fixture characteristics.
3. `otlp_query`; assert the unique function/sample/attribute survives the actual stdio JSON.
4. `otlp_use`; attach a numeric progress token and assert successful structured analysis with
   fixture-derived evidence where applicable plus exact integral token propagation in every
   progress notification.
5. `otlp_close` followed by another response.

For every workflow, capture and assert the actual decoded `result.content` JSON and relevant
`findings`, rows, session id, or summary fields. Payload assertions must distinguish the created
fixture from an empty fallback, a stale prior session, and a response from the wrong format family.
If the production response serializes JSON as text content, parse that text before asserting;
asserting only the surrounding MCP envelope is insufficient.

R5 proof for the new safety net:

1. Temporarily change each fixture's distinctive function/class/value while retaining the test's
   old expected value. Run its named workflow test and observe a failure that identifies the
   payload assertion; restore it. One proof per fixture family is required.
2. Temporarily remove the post-analysis/follow-up request or make the server-side analysis response
   fail in a disposable change; the named JFR workflow must fail. Restore it.
3. Temporarily change the server-side progress token to a string or alter the harness expectation
   from the sent integer (`4`) in a disposable change. The migrated `McpEndToEndTest` must fail on
   the notification's token type/value assertion, not only because a process exits or a response
   is missing. Restore it before proceeding.
4. Temporarily force the harness session path outside its unique directory. Its pre-launch path
   assertion must fail before launching a child. Restore it. This is the isolation R5 proof.

Stop conditions:

- A workflow is not complete if it uses an in-process server, a mocked handler, a test-only MCP
  registration, or a second protocol implementation that does not run `java -jar ... --stdio`.
- A test that passes with a synthetic file but does not inspect its distinctive data is not complete.
- A cleanup failure, path outside `build/mcp-e2e`, child process leak, or cross-workflow session
  visibility is a Tier 1 blocker, not a tolerated test flake.
- If an analysis cannot operate on a minimal valid fixture, document the specific contract reason
  and select the approved documented alternative; do not convert the workflow to per-tool smoke
  testing or introduce a downloaded large recording.

## Gate 5 — full validation and handoff

Run formatting first, inspect its diff, then validate in this order:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew :hdump-parser:test
./gradlew :jfr-mcp:test \
  --tests io.jafar.mcp.ToolCatalogSnapshotTest \
  --tests io.jafar.mcp.GuidanceExampleQueriesTest \
  --tests io.jafar.mcp.GuidanceToolReferencesTest \
  --tests io.jafar.mcp.ToolDescriptionQualityTest
./gradlew :jfr-mcp:test
./gradlew :jfr-mcp:exportMcpContract
./gradlew :jfr-mcp:endToEndTest
```

Finally inspect:

1. `jfr-mcp/build/mcp-contract/mcp-contract.json` parsed against the committed snapshot;
2. the exact subprocess command/isolated paths and captured JSON payloads for all four workflows;
3. `jfr-mcp/build/mcp-e2e/` for no leftover per-test directories; and
4. `git diff --check` plus the final diff, confirming no generated build file or user session file
   is staged.

If the broader repository-required release check is desired before PR, run the documented
`./gradlew test shadowJar` after the gates above. Report separately any tests not run and any
pre-existing downloaded-recording failure set by name. Do not describe the JBang/published path as
verified unless `-Dmcp.e2e.use.jbang=true` was deliberately run with a known published version.

The commit/PR must state:

- the Tier 0 snapshot fields now guarded and the explicit snapshot review mechanism;
- the four Tier 1 workflows and their fixture-specific payload evidence;
- the isolated child session/home and cleanup proof;
- each R5 temporary-break experiment and the named failing test it produced; and
- anything not verified (especially the optional published JBang route or unavailable large
  recordings).
