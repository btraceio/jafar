---
id: ev-jfr-profile
type: evidence
created: 2026-10-03
updated: 2026-10-03
---

# JFR method profile + GC view of the focused class run

Test JVM temporarily recorded with `-XX:StartFlightRecording=filename=/tmp/diag.jfr,settings=profile`
(run green, 18s-class). `jfr print --events jdk.ExecutionSample --json` → 4417 runnability samples.

Deepest-frame (self) attribution — top 12:

| samples | frame |
|---|---|
| 695 | io/jafar/parser/internal_api/metadata/MetadataField.hasConstantPool |
| 432 | io/jafar/parser/internal_api/RecordingStreamReader$BufferBackedRecordingStreamReader.readVarint |
| 278 | io/jafar/parser/ParsingUtils.parseLongSWAR |
| 274 | java/util/Objects.equals |
| 218 | io/jafar/parser/internal_api/RecordingStreamReader$BufferBackedRecordingStreamReader.readVarintSeq |
| 205 | java/util/HashMap.putVal |
| 168 | io/jafar/parser/internal_api/GenericValueReader.readValue |
| 166 | io/jafar/parser/internal_api/metadata/AbstractMetadataElement.getName |
| 140 | io/jafar/parser/internal_api/metadata/AbstractMetadataElement.getId |
| 114 | java/util/concurrent/ConcurrentHashMap.get |
| 114 | java/util/concurrent/ConcurrentHashMap.computeIfAbsent |
| 95 | io/jafar/parser/impl/MapValueBuilder.onLongValue |

Also visible: `$jacocoInit` probes inside parser metadata classes (52 samples on
AbstractMetadataElement alone) — agent probes sit inside the hot loop.

GC view: 6 collections over the 45s window, longest pause 26.3ms — GC pressure excluded.
Full stack (jstack during jfr_use): handler runs on Reactor `boundedElastic-1` parked in
`FutureTask.get` inside `StreamingChunkParser.parse:325`; chunk work on parser-pool threads
(unnamed `Thread-N` pairs — the custom thread factory in StreamingChunkParser does not name
threads; pool size = availableProcessors()-2 per parser instance).