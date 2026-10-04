---
id: ev-run-timings
type: evidence
created: 2026-10-03
updated: 2026-10-03
---

# Test-run timing series for McpJfrTransportTest.jfrDiagnoseReturnsReport

All runs `:jfr-mcp:test --tests io.jafar.mcp.McpJfrTransportTest` unless noted; testcase wall
time from JUnit XML (includes jfr_open + harness teardown).

| Run context | Deadline | Result |
|---|---|---|
| Branch-tip full suite (`:jfr-mcp:test`, 306 tests) | 15s | FAILED (only this test) — pre-rebase baseline |
| Focused, branch tip | 15s | FAILED at 17.8s testcase time (open ≈2.3s + 15s expiry) |
| Pristine origin/main worktree (`/tmp/jafar-main-wt`) | 15s | FAILED at 20.5s — same assertion, same message |
| Focused, `-Dmcp.test.timeout.ms=60000` (NOT forwarded at the time — no-op) | 15s | FAILED identically → forwarded-property gap discovered (R4) |
| Focused, `-Dmcp.test.timeout.ms=120000` (forwarding added) | 120s | green, 17.9s testcase |
| Focused under sampling, 180s | 180s | green, 20.9s (use 8.1s — box loaded: concurrent drydock-guided-tour suite observed with top) |
| Focused, agents stripped via init script | 180s | green, 13.1s (use 4.5s, tsa 2.3s, help 3.9s) |
| Heap ablation: `-Xmx8g` (2g default) | 15s | FAILED 18.4s — heap not the cause |
| Default after fix (60s) 2 | 60s | green, `:jfr-mcp:test --rerun-tasks` 404/404 |

Evidence sources: /tmp/baseline_test.log, /tmp/main_diag_test.log, /tmp/diag_timeout.log,
/tmp/diag_120s.log, /tmp/diag_sampling_run.log, /tmp/noagents_run.log, /tmp/final_test.log.