---
id: ev-parse-bench
type: evidence
created: 2026-10-03
updated: 2026-10-03
---

# Warm full untyped parse of the 2.2MB test recording (in-executor bench)

Throwaway JUnit bench (`io.jafar.mcp.TmpParseBench`, deleted after the runs) driving
`ParsingContext.create().newUntypedParser(test-dd.jfr)` + `handle` + `run()` inside the real
Gradle test executor — no transport, no harness:

| Config | runs (events=67657) |
|---|---|
| With agents (default test task: jacoco + mockito agents, `cores=12`) | 1.793s cold, 0.727s, 0.529s |
| Agents stripped (init script removed both `-javaagent` flags) | 1.980s cold, 0.588s, 0.449s |

Agent cost at the parser level ≈ 15–25%; at the tool level (use/tsa/diagnose) the paired
test runs showed 15–40% — the analysis layer pays per-method-entry probes too, not just the
parse loop.

Implication used for the fix: diagnose(full) ≈ six sequential sub-analyses, each re-running
whole-recording queries (USE fans out over ~4 event families, TSA ~4); ~10–12 parse-equivalents
× ~0.5s ⇒ 6–10s ideal CPU, 13–21s observed with agents + load. The 15s harness deadline was
inside the tool's natural range.