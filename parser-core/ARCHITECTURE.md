# Jafar Parser Architecture

This document outlines the architecture of the `parser` subproject: key entry points, core
streaming pipeline, typed and untyped parsing paths, and the supporting metadata/constant pool
infrastructure.

## TL;DR
- Entry: `io.jafar.parser.api.JafarParser` with typed/untyped factories.
- Core loop: `StreamingChunkParser` reads chunks and emits `ChunkParserListener` callbacks.
- Metadata: `MetadataEvent` populates `MutableMetadataLookup`; typed context binds deserializers.
- Constant pools: `CheckpointEvent` records offsets in `MutableConstantPools` for lazy materialization.
- Typed path: generated deserializers map events to interfaces; handlers run synchronously.
- Untyped path: events become `Map<String,Object>` with lazy constant-pool resolution.

## Architecture Diagram

```text
Jafar Parser Architecture (parser subproject)

+--------------------------------------------------------------------------------------------+
|                                        Client Code                                         |
|  - Registers handlers, calls `run()`                                                       |
+--------------------------------------------+-----------------------------------------------+
                                             |
                                             v
+--------------------------------------------------------------------------------------------+
|                                  io.jafar.parser.api                                       |
|  +------------------------+                 +------------------------------+               |
|  |      JafarParser       | <factory>       |     ParsingContext           |               |
|  | - newTypedParser(...)  |---------------> | - newTypedParser(path)       |               |
|  | - newUntypedParser(...)|                 | - newUntypedParser(path)     |               |
|  +------------------------+                 | - uptime()                   |               |
|                                             |   (impl: ParsingContextImpl) |               |
|                                             +------------------------------+               |
+--------------------------------------------------------------------------------------------+
                                             | uses factories
                                             v
+--------------------------------------------------------------------------------------------+
|                                      Context Factories                                     |
|  io.jafar.parser.impl                                  io.jafar.parser.impl                |
|  +-----------------------------+                       +----------------------------+      |
|  | TypedParserContextFactory   |                       | UntypedParserContextFactory|      |
|  | - newContext(parent, idx)   |                       | - newContext(parent, idx)  |      |
|  +-------------+---------------+                       +--------------+-------------+      |
|                | creates per-chunk                                      | per-chunk        |
|                v                                                       v                   |
|   +---------------------------+                           +--------------------------+     |
|   |   TypedParserContext      |                           |   UntypedParserContext   |     |
|   | - metadataLookup (mutable)|                           | - minimal, no-op hooks   |     |
|   | - constantPools (mutable) |                           +--------------------------+     |
|   | - typeFilter              |                                                            |                                                                       
|   | - deserializerCache       |                                                            |           
|   +---------------------------+                                                            |           
+--------------------------------------------------------------------------------------------+
                                             |
                                             v
+------------------------------------------------------------------------------------------------+
|                               Core Streaming + IO Pipeline                                     |
|  io.jafar.parser.internal_api                                                                  |
|  +--------------------------+     emits      +------------------------+                        |
|  |   StreamingChunkParser   |--------------> |   ChunkParserListener  |<----+                  |
|  | - parse(Path, Listener)  |                | onRecordingStart/End   |     | (typed/untyped   |
|  | - per-chunk tasks (pool) |                | onChunkStart/End       |     |  implementations)|
|  +--------------------------+                | onMetadata/Checkpoint  |     |                  |
|                 |                            | onEvent(typeId, ...)   |     |                  |
|     opens/slices|RecordingStream             +------------------------+     |                  |
|                 v                                                           |                  |
|  +--------------------------+     reads      +------------------------+     |                  |
|  |     RecordingStream      |--------------> |      ChunkHeader       |     |                  |
|  | - mapped file/slices     |                +------------------------+     |                  |
|  | - read primitives/varint |     reads      +------------------------+     |                  |
|  +--------------------------+--------------> |    MetadataEvent       |-----+---+              |
|                                              +------------------------+         |              |
|                                              | populates              |         |              |
|                                              v                        v         v              |
|                                    +------------------+        +----------------------+        |
|                                    | MutableMetadata  |        |   CheckpointEvent    |        |
|                                    |     Lookup       |        +----------------------+        |
|                                    +------------------+        | reads Constant Pools |        |
|                                                                v                      |        |
|                                                      +--------------------------+     |        |
|                                                      |   MutableConstantPools   |<----+        |
|                                                      |  (by typeId -> pool)     |              |
|                                                      +-----------+--------------+              |
|                                                                  |                             |
|                                                                  v                             |
|                                                      +--------------------------+              |
|                                                      |  MutableConstantPool     |              |
|                                                      | - id -> offset mapping   |              |
|                                                      | - lazy entry deserialize |              |
|                                                      +--------------------------+              |
+------------------------------------------------------------------------------------------------+   
                                                                                                 
Typed Path (strongly-typed events)
+-------------------------------------------------------------------------------------------------------+
| io.jafar.parser.impl                                                                                  |
| +----------------------------------+      resolves types        +-------------------------+           |
| |      TypedJafarParserImpl        |--------------------------->|     MetadataClass       |           |
| | - handle(Class<T>, JFRHandler)   |                            | - bind/get Deserializer |           |
| | - run(): parse(...)              |                            | - read()/skip()         |           |
| |   - set TypeFilter               |                            +-----------+-------------+           |
| |   - build typeId->Class map      |                                        | uses                    |
| |   - onEvent:                     |                                        v                         |
| |     clz = metadata.getClass(id)  |                          +---------------------------+           |
| |     obj = clz.read(stream)       |                          |  Deserializer (Generated) |           |
| |     for each handler -> handle() |                          +---------------------------+           |
| +-------------------+--------------+                                        ^                         |
|                     | Control                                               | generated by            |
|  +------------------v--------------+                          +-------------------------------+       |
|  |          ControlImpl            |                          |  CodeGenerator (ASM emitter)  |       |
|  | - abort, chunk info, stream     |                          +-------------------+-----------+       |
|  +---------------------------------+                                        | uses                    |
|                                                                             v                         |
|                                                               +--------------------------------+      |
|                                                               | ClassDefiners (strategy select)|      |
|                                                               | - hidden | lookup | unsafe |   |      |
|                                                               |   loader (runtime define)      |      |
|                                                               +--------------------------------+      |
+-------------------------------------------------------------------------------------------------------+

Untyped Path (maps with lazy constant-pool refs)
+-------------------------------------------------------------------------------------------------+
| io.jafar.parser.impl                                                                            |
| +-------------------------------+      builds maps via      +------------------------------+    |
| |        UntypedJafarParserImpl |-------------------------->|         EventStream          |    |
| | - handle(EventHandler)        |                           | (ChunkParserListener impl)   |    |
| | - run(): parse(...)           |                           | - onEvent:                   |    |
| +-------------------------------+                           |   GenericValueReader         |    |
|                                                              |   + MapValueBuilder         |    |
|                                                              |     - primitives -> Map     |    |
|                                                              |     - complex -> nested Map |    |
|                                                              |     - cp index ->           |    |
|                                                              |       ConstantPoolAccessor  |    |
|                                                              +-----------------------------+    |
+-------------------------------------------------------------------------------------------------+
```

