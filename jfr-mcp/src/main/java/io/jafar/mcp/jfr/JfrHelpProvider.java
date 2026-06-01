package io.jafar.mcp.jfr;

/** JfrPath and jfr_* MCP tool help text. */
public final class JfrHelpProvider {

  public String getOverviewHelp() {
    return """
        # JfrPath Query Language

        JfrPath is a path-based query language for navigating and querying JFR files.

        ## Basic Syntax
        ```
        events/<eventType>[filters] | pipeline_operator
        ```

        ## Query Roots
        - `events/<type>` - Access event data (e.g., events/jdk.ExecutionSample)
        - `metadata/<type>` - Access type metadata
        - `chunks` - Access chunk information
        - `constants/<type>` - Access constant pool entries

        ## Field Navigation
        - Simple: `events/jdk.FileRead/path`
        - Nested: `events/jdk.ExecutionSample/stackTrace/frames`
        - Array index: `events/jdk.ExecutionSample/stackTrace/frames/0`

        ## Quick Examples
        ```
        events/jdk.ExecutionSample | count()
        events/jdk.FileRead[bytes>1000] | top(10)
        events/jdk.GCPhasePause | stats(duration)
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)
        ```

        ## Help Topics
        Call jfr_help with topic parameter for detailed documentation:
        - filters: Filter syntax and operators
        - pipeline: Aggregation operators (count, groupBy, top, stats, etc.)
        - functions: Built-in functions for filters and select
        - examples: Common query patterns
        - event_types: Common JDK event types
        - tools: When to use each jfr_* tool (flamegraph vs stackprofile vs hotmethods, etc.)
        """;
  }

  public String getFiltersHelp() {
    return """
        # JfrPath Filters

        Filters use square brackets `[...]` to constrain results.

        ## Comparison Operators
        - `=` : Equal
        - `!=` : Not equal
        - `>` : Greater than
        - `>=` : Greater than or equal
        - `<` : Less than
        - `<=` : Less than or equal
        - `~` : Regex match

        ## Examples
        ```
        events/jdk.FileRead[bytes>1000]
        events/jdk.ExecutionSample[eventThread/javaName="main"]
        events/jdk.FileRead[path~"/tmp/.*"]
        events/jdk.GCPhasePause[duration>10000000]
        ```

        ## Boolean Expressions
        ```
        events/jdk.FileRead[bytes>1000 and path~"/tmp/.*"]
        events/jdk.ExecutionSample[eventThread/javaName="main" or eventThread/javaName="worker"]
        events/jdk.FileRead[not (bytes<100)]
        ```

        ## Filter Functions
        - `contains(field, "substr")` - String contains
        - `startsWith(field, "prefix")` - String starts with
        - `endsWith(field, "suffix")` - String ends with
        - `matches(field, "regex")` - Regex match
        - `exists(field)` - Field is not null
        - `empty(field)` - String/list is empty
        - `between(field, min, max)` - Numeric range
        - `len(field)` - Length (use in comparisons)

        ## Duration Values
        Durations can be specified with units:
        - `10ms` - 10 milliseconds
        - `1s` - 1 second
        - `500us` - 500 microseconds
        - `100ns` - 100 nanoseconds

        Example: `events/jdk.GCPhasePause[duration>10ms]`

        ## List Matching
        - `any:` (default) - Any element matches
        - `all:` - All elements match
        - `none:` - No elements match

        Example: `events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/type/name/string, ".*MyClass.*")]]`
        """;
  }

