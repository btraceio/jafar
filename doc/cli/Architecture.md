# JFR Shell Architecture

This document describes the internal architecture of JFR Shell, an interactive CLI for analyzing Java Flight Recorder files.

## System Overview

JFR Shell is organized into four modules with distinct responsibilities:

```mermaid
graph TB
    subgraph "JFR Shell System"
        CLI[jfr-shell<br/>Core CLI & Query Engine]
        JAFAR[jfr-shell-jafar<br/>Jafar Backend Plugin]
        JDK[jfr-shell-jdk<br/>JDK API Backend Plugin]
        TCK[jfr-shell-tck<br/>Backend Test Kit]
    end

    subgraph "External"
        PARSER[parser<br/>Jafar Parser Library]
        JDKAPI[jdk.jfr.consumer<br/>JDK Flight Recorder API]
        JFR[(JFR Files)]
    end

    CLI -->|SPI| JAFAR
    CLI -->|SPI| JDK
    TCK -->|validates| JAFAR
    TCK -->|validates| JDK
    JAFAR --> PARSER
    JDK --> JDKAPI
    JAFAR --> JFR
    JDK --> JFR
```

| Module | Purpose |
|--------|---------|
| `jfr-shell` | Core shell, command system, JfrPath query engine, plugin framework |
| `jfr-shell-jafar` | Backend using Jafar parser (full capabilities, priority 100) |
| `jfr-shell-jdk` | Backend using JDK API (limited capabilities, priority 50) |
| `jfr-shell-tck` | Technology Compatibility Kit for validating backends |

---

## Core Shell Architecture

### Component Overview

```mermaid
graph TB
    subgraph "Entry Points"
        MAIN[Main.java<br/>CLI Entry]
        SHELL[Shell.java<br/>REPL Loop]
    end

    subgraph "Command Layer"
        CD[CommandDispatcher<br/>Command Routing]
        CS[ConditionalState<br/>if/else Tracking]
        SR[ScriptRunner<br/>Script Execution]
    end

    subgraph "Session Layer"
        SM[SessionManager<br/>Multi-Session Coordinator]
        JS[JFRSession<br/>Single Recording]
        VS[VariableStore<br/>Variables per Session]
    end

    subgraph "Query Engine"
        PARSER[JfrPathParser<br/>Query Parsing]
        EVAL[JfrPathEvaluator<br/>Query Execution]
        RENDER[Renderers<br/>Table/CSV/JSON]
    end

    subgraph "Backend Layer"
        BR[BackendRegistry<br/>Backend Discovery]
        PM[PluginManager<br/>Plugin Loading]
    end

    MAIN --> SHELL
    MAIN --> PM
    SHELL --> CD
    CD --> CS
    CD --> SR
    CD --> SM
    SM --> JS
    SM --> VS
    CD --> PARSER
    PARSER --> EVAL
    EVAL --> RENDER
    EVAL --> BR
    JS --> BR
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `Main` | CLI entry point (picocli), backend selection, plugin initialization |
| `Shell` | Interactive REPL using JLine3, history, command recording |
| `CommandDispatcher` | Routes commands to handlers, manages global state |
| `SessionManager` | Manages multiple open JFR files with aliases |
| `JFRSession` | Wraps TypedJafarParser, provides metadata and event access |
| `VariableStore` | Stores scalar, lazy query, and map variables |
| `ConditionalState` | Stack-based if/elif/else/endif tracking |

### Command Execution Flow

```mermaid
sequenceDiagram
    participant U as User
    participant S as Shell
    participant CD as CommandDispatcher
    participant CS as ConditionalState
    participant SM as SessionManager
    participant P as JfrPathParser
    participant E as JfrPathEvaluator
    participant B as Backend

    U->>S: Type command
    S->>CD: dispatch(line)
    CD->>CS: Check if active branch

    alt Inactive branch (else block)
        CS-->>CD: Skip execution
    else Active branch
        CD->>CD: Parse command & args
        CD->>CD: Substitute variables

        alt show command
            CD->>P: parseQuery(expression)
            P-->>CD: JfrPath AST
            CD->>SM: Get current session
            SM-->>CD: JFRSession
            CD->>E: evaluate(session, query)
            E->>B: Stream events
            B-->>E: Event data
            E-->>CD: Results
            CD->>CD: Render output
        else open command
            CD->>SM: Create session
            SM->>B: Create JFRSession
        else other commands
            CD->>CD: Handle directly
        end
    end

    CD-->>S: Output
    S-->>U: Display results
