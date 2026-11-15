---
id: chunk-callback-concurrency-hazard
title: "JFR chunk callbacks run concurrently on a per-parse pool: listener fields must be chunk-keyed or thread-confined, and only a processor-count matrix catches the race"
kind: finding
tags: [jfr, concurrency, chunk-callback, chunk callback, streaming-chunk-parser, ci, thread-safety, determinism]
applies_to: [parser-core/src/main/java/io/jafar/parser/internal_api/StreamingChunkParser.java, .agents/skills/verify-change/SKILL.md]
source: generated-code-safety-harness/design-concurrency
check: grep -q "max(nproc-2,1)" .agents/skills/verify-change/SKILL.md && grep -q "Concurrency matrix" .github/workflows/pr-tests.yml
status: active
recorded: 2026-11-07
valid_at: 2026-11-07
---


The one proven jafar-specific concurrency hazard: a `ChunkParserListener`'s
callbacks for *different chunks of one recording* run in parallel.

**The hazard.** `StreamingChunkParser`
(`parser-core/src/main/java/io/jafar/parser/internal_api/StreamingChunkParser.java`)
parses each chunk of the file-mapped `parse(Path, ...)` route as a task submitted
to a fixed pool of `Math.max(availableProcessors() - 2, 1)` daemon threads
(`StreamingChunkParser#executor`, `submitParsingTask`); only the
`parse(InputStream, ...)` route is sequential. A plain instance field in a
listener is therefore written by two chunk threads and one chunk's values
silently overwrite the other's - the parse stays correct-looking on a
single-threaded run. Proven by experiment, three live reproductions (a probe
collecting into a plain `ArrayList` from the event handler lost 30 of 67,657
events; see the `probe-counter-race` evidence node in the source trail).

**The rule.** Any field touched in a chunk callback must be keyed by the chunk
index (`getChunkIndex()`, per-chunk state) or be thread-confined (`ThreadLocal`,
or a local captured per chunk). The advisory rule and the exact verification
commands live in `.agents/skills/verify-change/SKILL.md` under "Chunk-callback
state" - not every hazard has a gate, this one is caught by review plus the
matrix below.

**The matrix.** A 1-2 core CI runner cannot reproduce the race, so the existing
determinism nets (MultiChunkDeterminismTest, ConstantPoolChunkLocalityTest,
JFRSessionDeterminismTest) run in CI at `-XX:ActiveProcessorCount=1` AND `=16`
(the `Concurrency matrix` job in `.github/workflows/pr-tests.yml`), with the JUnit
XML carrying an `availableProcessors=<n>` audit so a silently unpinned run fails.
This catches "works on the runner, races on the laptop" for zero new test code.

Java has no in-process equivalent of `go test -race`; the matrix plus the
shared-ParsingContext stress tests are the Java answer to the same threat, not a
port of -race. Do not add a runtime cross-chunk-mutation detector to the product
surface: it is indistinguishable in general from deliberate concurrent designs
and refuted for product API (see the `a5-detector-decision` node in the source
trail).

Evidence class: proven from source (the executor construction and
`submitParsingTask` in StreamingChunkParser.java; the rule text in
verify-change/SKILL.md) and proven by experiment (the probe reproductions; the
matrix cells were proven to fire on the hazard, R5).