  public String getPipelineHelp() {
    return """
        # JfrPath Pipeline Operators

        Pipeline operators transform or aggregate results. Append with `|`:

        ## count()
        Count matching events.
        ```
        events/jdk.ExecutionSample | count()
        → { "count": 12345 }
        ```

        ## top(n[, by=field, asc=false])
        Return top N results sorted by field.
        ```
        events/jdk.FileRead | top(10, by=bytes)
        events/jdk.GCPhasePause | top(5, by=duration)
        events/jdk.FileRead | top(10, by=bytes, asc=true)  # smallest first
        ```

        ## groupBy(field[, agg=count|sum|avg|min|max, value=path, sortBy=key|value, asc=false])
        Group by field and aggregate.
        ```
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)
        events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)
        events/jdk.ExecutionSample | groupBy(eventThread/javaName, sortBy=value)
        ```

        ## stats([field])
        Compute min, max, avg, stddev, count.
        ```
        events/jdk.FileRead | stats(bytes)
        events/jdk.GCPhasePause | stats(duration)
        → { "min": 100, "max": 50000, "avg": 5000, "stddev": 2500, "count": 200 }
        ```

        ## sum([field])
        Sum numeric values.
        ```
        events/jdk.FileRead | sum(bytes)
        → { "sum": 1234567, "count": 100 }
        ```

        ## quantiles(q1, q2, ...[, path=field])
        Compute percentiles (0.0 to 1.0).
        ```
        events/jdk.GCPhasePause | quantiles(0.5, 0.9, 0.99, path=duration)
        → { "p50": 1000, "p90": 5000, "p99": 20000 }
        ```

        ## select(field1, field2, expr as alias, ...)
        Project specific fields or compute expressions.
        ```
        events/jdk.FileRead | select(path, bytes)
        events/jdk.FileRead | select(path, bytes / 1024 as kb)
        events/jdk.ExecutionSample | select(eventThread/javaName as thread, startTime)
        ```

        ## sortBy(field[, asc=false])
        Sort results by field.
        ```
        events/jdk.FileRead | select(path, bytes) | sortBy(bytes)
        ```

        ## decorateByTime(eventType, fields=f1,f2[, threadPath=..., decoratorThreadPath=...])
        Join with time-overlapping events on same thread.
        ```
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
        ```

        ## decorateByKey(eventType, key=path, decoratorKey=path, fields=f1,f2)
        Join using correlation keys.
        ```
        events/jdk.ExecutionSample | decorateByKey(RequestStart, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)
        ```
        """;
  }

  public String getFunctionsHelp() {
    return """
        # JfrPath Functions

        ## Filter Functions

        ### String Functions
        - `contains(field, "substr")` - Check if contains substring
        - `startsWith(field, "prefix")` - Check if starts with prefix
        - `endsWith(field, "suffix")` - Check if ends with suffix
        - `matches(field, "regex")` - Check if matches regex
        - `matches(field, "regex", "i")` - Case-insensitive regex

        ### Existence Functions
        - `exists(field)` - Field is present and not null
        - `empty(field)` - String or list is empty

        ### Numeric Functions
        - `between(field, min, max)` - Value in range (inclusive)
        - `len(field)` - Length of string or array

        ## Select Expression Functions

        ### Conditional
        - `if(condition, trueValue, falseValue)` - Conditional expression

        ### String Functions
        - `upper(string)` - Convert to uppercase
        - `lower(string)` - Convert to lowercase
        - `substring(string, start[, length])` - Extract substring
        - `length(string)` - String length
        - `coalesce(v1, v2, ...)` - First non-null value

        ### Arithmetic
        - `+`, `-`, `*`, `/` - Basic arithmetic
        - Parentheses for grouping: `(bytes * count) / 1024`

        ### String Templates
        Use `${...}` for interpolation:
        ```
        events/jdk.FileRead | select("${path}: ${bytes / 1024} KB" as info)
        ```

        ## Transform Operators (after |)
        - `| len()` - String/list length
        - `| uppercase()` - Convert to uppercase
        - `| lowercase()` - Convert to lowercase
        - `| trim()` - Trim whitespace
        - `| abs()` - Absolute value
        - `| round()` - Round to integer
        - `| floor()` - Round down
        - `| ceil()` - Round up
        """;
  }

