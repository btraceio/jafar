# Investigation Index

## Findings (confirmed)
- find-deadline-vs-real-cost | diagnose( full ) response 13-21s vs 15s deadline; fixed 15s->60s + property forwarding | [jfr-mcp, diagnose, timeout, e2e]

## Hypotheses
(none open)

## Dead ends
- dead-env-classification | Calling it 'environmental/flaky' without measuring | [classification] | REFUTED
- dead-heap-gc-logging | Heap, GC pressure and logging config as causes | [heap, gc, logging] | REFUTED

## Evidence
- ev-run-timings | Three failing 15s runs + three green raised-deadline runs + heap-ablation runs | [timings, runs]
- ev-parse-bench | Warm full untyped parses of the 2.2MB test recording, agent-laden vs agent-free | [parser, bench, agents]
- ev-jfr-profile | jfr ExecutionSample attribution + GC view: parser internals on top, 6 GCs/26ms max | [jfr, profile, gc]

## Questions
- q-parser-perf | Parser/analysis cost shape: hasConstantPool share, per-parse 14-thread daemon pools leaking until session close, agent tax on every test JVM | [parser, perf, follow-up]