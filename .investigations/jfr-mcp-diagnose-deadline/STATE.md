# Current State

## Active investigation
None — concluded 2026-10-03. Status `done` in meta.yaml; root cause found, fix merged in
`259a0da` (MCP transport tests: raise the response deadline to 60s).

## What was concluded
`jfr_diagnose` (default `depth=full`, `includeAnalysis=true`) re-parses the whole recording for
each of its six sub-analyses; a warm full untyped parse of the 2.2 MB test recording measures
0.45–0.73 s (0.53–0.73 s under the test JVM's jacoco/mockito agents). ~10–12 passes ⇒ 13–21 s
real response time, vs the harness's 15 s deadline — the boundary is inside the natural range,
so the test failed whenever the box was loaded (including on pristine origin/main) and passed
under any raised deadline. Fix: default 60 s + forward `-Dmcp.test.timeout.ms`.

## Open questions (for a successor)
- q-parser-perf — the diagnose cost shape is architectural: `MetadataField.hasConstantPool`
  ≈ 15 % of CPU in the profile; each parse spawns a fresh fixed pool of
  `availableProcessors()-2` unnamed daemon threads (`StreamingChunkParser` thread factory has no
  naming/teardown until session close); agents tax every test JVM 15–40 % (paired measurements).
  Any redesign is a planned-effort item, not a gate blocker.

## Ruled out (don't re-investigate)
- Heap: 8 GB vs 2 GB — identical timings.
- GC pressure: JFR profile run — 6 collections / 45 s, longest pause 26 ms.
- Logging: one shared main/resources/logback.xml on both in-process and child paths; parser at WARN.
- Hang: three 120–180 s deadline runs all returned 13–21 s responses.
- Backend/wiring mismatch: both paths register the same `JafarMcpServer` tool specs and the same
  jf shell-jafar backend (checked harness construction, `McpServer.sync`).

## Fix that landed
`McpTransportHarness.RESPONSE_TIMEOUT_MS` 15_000 → 60_000; `:jfr-mcp:test` forwards
`-Dmcp.test.timeout.ms` (the harness failure message advertises it, the task used to ignore it).
R5 two-sided proof: 2000 ms fails the named test; default passes the whole class.
Case file: doc/agents/Verification.md under R6 ("the diagnose deadline between green and failure").