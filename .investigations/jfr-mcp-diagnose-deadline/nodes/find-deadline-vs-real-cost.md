---
id: find-deadline-vs-real-cost
type: finding
depends_on: [ev-run-timings, ev-parse-bench, ev-jfr-profile]
status: confirmed
tags: [jfr-mcp, diagnose, timeout, e2e, fix]
created: 2026-10-03
updated: 2026-10-03
---

# diagnose(full) response cost vs the 15s harness deadline

## Reasoning chain
1. Failure signature: harness returns null on deadline — identical on branch tip AND pristine
   origin/main (ev-run-timings) ⇒ not branch-induced.
2. Raised-deadline runs always completed 13–21s and were never above 30s ⇒ slow-not-hung.
3. Heap ablation (8 GB) unchanged ⇒ not memory; JFR GC view (6 collections, ≤26 ms pauses) ⇒ not GC;
   shared logback config with parser at WARN both contexts ⇒ not logging overhead.
4. Method profile: cycles concentrate in raw parser internals (MetadataField.hasConstantPool 695,
   readVarint 650, SWAR 278) — i.e. the recording being re-parsed, repeatedly (ev-jfr-profile).
5. In-executor bench: one warm full untyped parse 0.45–0.73 s (ev-parse-bench); diagnose(full)
   = summary + exceptions + GC + hotmethods + USE(~4 queries) + TSA(~4 queries) ⇒ ~10–12
   parse-equivalents ⇒ 6–10 s ideal, 13–21 s with agents+load.
6. The 15 s deadline is below the tool's real cost ⇒ deterministic failure under load, flake
   elsewhere. Sibling tools (use 4.5–8.1 s, tsa 2.3–3.2 s) fit under it; only diagnose crossed.

## Fix (landed in 259a0da)
- McpTransportHarness.RESPONSE_TIMEOUT_MS 15_000 → 60_000 (3× worst measured; a hang still fails).
- :jfr-mcp:test forwards -Dmcp.test.timeout.ms (advertised in the harness failure message, until
  now silently ignored by the build).
- Two-sided R5: 2000 ms → the named test FAILS again; 60 s default → class and full module green.

## What this rules out
- 'Flaky/environmental' classification without measurement — the boundary flapped only because
  load moved the tool's cost across the deadline; the tool itself was behaving correctly.
- Any production regression from #119/#120 in the diagnose path — the child-JVM e2e workflow
  (fresh process, no agents) runs the same full diagnose inside a 10 s total.