```

---

## Backend Plugin System

### SPI Interface Hierarchy

```mermaid
classDiagram
    class JfrBackend {
        <<interface>>
        +getId() String
        +getName() String
        +getVersion() String
        +getPriority() int
        +getCapabilities() Set~BackendCapability~
        +createContext() BackendContext
        +createEventSource(BackendContext) EventSource
        +createMetadataSource() MetadataSource
        +createChunkSource() ChunkSource
        +createConstantPoolSource() ConstantPoolSource
    }

    class BackendContext {
        <<interface>>
        +uptime() long
        +close() void
    }

    class EventSource {
        <<interface>>
        +streamEvents(Path, Consumer~Event~) void
    }

    class MetadataSource {
        <<interface>>
        +loadAllClasses(Path) List~Map~
        +loadClass(Path, String) Map
        +loadField(Path, String, String) Map
    }

    class ChunkSource {
        <<interface>>
        +loadAllChunks(Path) List~Map~
        +loadChunk(Path, int) Map
        +getChunkSummary(Path) Map
    }

    class ConstantPoolSource {
        <<interface>>
        +loadSummary(Path) List~Map~
        +loadEntries(Path, String) List~Map~
        +getAvailableTypes(Path) Set~String~
    }

    class BackendCapability {
        <<enumeration>>
        EVENT_STREAMING
        METADATA_CLASSES
        CHUNK_INFO
        CONSTANT_POOLS
        STREAMING_PARSE
        TYPED_HANDLERS
        UNTYPED_HANDLERS
        CONTEXT_REUSE
    }

    JfrBackend --> BackendContext
    JfrBackend --> EventSource
    JfrBackend --> MetadataSource
    JfrBackend --> ChunkSource
    JfrBackend --> ConstantPoolSource
    JfrBackend --> BackendCapability
```

### Capability Matrix

| Capability | Description | Jafar | JDK |
|------------|-------------|:-----:|:---:|
| `EVENT_STREAMING` | Stream events from recordings | ✓ | ✓ |
| `METADATA_CLASSES` | Access event types and fields | ✓ | ✓ |
| `CHUNK_INFO` | Access chunk headers and offsets | ✓ | ✗ |
| `CONSTANT_POOLS` | Direct constant pool access | ✓ | ✗ |
| `STREAMING_PARSE` | Memory-efficient large file parsing | ✓ | ✓ |
| `TYPED_HANDLERS` | Compile-time typed interfaces | ✓ | ✗ |
| `UNTYPED_HANDLERS` | Map-based event access | ✓ | ✓ |
| `CONTEXT_REUSE` | Share context across sessions | ✓ | ✗ |

### Backend Discovery and Selection

```mermaid
sequenceDiagram
    participant M as Main
    participant PM as PluginManager
    participant BR as BackendRegistry
    participant SL as ServiceLoader
    participant B1 as JafarBackend
    participant B2 as JdkBackend

    M->>PM: initialize()
    PM->>PM: Load plugin JARs from ~/.jfr-shell/plugins/
    PM->>PM: Create URLClassLoader

    Note over M,BR: First backend access triggers discovery

    M->>BR: getInstance().getCurrent()
    BR->>BR: Check if already selected

    alt Not yet selected
        BR->>SL: load(JfrBackend.class, pluginClassLoader)
        SL->>B1: Instantiate
        SL->>B2: Instantiate
        SL-->>BR: [JafarBackend, JdkBackend]

        BR->>BR: Check JFRSHELL_BACKEND env var
        BR->>BR: Check jfr.shell.backend property
        BR->>BR: Select highest priority
        Note over BR: Jafar (100) > JDK (50)
    end

    BR-->>M: JafarBackend
```

### Plugin System Components

```mermaid
graph TB
    subgraph "Plugin Management"
        PM[PluginManager<br/>Singleton Entry Point]
        PR[PluginRegistry<br/>Plugin Discovery]
        PI[PluginInstaller<br/>Download & Verify]
        PS[PluginStorageManager<br/>~/.jfr-shell/plugins/]
        MR[MavenResolver<br/>Artifact Resolution]
        UC[UpdateChecker<br/>Version Comparison]
        PCL[PluginClassLoader<br/>URLClassLoader]
    end

    subgraph "Discovery Sources"
        LOCAL[(~/.m2/repository/<br/>io/btrace/jfr-shell-*)]
        REMOTE[(GitHub Registry<br/>jfr-shell-plugins.json)]
    end

    PM --> PR
    PM --> PI
    PM --> PS
    PM --> PCL
    PI --> MR
    PI --> UC
    PR --> LOCAL
    PR --> REMOTE
