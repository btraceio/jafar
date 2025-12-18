# GC Impact Analysis Example

This example demonstrates using `decorateByTime` to understand how garbage collection affects application behavior.

## Scenario

You want to understand what your application is doing during GC pauses, or correlate allocations, I/O, and CPU activity with specific GC phases.

## Queries

### Execution samples during GC phases

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name,duration)
```

This shows which code is running during different GC phases (concurrent mark, remark, etc.).

### Group samples by GC phase

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name)
```

### Allocations during GC

```bash
show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)
```

This shows total allocation size during each GC phase.

### File I/O during GC

```bash
show events/jdk.FileRead | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=bytes)
```

Identifies if I/O operations are happening during GC (may indicate blocked threads).

### Safe point correlation

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.SafepointBegin, fields=safepointId,operations)
```

Correlate execution samples with safepoint pauses.

## Analysis Patterns

### Before vs During vs After GC

Use different decorator events to understand timing:

```bash
# During GC phases
show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=sum, value=allocationSize)

# Immediate post-GC allocations
show events/jdk.ObjectAllocationSample | decorateByTime(jdk.GarbageCollection, fields=name)
```

### Thread behavior during GC

```bash
# Threads active during concurrent mark
show events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy(sampledThread/javaName, $decorator.name)
```

### Lock contention during GC

```bash
show events/jdk.JavaMonitorWait | decorateByTime(jdk.GCPhase, fields=name)
  | groupBy($decorator.name, agg=count)
```

Shows if lock contention increases during specific GC phases.

## Understanding Results

- **Null decorator fields**: Events occurring outside any GC phase
- **High activity during phases**: May indicate application work affecting GC efficiency
- **Patterns by phase name**:
  - "Concurrent Mark" - concurrent phase, application threads active
  - "Remark" - stop-the-world pause, application threads paused
  - "Cleanup" - final cleanup phase

## Related Events

- `jdk.GarbageCollection` - Overall GC events with duration
- `jdk.GCPhasePause` - Individual pause events
- `jdk.GCHeapSummary` - Heap state before/after GC
- `jdk.SafepointBegin/End` - Safepoint pauses
- `jdk.G1MMU` - G1 GC MMU statistics
- `jdk.ZPageAllocation` - ZGC page allocations

## Tips

1. Filter to specific GC names: `events/jdk.GCPhase[gcId=123]`
2. Focus on pause phases for performance impact
3. Correlate with heap summaries to understand memory pressure
4. Compare concurrent vs pause phases for application impact
5. Use with thread dumps to identify what's blocked during GC