## Key Packages
- `api`: Entry points and public contracts (`JafarParser`, `TypedJafarParser`, `UntypedJafarParser`,
  `ParsingContext`, `ParserContext`, `JFRHandler`, `Control`, `Values`, annotations).
- `impl`: Concrete parsers, contexts, event mapping utilities (`TypedJafarParserImpl`,
  `UntypedJafarParserImpl`, `TypedParserContext`, `EventStream`, `MapValueBuilder`, `ControlImpl`).
- `internal_api`: Streaming engine, metadata model, constant pools, deserialization/codegen,
  runtime class definition (`StreamingChunkParser`, `RecordingStream`, `Metadata*`, `Deserializer`,
  `CodeGenerator`, `ClassDefiners`, `MutableConstantPools`).
- `utils`: Low-level buffers and helpers (`CustomByteBuffer`, `SplicedMappedByteBuffer`, `BytePacking`).

## Flow Notes
- `StreamingChunkParser` drives: ChunkHeader -> MetadataEvent -> CheckpointEvent -> Events.
- `MetadataEvent` populates `MutableMetadataLookup`; `TypedParserContext.onMetadataReady()` binds
  deserializers for discovered classes.
- `CheckpointEvent` populates `MutableConstantPools` with offsets; values materialize lazily on
  access via `MutableConstantPool.get(...)` or `ConstantPoolAccessor` in untyped maps.
- Typed mode: events are deserialized to generated POJOs and dispatched to user `JFRHandler`s
  synchronously; handlers can request abort via `Control`.
- Untyped mode: events are `Map<String,Object>`; arrays and complex values are represented with
  `ArrayHolder`/nested maps, constant pool refs remain lazy until accessed.

### Typed path internals (legend)
- `MetadataClass.bindDeserializer()` consults `DeserializerCache` and invokes `CodeGenerator` to
  produce a `Deserializer.Generated` when not cached.
- `CodeGenerator` emits bytecode for field skipping and deserialization and uses `ClassDefiners`
  to define the class at runtime.
  - Strategy selection: `hidden` (JDK 15+), `lookup` (JDK 9–14), `unsafe` (JDK 8), `loader` (fallback).
- `MetadataClass.read(stream)` uses the bound `Deserializer` to materialize typed objects during
  event processing in `TypedJafarParserImpl.onEvent(...)`.
