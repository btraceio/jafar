# Shapes that lie

The bugs collected here share one shape: code reads a structure by *assuming* what is inside it,
the assumption is wrong, and nothing complains. No exception, no log line — just an empty list, a
null, or a plausible wrong answer that survives review and testing.

They are collected here because the next one is coming, and it will look exactly like these. When
you read a `Map`, a wrapped value, or a metadata list in this codebase, assume it is not the shape
you expect and check. Add what you find to this list.

---

## The pattern

```java
Object raw = clazz.get("fields");          // exists
if (raw instanceof List<?> list) {          // true
  for (Object entry : list) {
    if (entry instanceof Map<?, ?> field) { // false, for every element
      ...
    }
  }
}
return fields;                              // empty, silently
```

Every step succeeds. The key is right, the type check is right, and the result is empty because the
list holds rendered strings rather than maps. A `getOrDefault`, an `instanceof` that fails, or a
`catch` around the wrong scope all produce the same non-event.

**What catches it:** looking at the value, not the control flow. Print it, assert on it, or drive
the code and read what came out the far end — see
[R9 in Verification.md](Verification.md#r9-inspect-the-payload-not-the-exit-status).

---

## The cases

### A string constant is not a string

The untyped parser delivers a string constant as a single-entry map, `{string=[B}`, not as `[B`.

- **`AllocationAggregator` read `objectClass.name` as a plain `String`.** It was a wrapped constant,
  so every real recording aggregated to nothing. The existing tests all fed a flattened shape the
  parser never emits, so they passed.
- **Egress redaction replaced every wrapped constant.** `string` is in the default redact list —
  meaning *a field named string* — but the wrapper's inner key is literally `string`, so class
  names, symbols and group-by keys reaching the model became `{string=<redacted>}`. The redaction
  looked like it was working. Fixed by unwrapping before the decision, so the decision is taken on
  the outer field's real name.

- **The unified shell printed the wrapper.** `jafar-shell`'s `printResult` handed JFR rows
  straight to `TableFormatter` and its CSV/JSON writers, so `gc-analysis.jfrs` grouped by
  `{name=G1New}` and `thread-profiling.jfrs` by `{string=java/lang/Object}`. jfr-shell never showed
  it because its `TableRenderer.toCell` collapses a single-entry map — a rule that lived in a
  renderer, so a second shell with its own renderer did not inherit it — and nor did jfr-shell's
  own CSV and JSON writers, which printed `{name=G1New}` and `"name": {"name": "G1New"}`. The rule
  now lives in `ConstantValues` (jfr-shell), used by jfr-shell's CSV/JSON output and by every
  format of `jafar-shell` (`Shell.forDisplay`).

### A single-entry map is not always a wrapper

The converse of the above, and the first version of that fix got it wrong. The wrapper is a plain
`java.util.HashMap`, so nothing about the value says "constant": `metadata/jdk.types.Symbol` has
one field, its `fieldsByName` is `{string={name=string, type=java.lang.String, ...}}`, and
collapsing every single-entry cell of a JFR result printed it as the inner map — the field's name
gone, in `--format json` where a script would read it. `ConstantValues.holdsRecordingValues`
decides by where the rows came from instead: `events/` and `constants/` hold recording values and
are unwrapped; `metadata/` and `chunks/` are structure and are not. jfr-shell's table still
collapses every single-entry cell, whatever the root; a table is for reading, not for parsing.

`JfrAnalyses.unwrapValue` handles the `ArrayType`/`ComplexType` case; `Redactor` handles the
single-entry-map case; `TuiTableRenderer.unwrap` handles both for display, and `ConstantValues`
decides when display may use it. If you are reading event values anywhere else, one of those
applies.

### A display list is not a data list

`MetadataSource.loadClass` returns both:

| Key | Contents |
|---|---|
| `fields` | `List<String>` — rendered for display, e.g. `sampledThread:java.lang.Thread @Label(Thread)` |
| `fieldsByName` | `Map<String, Map<String, Object>>` — the structured `name`/`type`/`dimension` |

Reading `fields` and testing each element for a `Map` yields an empty list and no error. The first
run of the field-metadata feature produced labels and descriptions for every type with every field
list silently empty.

### A declared type is not a present type

`JFRSession.scanMetadata` reads the first chunk's metadata and stops, so `getAvailableTypes()`
returns every type the JVM *registered* — including those that emitted nothing. A recording made
with an agent that ships its own sampler lists an empty `jdk.ExecutionSample` beside a vendor type
holding thousands of events.

This is not a bug in the session; it is what metadata means. It becomes a bug when something
downstream treats the list as "what is in this recording" — which is how `as-query` came to offer a model
an empty `jdk.ExecutionSample` and watch it query that instead of `datadog.ExecutionSample`. The
model chose correctly from a list that was wrong.

### A count field is not a count

`JFRSession.eventTypeCounts` is seeded to `0L` from metadata and incremented only while a query's
handlers run. Before any query, every value is zero — and zero is indistinguishable from "no events"
unless you know that. `getEventTypeCounts()` is truthful only after a scan; for real counts use
`JfrPathEvaluator.countAllEventTypes`, which is one pass and is cached per recording by
`EventCountCache`.

Related: the numbers shown by `metadata --events-only` are **class IDs**, not counts.
`jdk.ActiveRecording` displays as `1830` and has one event.

### A missing column is not an error

`Values.get(row, path)` returns `null` when the path names nothing, and `compareValues(null, null)`
is `0`. A sort whose key resolves to nothing therefore succeeds, orders nothing, and returns the
input order — which for `top(n, ...)` means the first n rows presented as the top n.

`groupBy` names its output `key` and the aggregate after the function (`sum`, `count`, …), so

```
events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)
```

had no `value` column to read and returned ten arbitrary monitors. That query is an example in
`LanguageReference`, so it is a line every model was shown. `sortBy` caught the same mistake —
it checks the first row and throws `field 'value' not found. Available: [sum, key]` — which is why
this surfaced there first and stayed invisible in `top`.

Both now read `value` as the aggregate column (`resolveAggregateAlias`), and `groupBy` rejects a key
that matched no event rather than returning nothing. The general point stands for any new operator:
**a path that resolves to null must be distinguishable from a value that is null.** Validate against
the first row, as `applySortBy` does, or count what you consumed, as `aggregateGroupBy` does.

### A type id is not a type

A metadata class id names a type **within one chunk**. Chunks written by different producers — a
JVM's own chunk and an agent's, concatenated — assign ids independently. `tck-test.jfr` has two
chunks and 50 of its 290 ids name different types in each: id 125 is `jdk.CompilerConfiguration` in
chunk 1 and `datadog.ProfilerCounter` in chunk 2; even `long` is `#11` in one and `#219` in the
other.

`UntypedDeserializerCache` is shared by every chunk of a parse and was keyed by that id. Whichever
chunk generated a deserializer for id 125 first owned it, and the other chunk's events were decoded
with the wrong layout — `IOException: Unexpected string constant id: 85` when the misread landed on
a string, a plausible wrong map when it did not. Chunks parse on parallel threads, so *which* type
won varied between runs: `Code generation failed for datadog.ProfilerCounter` appeared in two runs
and in none of the next five. It looked flaky and was deterministic per ordering.

The cache is now keyed by `UntypedCodeGenerator.cacheKey` — name, id and full reachable layout —
and each chunk keeps its own id → deserializer map. The typed path was already safe: it chooses its
cache by `MetadataFingerprint`. Anything else that outlives a chunk must not be keyed by a class id
either.

Nor by `MetadataClass.equals`: it compares id, name, supertype and fields, and `MetadataField.equals`
compares only type id, constant-pool flag, dimension and declaring type — **not field names, not
annotations**. `TemporalNormalizer` cached each class's tick-normalization table in a static map
under that equality, so two chunks declaring the same `test.T {a: long}`, one with
`@Timestamp("TICKS")` on `a` and one without, shared one table: whichever chunk was decoded first
decided for both, and the other got raw ticks as epoch nanos or the reverse, on the generic path and
the generated one (`TemporalAnnotationPerChunkTest`). The table now lives on the `MetadataClass`
instance, which is per chunk. The generated path's structural key spells names out, length-prefixed,
so a field name cannot pass for key syntax.

---

## Before you trust a shape

- Print the value once, in the real path, with a real recording. Not the type — the value.
- If it comes from metadata, ask whether it means *declared* or *present*.
- If it is a count, ask what populated it and when.
- If it is a string from a recording, assume it is wrapped until you have seen otherwise — and if
  it is a single-entry map from metadata, assume it is data.
- If a structure has a display form and a data form, you want the data form, and the display form
  will not tell you that you took the wrong one.
- If it is a column name, check it against a row before you sort or filter by it. Silence means the
  column was absent, not that the data was uninteresting.
- If it is a metadata class id, or a `MetadataClass` compared with `equals`, it is only meaningful
  inside its chunk; `equals` ignores field names and annotations. A failure that comes and
  goes across runs of a multi-chunk parse is per-chunk state shared between chunks until shown
  otherwise.
