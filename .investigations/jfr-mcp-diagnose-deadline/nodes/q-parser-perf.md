---
id: q-parser-perf
type: question
status: open
related: [find-deadline-vs-real-cost, ev-parse-bench, ev-jfr-profile]
tags: [parser, perf, follow-up]
created: 2026-10-03
updated: 2026-10-03
---

# Parser/analysis cost follow-ups (data collected, redesign out of scope here)

Open threads a future perf effort owns, with numbers already on hand:

1. **`MetadataField.hasConstantPool` ≈ 15% of CPU** (695 of 4.4k samples in 45s) — metadata
   lookups are hot in the parse loop; worth a look at caching the answer or restructuring the
   call site (ev-jfr-profile).
2. **Per-parse executor churn**: each `JfrPathEvaluator` evaluation constructs a fresh
   `UntypedJafarParser`/`StreamingChunkParser` with a fixed pool of
   `availableProcessors()-2` (12 cores ⇒ 10 threads) unnamed daemon threads (factory in
   `StreamingChunkParser` line ~29 does not name them; pools only tear down at session
   `close()`). In long-lived MCP sessions that re-query, pools accumulate idle. Candidates:
   shared/pooled executor + named threads (the repo's thread naming conventions), or a
   reusable parser per session.
3. **Agent tax on every test JVM** (jacoco + mockito inline agents: 15–25% at parser,
   15–40% at tool level, paired measurements). CI pays this on the whole suite; consider
   coverage scope trims if CI time matters (ev-parse-bench).

None of these are correctness bugs and none block the e2e/eval gate; they are the follow-up
cost story the diagnose-deadline fix bought headroom for.