  public String getExamplesHelp() {
    return """
        # JfrPath Query Examples

        ## CPU/Execution Analysis
        ```
        # Count execution samples
        events/jdk.ExecutionSample | count()

        # Samples by thread
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)

        # Top threads by sample count
        events/jdk.ExecutionSample | groupBy(eventThread/javaName, sortBy=value) | top(10)

        # Samples for specific thread
        events/jdk.ExecutionSample[eventThread/javaName="main"] | count()
        ```

        ## GC Analysis
        ```
        # GC pause statistics
        events/jdk.GCPhasePause | stats(duration)

        # Longest GC pauses
        events/jdk.GCPhasePause | top(10, by=duration)

        # GC pauses over 10ms
        events/jdk.GCPhasePause[duration>10ms] | count()

        # Heap usage after GC
        events/jdk.GCHeapSummary[when/when="After GC"] | select(heapUsed, heapSpace/committedSize)
        ```

        ## I/O Analysis
        ```
        # File read statistics
        events/jdk.FileRead | stats(bytes)

        # Largest file reads
        events/jdk.FileRead | top(10, by=bytes)

        # Reads by file path
        events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)

        # Slow file operations (>10ms)
        events/jdk.FileRead[duration>10ms] | top(10)

        # Socket reads by host
        events/jdk.SocketRead | groupBy(host)
        ```

        ## Lock Contention
        ```
        # Monitor enter events
        events/jdk.JavaMonitorEnter | count()

        # Longest lock waits
        events/jdk.JavaMonitorEnter | top(10, by=duration)

        # Contention by monitor class
        events/jdk.JavaMonitorEnter | groupBy(monitorClass)

        # Lock waits over 1ms
        events/jdk.JavaMonitorEnter[duration>1ms] | count()
        ```

        ## Memory Analysis
        ```
        # Allocation samples
        events/jdk.ObjectAllocationSample | count()

        # Allocations by class
        events/jdk.ObjectAllocationSample | groupBy(objectClass/name)

        # Large allocations
        events/jdk.ObjectAllocationOutsideTLAB | top(10, by=allocationSize)
        ```

        ## Thread Analysis
        ```
        # Thread starts
        events/jdk.ThreadStart | count()

        # Thread CPU load
        events/jdk.ThreadCPULoad | top(10, by=user)

        # Thread sleep events
        events/jdk.ThreadSleep | stats(time)
        ```

        ## Correlation/Decoration
        ```
        # Samples during lock waits
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)

        # Group samples by lock class
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass) | groupBy($decorator.monitorClass)
        ```
        """;
  }

  public String getEventTypesHelp() {
    return """
        # Common JDK Event Types

        ## CPU/Execution
        - `jdk.ExecutionSample` - CPU profiling samples (fields: stackTrace, state, eventThread)
        - `jdk.NativeMethodSample` - Native method samples
        - `jdk.CPULoad` - CPU load (fields: jvmUser, jvmSystem, machineTotal)
        - `jdk.ThreadCPULoad` - Per-thread CPU (fields: user, system, eventThread)

        ## GC
        - `jdk.GCPhasePause` - GC pause phases (fields: name, duration)
        - `jdk.GarbageCollection` - GC events (fields: name, cause, duration)
        - `jdk.GCHeapSummary` - Heap state (fields: when, heapUsed, heapSpace)
        - `jdk.G1HeapSummary` - G1 heap (fields: edenUsedSize, survivorUsedSize)
        - `jdk.YoungGarbageCollection` - Young GC (fields: tenuringThreshold)
        - `jdk.OldGarbageCollection` - Old GC

        ## Memory
        - `jdk.ObjectAllocationSample` - Sampled allocations (fields: objectClass, weight)
        - `jdk.ObjectAllocationInNewTLAB` - TLAB allocations (fields: objectClass, tlabSize)
        - `jdk.ObjectAllocationOutsideTLAB` - Large allocations (fields: objectClass, allocationSize)

        ## I/O
        - `jdk.FileRead` - File reads (fields: path, bytes, duration)
        - `jdk.FileWrite` - File writes (fields: path, bytes, duration)
        - `jdk.SocketRead` - Socket reads (fields: host, port, bytes, duration)
        - `jdk.SocketWrite` - Socket writes (fields: host, port, bytes, duration)

        ## Threads
        - `jdk.ThreadStart` - Thread creation (fields: thread, parentThread)
        - `jdk.ThreadEnd` - Thread termination (fields: thread)
        - `jdk.ThreadSleep` - Thread.sleep (fields: time, eventThread)
        - `jdk.ThreadPark` - LockSupport.park (fields: timeout, eventThread)

        ## Locking
        - `jdk.JavaMonitorEnter` - synchronized enter (fields: monitorClass, duration)
        - `jdk.JavaMonitorWait` - Object.wait (fields: monitorClass, timeout, duration)
        - `jdk.JavaMonitorInflate` - Monitor inflation (fields: monitorClass)

        ## Compilation
        - `jdk.Compilation` - JIT compilation (fields: method, duration, isOsr)
        - `jdk.CompilerPhase` - Compilation phases (fields: name, duration)

        ## Class Loading
        - `jdk.ClassLoad` - Class loading (fields: loadedClass, duration)
        - `jdk.ClassDefine` - Class definition (fields: definedClass)

        ## Common Field Paths
        - `eventThread/javaName` - Thread name
        - `eventThread/javaThreadId` - Thread ID
        - `stackTrace/frames` - Stack frames array
        - `stackTrace/frames/0/method/name/string` - Top frame method name
        - `duration` - Event duration (nanoseconds)
        - `startTime` - Event start time
        """;
  }

