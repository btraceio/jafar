# Monitor Contention Analysis Example

This example demonstrates using `decorateByTime` to analyze monitor contention by correlating execution samples with monitor wait events.

## Scenario

You want to understand which locks are causing thread contention in your application. By decorating execution samples with concurrent monitor wait events on the same thread, you can identify which monitors are being waited on during CPU sampling.

## Query

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
```

## Breakdown

1. **Primary Event**: `jdk.ExecutionSample` - CPU profiling samples
2. **Decorator Event**: `jdk.JavaMonitorWait` - Monitor wait events with duration
3. **Time Overlap**: Matches samples that occur during monitor waits on the same thread
4. **Decorator Fields**: `monitorClass` (which lock), `duration` (how long waiting)

## Analysis Queries

### Count samples during lock waits by monitor class

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | groupBy($decorator.monitorClass)
```

### Top monitors by contended sample count

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)
  | groupBy($decorator.monitorClass, agg=count) | top(10, by=count)
```

### Samples with deep stacks during lock waits

```bash
show events/jdk.ExecutionSample[len(stackTrace/frames)>20]
  | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
```

### Average wait duration per monitor

```bash
show events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
  | groupBy($decorator.monitorClass, agg=avg, value=$decorator.duration)
```

## Understanding Results

- Events with `$decorator.monitorClass` not null: execution sample occurred during a monitor wait
- Events with `$decorator.monitorClass` null: execution sample occurred outside any monitor wait
- High sample counts for a specific monitor indicate contention hotspots
- Can correlate with stack traces to understand what code is contending

## Related Events

- `jdk.JavaMonitorEnter` - Monitor entry attempts
- `jdk.JavaMonitorInflate` - Monitor inflation events
- `jdk.ThreadPark` - Thread parking events (similar analysis possible)
