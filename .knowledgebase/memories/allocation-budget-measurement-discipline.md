---
id: allocation-budget-measurement-discipline
title: "Allocation gates budget bytes, never time: two warm-up parses, pool-thread absolutes marked from onChunkEnd, and a C1 pin - or the same measurement wobbles by whole percents"
kind: finding
tags: [allocation, budget, measurement, ci, c1, escape-analysis, threadmxbean, gates]
applies_to: [test-support/src/main/java/io/jafar/testsupport/AllocationMeter.java, gradle/allocation-budgets.properties]
source: generated-code-safety-harness/design-allocation
check: grep -q "TieredStopAtLevel=1" parser-core/build.gradle && grep -q "markCurrentThread" test-support/src/main/java/io/jafar/testsupport/AllocationMeter.java
status: active
recorded: 2026-11-07
valid_at: 2026-11-07
---


The measurement discipline that makes a per-parse allocation gate deterministic
(measured to the byte); deviate from any item and the budget flakes or hides
regressions.

**Budget bytes, never time.** Thread-allocated bytes per parse are
byte-identical across runs after warm-up, while identical work's wall time
varied ~5x within a single process (245ms down to 52ms across runs of the same
probe). A wall-clock gate is flaky or so loose it is inert; a bytes gate is both
sensitive and stable.

**Discard TWO warm-up parses.** The first parse pays ~14x the steady state in
lazy parser/context initialization, and JDK 21 has one more lazy step after it
(JDK 8 does not) - one warm-up parse is not enough on 21. Warm-up happens
outside the meter, never inside it.

**Pool threads need their own accounting.** Chunk callbacks allocate on pool
threads, and the pool is fresh per `StreamingChunkParser` - the threads die when
the parse ends and `getThreadAllocatedBytes(long)` reads -1 for a dead thread.
`AllocationMeter#markCurrentThread` must therefore be called from the callback
itself (`onChunkEnd`, or the event handler on the same thread) while the thread
is alive. Pool values are lifetime absolutes, valid only because the threads are
fresh per parse; `AllocationMeter#assertFreshPoolThreads` fails a reused thread
id. The meter must not perturb what it measures: it allocates once per thread
(the `computeIfAbsent` cell via a non-capturing lambda instantiated once) and
nothing per callback.

**Pin the JVM.** The allocation tasks run with `-XX:ActiveProcessorCount=4`
(chunk pool = 2 = the recording's 2 chunks) and `-XX:TieredStopAtLevel=1`
(`parser-core/build.gradle`, `parser-codegen/build.gradle`). Without the C1 pin,
C2's escape analysis eliminates a timing-dependent share of the pool-thread
allocation, wobbling the same measurement by 0.6-6% between runs; under C1 it is
byte-identical - the committed budget figures (46,384,688 bytes, three
consecutive identical runs, four JDK 21 builds across vendors plus two JDK 8
vendors, recorded in `gradle/allocation-budgets.properties`) hold only with the
pin.

**The ratchet.** Budgets live in `gradle/allocation-budgets.properties`, keyed
per JDK (`...jdk21=`, `...jdk8=` - the cross-JDK delta is real, hundreds of
bytes on the probe fixture; never share one key across JDKs). Raising a value or
a tolerance requires `Alloc-Budget: <reason>` in the PR body
(`scripts/alloc-budget-check.sh`, sibling of the parity gate); lowering needs no
statement, and a measurement more than the tolerance BELOW the budget fails with
"re-tighten", so a stale loose budget cannot linger. Recorded sensitivity floor:
a regression smaller than the tolerance slips through (one extra 16-byte array
per event = ~1.08 MB, caught with ~500x margin).

Evidence class: proven by experiment (the byte-identical measurement table and
the C1-pin A/B comparison recorded in the `gradle/allocation-budgets.properties`
header; the 5x wall-time variance in the `alloc-budget-feasible` source node)
and proven from source (`AllocationMeter.java`, the `jvmArgs` lines in the
build files).
