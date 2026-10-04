---
id: dead-env-classification
type: deadend
depends_on: [ev-run-timings]
status: refuted
created: 2026-10-03
updated: 2026-10-03
---

# 'Environmental/flaky' — refuted by measurement

The initial classification (recorded in the session report) treated the failure as a
pre-existing environmental issue with an honest NOT-VERIFIED note. The user pushed to
investigate; three cheap probes refuted it:
- A raised deadline made the SAME runs pass (3/3) — a stable failure, not a race.
- Pristine origin/main reproduced it identically — pre-existing, yes, but not environmental.
- Method profiling + an in-executor parse bench located the cost inside the tool's own
  re-parse fan-out — a load-independent floor of ~6–10s that only *crosses* the 15s deadline
  when the box is loaded.

Lesson: 'environmental' means proven environmental; otherwise it is an unmeasured boundary.