  public String getToolsHelp() {
    return """
        # Choosing the Right jfr_* Tool

        ## CPU Profiling Tools

        ### jfr_hotmethods
        Best for: Quick identification of which methods consume the most CPU.
        Returns: Flat ranked list of leaf methods with sample counts and percentages.
        Use when: You need a fast answer to "where is CPU time going?" without call-path context.

        ### jfr_flamegraph
        Best for: Understanding call paths and how code reaches hot methods.
        Returns: Aggregated stack traces in folded (semicolon-separated) or tree (JSON) format.
        Use when: You need to see the full call hierarchy — which callers lead to a hotspot,
        or which entry points fan out into expensive subtrees. Output is designed for
        visualization tools or structural call-path reasoning.

        ### jfr_stackprofile
        Best for: Detecting temporal patterns and thread affinity in CPU hotspots.
        Returns: Structured JSON with per-frame time-bucket arrays, per-thread sample counts,
        numeric percentages, and a derived category (normal / hotspot / steady-hotspot).
        Use when: You need to programmatically analyze how a method's CPU usage changes over
        the recording duration (bursty vs steady), identify N+1 query patterns (steady hotspots),
        or determine which threads are contributing to a hotspot. The time-bucket arrays let you
        detect intermittent load spikes that would be invisible in aggregated flamegraph data.

        ### Decision Guide
        ```
        Need a quick "top CPU consumers" list?          → jfr_hotmethods
        Need to understand call paths / callers?        → jfr_flamegraph
        Need temporal patterns or thread breakdown?     → jfr_stackprofile
        Need all of the above for deep investigation?   → Start with jfr_stackprofile,
                                                          then jfr_flamegraph for call paths
        ```

        ## Other Specialized Tools

        ### jfr_summary
        Recording overview: duration, event counts, JVM info. Start here for orientation.

        ### jfr_diagnose
        Automated performance triage. Runs multiple checks and returns findings + recommendations.

        ### jfr_use
        USE Method analysis (Utilization, Saturation, Errors) for CPU, memory, threads, and I/O.

        ### jfr_tsa
        Thread State Analysis. Breaks down what threads are doing: running, blocked, waiting, I/O.

        ### jfr_exceptions
        Exception pattern analysis: types, frequencies, and common throw sites.

        ### jfr_callgraph
        Call graph between methods. Shows caller→callee relationships with sample weights.

        ### jfr_query
        General-purpose JfrPath queries for anything not covered by specialized tools.
        Use jfr_help(topic="pipeline") and jfr_help(topic="filters") for query syntax.
        """;
  }

}