```

**Offline Installation**: For air-gapped environments, plugins can be installed from local JAR files:
```bash
java -jar jfr-shell.jar --install-plugin /path/to/plugin.jar
```
See [Backend Plugin Guide](Backends.md#offlineairgapped-installation) for details.

---

## JfrPath Query Engine

### Query Grammar

```
Query     := Root ['/' EventType] [Filters] [Pipeline]
Root      := 'events' | 'metadata' | 'chunks' | 'cp'
EventType := Identifier | '(' Identifier ('|' Identifier)* ')'
Filters   := '[' Predicate ']' ('[' Predicate ']')*
Pipeline  := '|' Operator ('|' Operator)*

Predicate := FieldPredicate | ExprPredicate
FieldPredicate := Path Op Literal [MatchMode]
ExprPredicate  := BoolExpr

BoolExpr  := AndExpr ('or' AndExpr)*
AndExpr   := NotExpr ('and' NotExpr)*
NotExpr   := 'not'? PrimaryBool
PrimaryBool := '(' BoolExpr ')' | FuncExpr | CompExpr
CompExpr  := ValueExpr Op Literal
```

### Query Processing Flow

```mermaid
flowchart TB
    INPUT[/"show events/jdk.FileRead[bytes>1000] | top(10, by=bytes)"/]

    subgraph "Parsing Phase"
        TOK[Tokenize]
        PARSE[Parse Query]
        AST[Build AST]
    end

    subgraph "AST Structure"
        ROOT[Root: EVENTS]
        TYPE[EventType: jdk.FileRead]
        PRED[Predicate: bytes > 1000]
        PIPE[Pipeline: top]
    end

    subgraph "Evaluation Phase"
        STREAM[Stream Events]
        FILTER[Apply Predicates]
        AGG[Execute Pipeline]
        FORMAT[Format Results]
    end

    OUTPUT[/Table Output/]

    INPUT --> TOK --> PARSE --> AST
    AST --> ROOT --> TYPE --> PRED --> PIPE
    PIPE --> STREAM --> FILTER --> AGG --> FORMAT --> OUTPUT
```

### AST Node Types

```mermaid
classDiagram
    class JfrPath {
        +RootType root
        +List~String~ eventTypes
        +List~Segment~ segments
        +List~Predicate~ predicates
        +List~PipelineOp~ pipeline
    }

    class RootType {
        <<enumeration>>
        EVENTS
        METADATA
        CHUNKS
        CP
    }

    class Predicate {
        <<interface>>
    }

    class FieldPredicate {
        +List~String~ path
        +String op
        +Object value
        +MatchMode matchMode
    }

    class ExprPredicate {
        +BoolExpr expression
    }

    class BoolExpr {
        <<sealed>>
    }

    class LogicalExpr {
        +BoolExpr left
        +BoolExpr right
        +Lop op
    }

    class Lop {
        <<enumeration>>
        AND
        OR
    }

    class NotExpr {
        +BoolExpr inner
    }

    class CompExpr {
        +ValueExpr left
        +String op
        +Object right
    }

    class FuncBoolExpr {
        +String name
        +List~Expr~ args
    }

    JfrPath --> RootType
    JfrPath --> Predicate
    Predicate <|-- FieldPredicate
    Predicate <|-- ExprPredicate
    ExprPredicate --> BoolExpr
    BoolExpr <|-- LogicalExpr
    BoolExpr <|-- NotExpr
    BoolExpr <|-- CompExpr
    BoolExpr <|-- FuncBoolExpr
    LogicalExpr --> Lop
```

### Pipeline Operators

| Category | Operators |
|----------|-----------|
| **Aggregation** | `count()`, `sum(path)`, `stats(path)`, `quantiles(...)`, `sketch(path)` |
| **Grouping** | `groupBy(key, agg=..., value=..., sortBy=..., asc=...)` |
| **Sorting** | `sortBy(field, asc=...)`, `top(n, by=..., asc=...)`, `asc`, `desc` |
| **Selection** | `select(field1, field2, expr as alias)` |
| **Decoration** | `decorateByTime(type, fields=...)`, `decorateByKey(type, key=..., fields=...)` |
| **Transform** | `tomap(keyField, valueField)`, `timerange(path, duration=...)` |

### Event Decoration

```mermaid
sequenceDiagram
    participant Q as Query
    participant E as Evaluator
    participant ES as EventSource
    participant DM as DecoratedEventMap

    Note over Q: show events/jdk.ExecutionSample<br/>| decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)

    Q->>E: evaluate(query)

    rect rgb(240, 248, 255)
        Note over E,ES: Pass 1: Collect decorators
        E->>ES: Stream jdk.JavaMonitorWait
        ES-->>E: Decorator events with time ranges
        E->>E: Index by thread + time
    end

    rect rgb(255, 248, 240)
        Note over E,ES: Pass 2: Stream primary events
        E->>ES: Stream jdk.ExecutionSample
        loop For each sample
            ES-->>E: Sample event
            E->>E: Find overlapping decorators
            E->>DM: Wrap with decorator
            DM-->>E: DecoratedEventMap
        end
    end

    E-->>Q: Results with $decorator.* fields
