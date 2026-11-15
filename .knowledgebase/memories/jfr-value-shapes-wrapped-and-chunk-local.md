---
id: jfr-value-shapes-wrapped-and-chunk-local
title: JFR untyped values arrive wrapped and type ids are chunk-local - resolve with Values#resolvedDeep and key nothing by a class id
kind: finding
tags: [jfr, data-shapes, untyped-parser, constant-pool, metadata, wrapped-constants, chunk-local-type-ids, shapes-that-lie]
applies_to: [parser-core/src/main/java/io/jafar/parser/api/Values.java, doc/agents/DataShapes.md]
source: generated-code-safety-harness/design-format-correctness
check: grep -q "A string constant is not a string" doc/agents/DataShapes.md && grep -q "A type id is not a type" doc/agents/DataShapes.md
status: active
recorded: 2026-11-07
valid_at: 2026-11-07
---


Two recurring wrong-shape failure classes in the JFR untyped value model (the
"shapes that lie" catalogue lives in `doc/agents/DataShapes.md`). Both shipped
green: the existing tests fed shapes the parser never emits, so tests that pass
prove nothing about the payload (R9 - inspect the payload, not the exit status).

**1. A wrapped constant is not its value.** The untyped parser delivers a string
constant as a one-entry map `{string=[B}`, never `[B` or `String`. The
`AllocationAggregator` bug read `objectClass.name` as a plain `String` and every
real recording aggregated to nothing while all tests passed. Resolve through
`Values#resolvedDeep`
(`parser-core/src/main/java/io/jafar/parser/api/Values.java`): it recursively
unwraps `ComplexType` nodes to `Map<String, Object>` and `ArrayType` nodes to the
backing array. `Values#resolvedDeepCollapsed` additionally collapses a reference
to a JFR simple type to its value - but the collapse branch requires
`ComplexType#isSimpleType` (simple type AND exactly one field), which a pooled
`java.lang.String` (a primitive class with zero metadata fields) fails, so even
the collapsed form keeps that one-entry map (proven from source: the
`resolvedDeep(Map, boolean)` collapse branch in Values.java; root-caused for the
Java/Go differential in `parser-core/src/test/resources/differential/allowlist.txt`).
The converse is equally true: a single-entry map is not always a wrapper (a
metadata class with one field flattens to one cell). Decide unwrapping by where
the rows came from (recording values vs structure), never by the shape alone.

**2. A type id is not a type.** A metadata class id names a type *within one
chunk*; chunks written by different producers assign ids independently
(`tck-test.jfr`: id 125 is `jdk.CompilerConfiguration` in chunk 1 and
`datadog.ProfilerCounter` in chunk 2). A cache shared across chunks and keyed by
class id serves whichever chunk generated the entry first - the other chunk's
events decode with the wrong layout, and because chunks parse on parallel
threads, which id wins varies between runs: the failure looks flaky and is
deterministic per ordering. The untyped deserializer cache is keyed by name, id
and full reachable layout with a per-chunk id-to-deserializer map; the typed path
chooses by `MetadataFingerprint`. `MetadataClass#equals` compares id, supertype
and fields but NOT field names or annotations, so it must not key per-class state
either (the `TemporalNormalizer` bug: same-id classes with different `@Timestamp`
annotations shared one normalization table). Anything that outlives a chunk must
not be keyed by a class id.

Evidence class: proven from source (`Values#resolvedDeep` /
`Values#resolvedDeepCollapsed` and their collapse condition in Values.java; the
`UntypedCodeGenerator` cache key and per-chunk map) plus the case files with named
regression tests in `doc/agents/DataShapes.md` ("A string constant is not a
string", "A single-entry map is not always a wrapper", "A type id is not a
type").
