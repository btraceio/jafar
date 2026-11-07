---
id: javago-parity-differential-oracle
title: "Java/Go untyped-parser parity: the Parity statement carries intent, the canonical-dump differential proves the values, and the allowlist is shrink-only with root-caused entries"
kind: finding
tags: [parity, go, java, differential, untyped-parser, canonical-dump, allowlist, oracle]
applies_to: [scripts/parity-check.sh, go-parser/internal/differential/dump.go, test-support/src/main/java/io/jafar/testsupport/jfr/JfrCanonicalDump.java]
source: generated-code-safety-harness/a3-go-java-differential
check: grep -q "jafar-dump v1 side=go" go-parser/internal/differential/dump.go && grep -q 'DUMP_FORMAT = "# jafar-dump v1"' test-support/src/main/java/io/jafar/testsupport/jfr/JfrCanonicalDump.java
status: active
recorded: 2026-11-07
valid_at: 2026-11-07
---


The Java/Go parity contract has two halves, and neither replaces the other.

**The statement (intent).** The Java untyped parser
(`parser-core/src/main/java/io/jafar/parser/{impl,internal_api}`) and the Go port
(`go-parser/jfr`) are two implementations of the same format and the same value
model. A PR that changes production code on exactly one side fails CI
(`scripts/parity-check.sh`) until its body carries `Parity: ported in this PR` or
`Parity: not applicable — <reason>` (name the direction). Test-only changes,
fixtures and testdata do not count. The statement carries the port/intent
decision, which is judgment; it cannot prove the values.

**The differential (values).** One canonical dump format (R7), rendered by
`JfrCanonicalDump`
(`test-support/src/main/java/io/jafar/testsupport/jfr/JfrCanonicalDump.java`)
for the golden tests and the Java side, mirrored line for line by the Go side
(`go-parser/internal/differential/dump.go`): header
`# jafar-dump v1 side=<java|go> fixture-sha256=<hex> events=<n>` (or
`error=<msg>` - a failed dump can never be mistaken for an empty one), then one
line per event, sorted, `<type> k=v ...` with:
- values from `Values#resolvedDeep`, never `resolvedDeepCollapsed` - it must
  match Go's `ResolveDeepMap` (see the jfr-value-shapes memory for why the
  collapsed form diverges);
- dotted paths into nested maps, sorted; arrays indexed `.0 .1 ...`;
- `byte[]` as `b<len>:<hex>`;
- floats/doubles as `f:` plus the 16 hex digits of the IEEE-754 bit pattern
  (`Double#doubleToLongBits`) - never decimal formatting: `%.9g` vs `%v` alone
  produced 358 false diffs out of 67,657 lines on the first prototype run;
- `\n`/`\r` escaped (real recordings embed raw newlines: /proc/cpuinfo,
  os-release - naive line counting saw 30 phantom events);
- no chunk identity anywhere - chunks parse in parallel, and a re-chunked
  recording must dump identically.

The Go harness lives in `go-parser/internal/differential`, deliberately NOT in
`go-parser/jfr`: parity-check.sh would otherwise see every harness change as a
one-sided Go production change.

**The allowlist (shrink-only).** `parser-core/src/test/resources/differential/allowlist.txt`
is the corpus-level allowlist the comparator (`DumpComparator`) enforces. No
tool ever writes it; an entry is added only by a visible hand edit, always as a
`# reason:` comment immediately followed by the entry, only after the divergence
it covers is root-caused. `equiv <type> <fieldPath> <rule>` normalizes both
sides' rendering of that field BEFORE the compare - the values are still
compared, so an equiv can never hide a real value divergence. `diverge <type>
<fieldPath> side=<java|go>` excludes the field and names the side at fault. An
entry that matches nothing fails with "stale allowlist entry", so fixing a
divergence is forced to shrink the list.

**The pattern entry (the endpoint proof).** The one committed equiv is
`datadog.Endpoint endpoint unwrap-empty-key`: chunk-2 metadata declares
`java.lang.String` as a primitive class with zero metadata fields, so a pooled
entry has no field name - the Java map path keys it "" (rendering `endpoint.=V`)
while Go's constant-pool path special-cases `java.lang.String` and returns the
scalar (`endpoint=V`). Third oracle: `jfr print` renders the same scalar string
both parsers hold. Identical values, representation only - an equiv, not a
diverge. (Both parsers refusing the same malformed fixture is agreement, not a
divergence - the node-pool cases.)

Evidence class: proven by experiment (tck-test.jfr, 67,657 events both sides,
identical per-type histograms; the two systematic rules above were the only
residues) and proven from source for the committed entry (code paths cited in
the allowlist's root-cause block; the never-hides-a-divergence behavior is
fire-tested in `DumpComparatorTest`).
