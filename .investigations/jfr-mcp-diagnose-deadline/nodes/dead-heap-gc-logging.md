---
id: dead-heap-gc-logging
type: deadend
depends_on: [ev-jfr-profile, ev-run-timings]
status: refuted
tags: [heap, gc, logging]
created: 2026-10-03
updated: 2026-10-03
---

# Heap, GC and logging causes — refuted with data

- Heap: `-Xmx8g` ablation produced identical failing timings (18.4s testcase at the 15s
  deadline) vs the 2g baseline (17.9s) — no meaningful change (ev-run-timings).
- GC: JFR `profile` run of the focused class recorded 6 collections in 45s, longest pause
  26.3ms — nowhere near the seconds-scale cost being explained (ev-jfr-profile).
- Logging: one shared `jfr-mcp/src/main/resources/logback.xml` serves BOTH the in-process test
  classpath and the shadow-jar child; `io.jafar.parser` is WARN in both, `io.jafar.mcp` DEBUG
  (the DEBUG lines are tiny single lines per JSON-RPC message). A per-event DEBUG theory would
  also have to explain why the child JVM is fast — it isn't per-event (ev-jfr-profile,
  harness config).

Each probe was one command; together they closed the space so the parser-attribution evidence
could carry the conclusion alone.