```

---

## Complete Data Flow

```mermaid
flowchart TB
    subgraph "User Interface"
        USER((User))
        TERM[Terminal]
    end

    subgraph "Shell Layer"
        MAIN[Main.java]
        SHELL[Shell.java]
        JLINE[JLine3 Reader]
    end

    subgraph "Command Layer"
        CD[CommandDispatcher]
        COND[ConditionalState]
        VARS[VariableStore]
    end

    subgraph "Session Layer"
        SM[SessionManager]
        SESS[JFRSession]
    end

    subgraph "Query Layer"
        PARSER[JfrPathParser]
        EVAL[JfrPathEvaluator]
    end

    subgraph "Backend Layer"
        REG[BackendRegistry]
        BACKEND[JfrBackend]
        ES[EventSource]
        MS[MetadataSource]
    end

    subgraph "Output Layer"
        TABLE[TableRenderer]
        CSV[CsvRenderer]
        JSON[JSON Formatter]
    end

    subgraph "Storage"
        JFR[(JFR File)]
        PLUGINS[(Plugin JARs)]
    end

    USER -->|input| TERM
    TERM --> JLINE
    JLINE --> SHELL
    MAIN --> SHELL
    SHELL --> CD

    CD <--> COND
    CD <--> VARS
    CD --> SM
    SM --> SESS

    CD --> PARSER
    PARSER --> EVAL
    EVAL --> REG
    REG --> BACKEND
    BACKEND --> ES
    BACKEND --> MS
    ES --> JFR
    MS --> JFR

    EVAL --> TABLE
    EVAL --> CSV
    EVAL --> JSON

    TABLE --> TERM
    CSV --> TERM
    JSON --> TERM

    MAIN -.->|initialize| PLUGINS
    REG -.->|ServiceLoader| PLUGINS
```

---

## Design Patterns

| Pattern | Usage |
|---------|-------|
| **Singleton** | `BackendRegistry`, `PluginManager` - single instances managing global state |
| **Factory** | `JfrBackend.createEventSource()` - backends create their own source implementations |
| **Strategy** | Output formats (Table/CSV/JSON) - interchangeable result formatting |
| **Adapter** | Providers (MetadataProvider, etc.) - adapt shell to backend APIs |
| **Flyweight** | `LazyQueryValue` - shared query results with weak references |
| **Command** | `CommandDispatcher` - encapsulates command execution |
| **Sealed Classes** | `Value`, `BoolExpr`, `ValueExpr` - exhaustive type hierarchies |

---

## Package Structure

```
jfr-shell/src/main/java/io/jafar/shell/
├── Main.java                    # CLI entry point
├── Shell.java                   # Interactive REPL
├── cli/
│   └── CommandDispatcher.java   # Command routing
├── backend/
│   ├── JfrBackend.java          # Backend SPI
│   ├── EventSource.java         # Event streaming
│   ├── MetadataSource.java      # Type metadata
│   ├── ChunkSource.java         # Chunk info
│   ├── ConstantPoolSource.java  # Constant pools
│   ├── BackendContext.java      # Resource sharing
│   ├── BackendCapability.java   # Capability enum
│   └── BackendRegistry.java     # Discovery & selection
├── plugin/
│   ├── PluginManager.java       # Plugin management
│   ├── PluginRegistry.java      # Plugin discovery
│   ├── PluginInstaller.java     # Installation
│   └── MavenResolver.java       # Maven resolution
├── jfrpath/
│   ├── JfrPath.java             # Query AST
│   ├── JfrPathParser.java       # Query parsing
│   └── JfrPathEvaluator.java    # Query execution
├── providers/
│   ├── MetadataProvider.java    # Metadata access
│   ├── ChunkProvider.java       # Chunk access
│   └── ConstantPoolProvider.java # CP access
├── core/
│   ├── SessionManager.java      # Multi-session coordinator
│   └── VariableStore.java       # Variable storage
└── JFRSession.java              # Single recording session
```

---

## See Also

- [JFR Shell Usage Guide](Usage.md)
- [JfrPath Query Language](JFRPath.md)
- [Backend Plugin Guide](Backends.md)
- [Backend Quickstart Tutorial](BackendQuickstart.md)
